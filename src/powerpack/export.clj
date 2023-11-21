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
            [stasis.core :as stasis]))

(defn create-fs-exporter []
  (reify
    powerpack/IFileSystem
    (read-file [_ path]
      (let [file (io/file path)]
        (when (.exists file)
          (slurp file))))

    (get-entries [_ path]
      (file-seq (io/file path)))

    (write-file [_ path content]
      (spit path content))

    powerpack/IOptimus
    (export-assets [_ assets build-dir]
      (export/save-assets assets build-dir))

    powerpack/IStasis
    (slurp-directory [_ path re]
      (stasis/slurp-directory path re))

    (export-page [_ uri body build-dir]
      (stasis/export-page uri body build-dir {}))

    (empty-directory! [_ dir]
      (stasis/empty-directory! dir))

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

(defn get-image-assets [powerpack export-data]
  (->> (mapcat :assets export-data)
       (filter #(imagine/image-url? % (:imagine/config powerpack)))
       set))

(defn get-ex-datas [e]
  (->> (concat [(ex-data e)]
               (when-let [cause (.getCause e)]
                 (get-ex-datas cause)))
       (remove nil?)))

(defn export-images [exporter powerpack export-data]
  (doseq [image (get-image-assets powerpack export-data)]
    (powerpack/transform-image-to-file
     exporter
     (-> image
         imagine/image-spec
         (imagine/inflate-spec (:imagine/config powerpack)))
     (str (:powerpack/build-dir powerpack) image))))

(defn load-export [exporter powerpack & [{:keys [max-files]}]]
  (let [dir (:powerpack/build-dir powerpack)
        etags (some->> (str dir "/etags.edn")
                       (powerpack/read-file exporter)
                       read-string)]
    (-> (if (< (count (powerpack/get-entries exporter dir)) (or max-files 1000))
          {:complete? true
           :files (powerpack/slurp-directory exporter dir #"\.[^.]+$")}
          {:complete? false
           :files (->> (keys etags)
                       (keep #(when-let [content (powerpack/read-file exporter (str dir %))]
                                [% content])))})
        (update :files
                #(->> %
                      (map (fn [[file content]]
                             [file (cond-> {:content content}
                                     (get etags file) (assoc :etag (get etags file)))]))
                      (into {}))))))

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

(defn get-export-data [exporter powerpack {:keys [full-diff-max-files]}]
  (let [previous-export (log/with-monitor :info "Loading previous export"
                          (load-export exporter powerpack {:max-files full-diff-max-files}))
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
     :previous-export previous-export}))

(defn get-cached-page [{:keys [etags previous-export]} page]
  (when (and (:page/etag page) (= (:page/etag page) (get etags (:page/uri page))))
    (get (:files previous-export) (:page/uri page))))

(defn render-page [powerpack {:keys [ctx db] :as export-data} page]
  (if-let [cached (get-cached-page export-data page)]
    (do
      (log/debug "Reusing" (:page/uri page) "from previous export")
      {:elapsed 0
       :cached? true
       :body (:content cached)})
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

(defn detect-problems [powerpack export-data page body]
  (when (string? body)
    (let [page-data (page/extract-page-data {:powerpack/app powerpack} (:page/uri page) body)]
      (when-let [broken-links (page/find-broken-links powerpack export-data page-data)]
        {:powerpack/problem :powerpack/broken-links
         :uri (:page/uri page)
         :links broken-links}))))

(defn export-page [exporter powerpack export-data {:page/keys [uri] :as page}]
  (let [{:keys [body elapsed cached?] :as res} (render-page powerpack export-data page)]
    (or (when (:powerpack/problem res)
          res)
        (detect-problems powerpack export-data page body)
        (when (nil? body)
          {:problem :powerpack/empty-body
           :uri uri})
        (try
          (powerpack/export-page exporter uri body (:powerpack/build-dir powerpack))
          (catch Exception e
            {:powerpack/problem ::exception
             :message "Encountered an exception while creating pages"
             :exception e}))
        {:uri uri
         :elapsed elapsed
         :cached? cached?})))

(defn export-pages [exporter powerpack {:keys [pages] :as export-data}]
  (log/with-monitor :info
    (format "Rendering, validating and exporting %d pages" (count pages))
    (let [results (pmap #(export-page exporter powerpack export-data %) pages)]
      (if-let [problems (seq (filter :powerpack/problem results))]
        {:powerpack/problem ::export-failed
         :problems problems}
        {:exported-pages results}))))

(defn export-site [exporter powerpack export-data]
  (let [result (export-pages exporter powerpack export-data)]
    (when-not (:powerpack/problem result)
      (log/with-monitor :info "Exporting assets"
        (powerpack/export-assets exporter (:assets export-data) (:powerpack/build-dir powerpack)))
      (when (:imagine/config powerpack)
        (log/info (str "Exporting images from:\n" (format-asset-targets powerpack "  ")))
        (log/with-monitor :info "Exporting images"
          (-> (update powerpack :imagine/config assoc :cacheable-urls? true)
              (export-images exporter export-data))))
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

(defn group-problems [problems]
  (->> (group-by :powerpack/problem problems)
       (mapcat
        (fn [[k problems]]
          (if (= k :powerpack/broken-links)
            [{:powerpack/problem :powerpack/broken-links
              :links (mapcat (fn [{:keys [uri links]}]
                               (map #(assoc % :uri uri) links)) problems)}]
            problems)))))

(defn format-report [powerpack result]
  (case (:powerpack/problem result)
    ::export-failed
    (->> (group-problems (:problems result))
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

(defn print-heading [s entries color]
  (let [num (count entries)]
    (log/info (ansi/style (format s num (if (= 1 num) "file" "files")) color))))

(defn print-file-names [file-names]
  (let [n (count file-names)
        print-max 50]
    (doseq [path (take print-max (sort file-names))]
      (log/info "    -" path))
    (when (< print-max n)
      (log/info "    and" (- n print-max) "more"))))

(defn report-differences [old new]
  (let [{:keys [added removed changed unchanged]} (stasis/diff-maps old new)]
    (if (and (empty? removed)
             (empty? changed)
             (empty? unchanged))
      (print-heading "- First export! Created %s %s." added :green)
      (do
        (when (seq unchanged)
          (print-heading "- %s unchanged %s." unchanged :cyan))
        (when (seq changed)
          (print-heading "- %s changed %s:" changed :yellow)
          (print-file-names changed))
        (when (seq removed)
          (print-heading "- %s removed %s:" removed :red)
          (print-file-names removed))
        (when (seq added)
          (print-heading "- %s added %s:" added :green)
          (print-file-names added))))))

(defn report-elapsed-percentile [n pages]
  (str n "th percentile: "
       (->> (sort-by :elapsed pages)
            (take (int (* (/ n 100) (count pages))))
            last
            :elapsed) "ms"))

(defn print-report [exporter powerpack export-data result {:keys [full-diff-max-files]}]
  (if (:powerpack/problem result)
    (do
      (log/info "Detected problems in exported site, deployment is not advised")
      (log/info (ansi/style (format-report powerpack result) :red)))
    (let [export (load-export exporter powerpack {:max-files full-diff-max-files})
          {:keys [exported-pages]} result
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
      (if (:complete? export)
        (report-differences (:files (:previous-export export-data)) (:files export))
        (log/info (format "Exported %d pages" (count (:pages export-data))))))))

;; Borrowed from Stasis
(defn- statically-servable-uri? [^String uri]
  (or (.endsWith uri "/")
      (not (re-find #"/[^./]+$" uri))))

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
                                 (remove statically-servable-uri?)
                                 seq)]
        {:powerpack/problem ::unservable-urls
         :urls unservable})))

(defn export* [exporter app-options opt]
  (log/with-timing :info "Ran Powerpack export"
    (let [powerpack (log/with-monitor :info "Creating app" (app/create-app app-options))
          _ (app/start powerpack)
          export-data (get-export-data exporter powerpack opt)]
      (log/with-monitor :info "Clearing build directory"
        (powerpack/empty-directory! exporter (:powerpack/build-dir powerpack)))
      (let [result (or (validate-app powerpack export-data)
                       (export-site exporter powerpack export-data))]
        (app/stop powerpack)
        (print-report exporter powerpack export-data result opt)
        {:success? (nil? (:powerpack/problem result))}))))

(defn export
  "Export the site. `opt` is an optional map of options:

  - `full-diff-max-files` The maximum number of files to perform a full diff of
    the export. If the number of exported files is higher than this, just report
    the number of exported files. Defaults to 1000."
  [app-options & [opt]]
  (let [logger (log/start-logger (or (:powerpack/log-level app-options) :info))]
    (export* (create-fs-exporter) app-options opt)
    (when-let [stop (:stop logger)]
      (stop))))

(defn export! [app-options & [opt]]
  (let [res (export app-options opt)]
    (when-not (:success? res)
      (System/exit 1))))
