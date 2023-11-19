(ns powerpack.export
  (:require [clansi.core :as ansi]
            [clojure.core.memoize :as memoize]
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
            [powerpack.web :as web]
            [stasis.core :as stasis]))

(def optimize
  (-> (fn [assets options]
        (-> assets
            (optimizations/all options)
            (->> (remove :bundled)
                 (remove :outdated))))
      (memoize/lru {} :lru/threshold 3)))

(defn extract-data
  "Loops over the generated pages, extracting link targets and images from them"
  [powerpack pages]
  (for [[uri page] (filter (comp string? second) pages)]
    (page/extract-page-data {:powerpack/app powerpack} uri page)))

(defn get-image-assets [powerpack export-data]
  (->> (mapcat :assets export-data)
       (filter #(imagine/image-url? % (:imagine/config powerpack)))
       set))

(defn get-ex-datas [e]
  (->> (concat [(ex-data e)]
               (when-let [cause (.getCause e)]
                 (get-ex-datas cause)))
       (remove nil?)))

(defn export-images [powerpack export-data]
  (doseq [image (get-image-assets powerpack export-data)]
    (-> image
        imagine/image-spec
        (imagine/inflate-spec (:imagine/config powerpack))
        (imagine/transform-image-to-file (str (:powerpack/build-dir powerpack) image)))))

(defn- load-export-dir [export-directory]
  (stasis/slurp-directory export-directory #"\.[^.]+$"))

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

(defn get-export-data [powerpack]
  (try
    (let [assets (optimize (assets/get-assets powerpack) {})
          ctx {:optimus-assets assets}
          pages (log/with-monitor :info "Loading pages"
                  (web/get-pages (d/db (:datomic/conn powerpack)) ctx powerpack))]
      {:pages pages
       :ctx ctx
       :assets assets
       :page-data (log/with-monitor :info "Extracting links and asset URLs"
                    (extract-data powerpack pages))})
    (catch Exception e
      (or (first (filter :powerpack/problem (get-ex-datas e)))
          {:powerpack/problem ::export-exception
           :exception e}))))

(defn export-site [powerpack {:keys [pages ctx assets page-data]}]
  (log/with-monitor :info (str "Exporting " (count pages) " pages")
    (stasis/export-pages pages (:powerpack/build-dir powerpack) ctx))
  (log/with-monitor :info "Exporting assets"
    (export/save-assets assets (:powerpack/build-dir powerpack)))
  (when (:imagine/config powerpack)
    (log/info (str "Exporting images from:\n" (format-asset-targets powerpack "  ")))
    (log/with-monitor :info "Exporting images"
      (-> (update powerpack :imagine/config assoc :cacheable-urls? true)
          (export-images page-data)))))

(defn format-exception [e]
  (str (str/replace (.getMessage e) #"^([a-zA-Z]\.?)+: " "")
       (when-let [cause (.getCause e)]
         (str "\n    Caused by: " (format-exception cause)))))

(defn pprs [x log-level]
  (->> x
       (walk/postwalk
        (fn [x]
          (cond-> x
            (and (not= :debug log-level)
                 (map? x))
            (dissoc :powerpack/app :app/db :i18n/dictionaries))))
       pprint/pprint
       with-out-str))

(defn format-report [powerpack validation]
  (let [log-level (:powerpack/log-level powerpack)]
    (case (:powerpack/problem validation)
      ::export-exception
      (str "Encountered an exception while creating pages\n"
           (when-let [e (:exception validation)]
             (str (format-exception e) "\n"
                  (when-let [data (get-ex-datas e)]
                    (str "\nException data:\n"
                         (str/join "\n" (map #(pprs % log-level) data))
                         "\n\n"))
                  (if (= :debug log-level)
                    (with-out-str (.printStackTrace e))
                    (str "Run export with :powerpack/log-level set to :debug for full stack traces\n"
                         "and data listings")))))

      :m1p/discrepancies
      (str "i18n dictionaries are not in good shape\n"
           (v/format-report
            (:dictionaries validation)
            (:problems validation)))

      :powerpack/bad-links
      (page/format-broken-links (:links validation))

      :powerpack/missing-asset
      (str (:uri validation) " is referring to missing asset " (:path validation) ":\n\n"
           (:html validation) "\n\n"
           "Any asset to be exported by Powerpack must be configured as an Optimus asset\n"
           "or handled by the imagine image manipulation pipeline. Please check the relevant\n"
           "configurations and/or the referred asset path.\n\n"
           (pprs (select-keys powerpack [:imagine/config :optimus/bundles :optimus/assets]) log-level)))))

(defn print-heading [s entries color]
  (let [num (count entries)]
    (println (ansi/style (format s num (if (= 1 num) "file" "files")) color))))

(defn print-file-names [file-names]
  (let [n (count file-names)
        print-max 50]
    (doseq [path (take print-max (sort file-names))]
      (println "    -" path))
    (when (< print-max n)
      (print "    and" (- n print-max) "more"))))

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

(defn print-report [powerpack {:keys [old-files validation]}]
  (let [new-files (load-export-dir (:powerpack/build-dir powerpack))]
    (if (:powerpack/problem validation)
      (do
        (log/info "Detected problems in exported site, deployment is not advised")
        (log/info (ansi/style (format-report powerpack validation) :red)))
      (do
        (log/info "Export complete:")
        (report-differences old-files new-files)))))

(defn validate-app [powerpack export-data]
  (or (when (:powerpack/problem export-data)
        export-data)
      (when-let [dicts (some-> powerpack :i18n/dictionaries deref)]
        (when-let [problems (seq (concat
                                  (v/find-non-kw-keys dicts)
                                  (v/find-missing-keys dicts)
                                  (v/find-misplaced-interpolations dicts)
                                  (v/find-type-discrepancies dicts)))]
          {:powerpack/problem :m1p/discrepancies
           :dictionaries dicts
           :problems problems}))
      (let [broken-links (seq (page/find-broken-links powerpack export-data))]
        {:powerpack/problem :powerpack/bad-links
         :links broken-links})
      {}))

(defn conclude-export [powerpack opt]
  (app/stop powerpack)
  (print-report powerpack opt)
  (when-let [stop (:stop (:logger opt))]
    (stop))
  {:success? (nil? (:powerpack/problem (:validation opt)))})

(defn export [app-options & [opt]]
  (log/with-timing :info "Ran Powerpack export"
    (let [powerpack (log/with-monitor :info "Creating app" (app/create-app app-options))
          logger (log/start-logger (:powerpack/log-level powerpack))
          old-files (log/with-monitor :info "Loading previous export"
                      (load-export-dir (:powerpack/build-dir powerpack)))
          _ (app/start powerpack)
          export-data (get-export-data powerpack)
          validation (log/with-monitor :info "Validating app"
                       (validate-app powerpack export-data))]
      (if (:powerpack/problem validation)
        (conclude-export powerpack {:logger logger :validation validation})
        (do
          (log/with-monitor :info "Clearing build directory"
            (stasis/empty-directory! (:powerpack/build-dir powerpack)))
          (export-site powerpack export-data)
          (->> (merge opt {:old-files old-files
                           :logger logger})
               (conclude-export powerpack)))))))

(defn export! [app-options & [opt]]
  (let [res (export app-options opt)]
    (when-not (:success? res)
      (System/exit 1))))
