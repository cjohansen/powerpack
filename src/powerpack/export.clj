(ns powerpack.export
  (:require [clansi.core :as ansi]
            [clojure.core.memoize :as memoize]
            [clojure.java.io :as io]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [datomic-type-extensions.api :as d]
            [imagine.core :as imagine]
            [m1p.validation :as v]
            [optimus.export :as export]
            [optimus.optimizations :as optimizations]
            [powerpack.app :as app]
            [powerpack.assets :as assets]
            [powerpack.logger :as log]
            [powerpack.page :as page]
            [powerpack.protocols :as powerpack]
            [powerpack.web :as web]
            [stasis.core :as stasis])
  (:import (java.nio.file CopyOption Files StandardCopyOption)))

(def copy-options (into-array CopyOption [StandardCopyOption/REPLACE_EXISTING]))

(defn ->path [s]
  (.toPath (io/file s)))

(defn create-fs-exporter []
  (reify
    powerpack/IFileSystem
    (file-exists? [_ path]
      (.exists (io/file path)))

    (read-file [_ path]
      (let [file (io/file path)]
        (when (.exists file)
          (slurp file))))

    (get-entries [_ path]
      (file-seq (io/file path)))

    (write-file [_ path content]
      (spit path content))

    (delete-file [_ path]
      (let [file (io/file path)]
        (when (.isDirectory file)
          (stasis/empty-directory! path))
        (.delete file)))

    (move [_ source-path dest-path]
      (let [dest (->path dest-path)]
        (.mkdirs (io/file (str (.getParent dest))))
        (Files/move (->path source-path) dest copy-options)))

    (copy [_ source-path dest-path]
      (let [source (->path source-path)
            dest (->path dest-path)]
        (.mkdirs (io/file (str (.getParent dest))))
        (Files/copy source dest copy-options)))

    (get-tmp-path [_]
      (let [tmp (System/getProperty "java.io.tmpdir")]
        (str tmp
             (when-not (re-find #"/$" tmp) "/")
             "powerpack/"
             (random-uuid))))

    powerpack/IOptimus
    (export-assets [_ assets build-dir]
      (export/save-assets assets build-dir))

    powerpack/IStasis
    (export-page [_ uri body build-dir]
      (stasis/export-page uri body build-dir {}))

    powerpack/IImagine
    (transform-image-to-file [_ transformation path]
      (imagine/transform-image-to-file transformation path))))

(def optimize
  (-> (fn [assets options]
        (-> assets
            (optimizations/all options)
            (->> (remove :bundled)
                 (remove :outdated))))
      (memoize/lru {} :lru/threshold 3)))

(defn get-image-assets [powerpack result]
  (->> (:exported-pages result)
       (mapcat (comp :assets :page-data))
       set
       (filter #(imagine/image-url? % (:imagine/config powerpack)))
       set))

(defn get-ex-datas [e]
  (->> (concat [(ex-data e)]
               (when-let [cause (.getCause e)]
                 (get-ex-datas cause)))
       (remove nil?)))

(defn export-images [powerpack exporter result]
  (doseq [image (get-image-assets powerpack result)]
    (log/debug "Export image" image)
    (powerpack/transform-image-to-file
     exporter
     (-> image
         imagine/image-spec
         (imagine/inflate-spec (:imagine/config powerpack)))
     (str (:powerpack/build-dir powerpack) image))))

(defn move-previous-export [exporter powerpack]
  (when (powerpack/file-exists? exporter (:powerpack/build-dir powerpack))
    (log/with-monitor :info "Temporarily backing up previous export"
      (let [dir (powerpack/get-tmp-path exporter)]
        (powerpack/move exporter (:powerpack/build-dir powerpack) dir)
        dir))))

(defn format-asset-targets [powerpack indent]
  (->> (for [{:keys [selector attr]} (:powerpack/asset-targets powerpack)]
         (let [[element attr-m] (-> (str/join " " (remove (comp #{"head" "svg"} str) selector))
                                    (str/replace #"\[src\]" "")
                                    (str/replace #"\[srcset\]" "")
                                    (str/split #"\["))]
           [(->> [(or (not-empty element) "*")
                  (some-> attr-m
                          (str/replace #"\]" "")
                          (str/replace #"(.*)=(.*)" "$1=\"$2\""))]
                 (remove nil?)
                 (str/join " "))
            attr]))
       (group-by first)
       (map (fn [[target xs]]
              (str indent "<" target "> " (str/join ", " (map second xs)))))
       (str/join "\n")))

(defn get-pages-to-export [db]
  (d/q '[:find [(pull ?p [:page/uri :page/etag]) ...]
         :where [?p :page/uri]]
       db))

(defn get-export-data [exporter powerpack]
  (let [previous-export-dir (move-previous-export exporter powerpack)
        db (d/db (:datomic/conn powerpack))
        pages (get-pages-to-export db)
        assets (optimize (assets/get-assets powerpack) {})
        ctx {:optimus-assets assets}]
    {:pages pages
     :urls (set (map :page/uri pages))
     :etags (->> (map (juxt :page/uri :page/etag) pages)
                 (filter second)
                 (into {}))
     :ctx ctx
     :db db
     :assets assets
     :previous-etags (some->> (str previous-export-dir "/etags.edn")
                              (powerpack/read-file exporter)
                              read-string)
     :previous-export-dir previous-export-dir}))

(defn get-cached-path [exporter
                       {:keys [previous-etags previous-export-dir]}
                       {:page/keys [etag uri]}]
  (when (and previous-export-dir etag (= etag (get previous-etags uri)))
    (let [path (stasis/normalize-uri (str previous-export-dir uri))]
      (when (powerpack/file-exists? exporter path)
        path))))

(defn render-page [exporter powerpack {:keys [ctx db] :as export-data} page]
  (if-let [cached-path (get-cached-path exporter export-data page)]
    (do
      (log/debug "Reusing" (:page/uri page) "from previous export")
      {:elapsed 0
       :cached? true
       :path cached-path})
    (try
      (log/debug "Rendering" (:page/uri page))
      (let [start (System/currentTimeMillis)
            body (-> (assoc ctx :uri (:page/uri page) :app/db db)
                     (web/handle-request powerpack nil)
                     :body)]
        {:body body
         :elapsed (- (System/currentTimeMillis) start)})
      (catch Exception e
        {:powerpack/problem ::exception
         :message (str "Failed to render page " (:page/uri page))
         :uri (:page/uri page)
         :eception e}))))

(defn export-page [exporter powerpack export-data {:page/keys [uri] :as page}]
  (let [res (render-page exporter powerpack export-data page)
        dir (:powerpack/build-dir powerpack)]
    (or (when (:powerpack/problem res)
          res)
        (when (:cached? res)
          (let [path (stasis/normalize-uri (str dir uri))]
            (powerpack/copy exporter (:path res) path)
            (cond-> {:uri uri
                     :elapsed 0
                     :cached? true}
              (re-find #"\.html$" path)
              (assoc :page-data (page/extract-page-data
                                 {:powerpack/app powerpack}
                                 uri
                                 (powerpack/read-file exporter path))))))
        (when (nil? (:body res))
          {:problem :powerpack/empty-body
           :uri uri})
        (try
          (powerpack/export-page exporter uri (:body res) dir)
          (cond-> {:uri uri
                   :elapsed (:elapsed res)}
            (string? (:body res))
            (assoc :page-data (page/extract-page-data
                               {:powerpack/app powerpack}
                               uri
                               (:body res))))
          (catch Exception e
            {:powerpack/problem ::exception
             :message "Encountered an exception while creating pages"
             :exception e})))))

(defn export-pages [exporter powerpack {:keys [pages] :as export-data}]
  (log/with-monitor :info
    (format "Rendering, validating and exporting %d pages" (count pages))
    (let [results (pmap #(export-page exporter powerpack export-data %) pages)]
      (if-let [problems (seq (filter :powerpack/problem results))]
        {:powerpack/problem ::export-failed
         :problems problems}
        {:exported-pages results}))))

(defn strip-link-ids [page-data]
  (update page-data :links (fn [links] (map #(dissoc % :id) links))))

(defn validate-export [powerpack export-data {:keys [skip-link-hash-verification? link-ok?]} result]
  (let [page-data (cond->> (keep :page-data (:exported-pages result))
                    skip-link-hash-verification?
                    (map strip-link-ids))
        ctx {:url->ids (->> (filter :ids page-data)
                            (map (juxt :uri :ids))
                            (into {}))
             :urls (set (:urls export-data))
             :assets-urls (->> (:assets export-data)
                               (remove :outdated)
                               (map :path)
                               set)}]
    (if-let [links (seq (cond->> (mapcat #(page/find-broken-links powerpack ctx %) page-data)
                          (ifn? link-ok?) (remove #(link-ok? powerpack ctx %))))]
      {:powerpack/problem :powerpack/broken-links
       :links links}
      result)))

(defn export-site [exporter powerpack export-data]
  (let [result (export-pages exporter powerpack export-data)]
    (when-not (:powerpack/problem result)
      (log/with-monitor :info "Exporting assets"
        (powerpack/export-assets exporter (:assets export-data) (:powerpack/build-dir powerpack)))
      (when (:imagine/config powerpack)
        (log/info (str "Exporting images from:\n" (format-asset-targets powerpack "  ")))
        (log/with-monitor :info "Exporting images"
          (-> (update powerpack :imagine/config assoc :cacheable-urls? true)
              (export-images exporter result))))
      (when-let [etags (not-empty (:etags export-data))]
        (log/with-monitor :info "Exporting etags to etags.edn"
          (powerpack/write-file exporter (str (:powerpack/build-dir powerpack) "/etags.edn") (pr-str etags)))))
    result))

(defn pprs [x {:powerpack/keys [log-level]}]
  (->> x
       (walk/postwalk
        (fn [x]
          (cond-> x
            (and (not= :debug log-level)
                 (map? x))
            (dissoc :powerpack/app :app/db :i18n/dictionaries))))
       pprint/pprint
       with-out-str))

(defn format-exception-object [e]
  (str (str/replace (.getMessage e) #"^([a-zA-Z]\.?)+: " "")
       (when-let [cause (.getCause e)]
         (str "\n    Caused by: " (format-exception-object cause)))))

(defn format-exception [powerpack {:keys [exception]}]
  (when exception
    (str (format-exception-object exception) "\n"
         (when-let [data (get-ex-datas exception)]
           (str "\nException data:\n"
                (str/join "\n" (map #(pprs % powerpack) data))
                "\n\n"))
         (if (= :debug (:powerpack/log-level powerpack))
           (with-out-str (.printStackTrace exception))
           (str "Run export with :powerpack/log-level set to :debug for full stack traces\n"
                "and data listings")))))

(defn format-report [powerpack result]
  (case (:powerpack/problem result)
    ::export-failed
    (->> (:problems result)
         (map #(format-report powerpack %))
         (str/join "\n\n"))

    ::relative-urls
    (str "The following pages must have absolute paths:\n - "
         (str/join "\n - " (:urls result)))

    ::unservable-urls
    (str "The following page paths must end in a slash:\n - "
         (str/join "\n - " (:urls result)))

    ::exception
    (str (:message result) "\n"
         (format-exception powerpack (:exception result)))

    :m1p/discrepancies
    (str "i18n dictionaries are not in good shape\n"
         (v/format-report
          (:dictionaries result)
          (:problems result)))

    :powerpack/broken-links
    (page/format-broken-links (:links result))

    :powerpack/missing-asset
    (let [{:keys [uri path html]} result]
      (str uri " is referring to missing asset " path ":\n\n"
           html "\n\n"
           "Any asset to be exported by Powerpack must be configured as an Optimus asset\n"
           "or handled by the imagine image manipulation pipeline. Please check the relevant\n"
           "configurations and/or the referred asset path.\n\n"
           (when (io/resource (str (:powerpack/dev-assets-root-path powerpack) path))
             (str "This particular asset exists in your :powerpack/dev-assets-root-path dir,\n"
                  (:powerpack/dev-assets-root-path powerpack) ". "
                  "Development assets can not be used in production exports.\n\n"))
           (pprs (select-keys powerpack [:imagine/config
                                         :optimus/bundles
                                         :optimus/assets
                                         :powerpack/dev-assets-root-path])
                 powerpack)))

    :powerpack/empty-body
    (str (:uri result) " has nil body")))

(defn report-elapsed-percentile [n pages]
  (str n "th percentile: "
       (->> (sort-by :elapsed pages)
            (take (int (* (/ n 100) (count pages))))
            last
            :elapsed) "ms"))

(defn print-report [powerpack export-data result]
  (if (:powerpack/problem result)
    (do
      (log/info "Detected problems in exported site, deployment is not advised")
      (log/info (ansi/style (format-report powerpack result) :red)))
    (let [{:keys [exported-pages]} result
          by-elapsed (sort-by :elapsed (filter :elapsed exported-pages))]
      (log/info "Export complete")
      (when-let [cached (seq (filter :cached? exported-pages))]
        (log/info "Reused" (count cached) "pages from previous export"))
      (when (< 100 (count exported-pages))
        (log/info (->> ["Performance metrics:"
                        (report-elapsed-percentile 99 by-elapsed)
                        (report-elapsed-percentile 95 by-elapsed)
                        (report-elapsed-percentile 90 by-elapsed)
                        (report-elapsed-percentile 50 by-elapsed)]
                       (str/join "\n    "))))
      (when-let [slow (seq (filter #(< 1000 (:elapsed %)) by-elapsed))]
        (let [worst-offenders (reverse (take-last 10 slow))]
          (log/info (str (count slow) " pages took more than 1000ms to render\n"
                         "Top " (count worst-offenders) " slowest renders:\n"
                         (->> worst-offenders
                              (map (fn [{:keys [uri elapsed]}]
                                     (str uri " (" elapsed "ms)")))
                              (str/join "\n"))))))
      (log/info (format "Exported %d pages" (count (:pages export-data)))))))

(defn validate-app [powerpack {:keys [pages]}]
  (or (when-let [dicts (some-> powerpack :i18n/dictionaries deref)]
        (when-let [problems (seq (concat
                                  (v/find-non-kw-keys dicts)
                                  (v/find-missing-keys dicts)
                                  (v/find-misplaced-interpolations dicts)
                                  (v/find-type-discrepancies dicts)))]
          {:powerpack/problem :m1p/discrepancies
           :dictionaries dicts
           :problems problems}))
      (when-let [relative-urls (->> (map :page/uri pages)
                                    (remove #(re-find #"^/" %))
                                    seq)]
        {:powerpack/problem ::relative-urls
         :urls relative-urls})
      (when-let [unservable (->> (map :page/uri pages)
                                 (remove stasis/statically-servable-uri?)
                                 seq)]
        {:powerpack/problem ::unservable-urls
         :urls unservable})))

(defn pluralize [n s]
  (str n " " s (when (not= 1 n) "s")))

(defn log-etags [{:keys [previous-etags etags]}]
  (let [old-n (count previous-etags)
        n (count etags)]
    (when (< 0 old-n)
      (log/info (str "Comparing " (pluralize old-n "etag")
                     " from previous export to " (pluralize n "new one") ":\n"
                     (->> (for [[url etag] (take 10 previous-etags)]
                            (str "  - " url ": " etag " vs " (get etags url)))
                          (str/join "\n"))
                     (when (< 10 old-n)
                       "  ..."))))))

(defn export* [exporter app-options opt]
  (log/with-timing :info "Ran Powerpack export"
    (let [powerpack (log/with-monitor :info "Creating app" (app/create-app app-options))
          _ (app/start powerpack)
          export-data (get-export-data exporter powerpack)
          _ (log-etags export-data)
          result (or (validate-app powerpack export-data)
                     (->> (export-site exporter powerpack export-data)
                          (validate-export powerpack export-data opt)))]
      (app/stop powerpack)
      (when-let [dir (:previous-export-dir export-data)]
        (log/with-monitor :info "Clearing previous export"
          (powerpack/delete-file exporter dir)))
      (print-report powerpack export-data result)
      (assoc result :success? (nil? (:powerpack/problem result))))))

(defn export
  "Export the site. `opt` is an optional map of options:

  - `:skip-link-hash-verification?` If you use hash-URLs for frontend routing and
    are ok with links with hashes that aren't available as ids in the target
    document, set this to `true`. Defaults to `false`, e.g., verify that linked
    hashes do indeed exist on the target page (when the target page is one
    generated by the export - not external ones)."
  [app-options & [opt]]
  (let [logger (log/start-logger (or (:powerpack/log-level app-options) :info))
        result (export* (create-fs-exporter) app-options opt)]
    (when-let [stop (:stop logger)]
      (stop))
    result))

(defn export! [app-options & [opt]]
  (let [res (export app-options opt)]
    (when-not (:success? res)
      (System/exit 1))))
