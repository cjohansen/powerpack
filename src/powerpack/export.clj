(ns powerpack.export
  (:require [clojure.core.memoize :as memoize]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [datomic-type-extensions.api :as d]
            [imagine.core :as imagine]
            [m1p.core :as m1p]
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

(defn export-site [powerpack]
  (let [assets (optimize (assets/get-assets powerpack) {})
        ctx {:optimus-assets assets}
        pages (log/with-monitor :info "Loading pages"
                (web/get-pages (d/db (:datomic/conn powerpack)) ctx powerpack))]
    (log/with-monitor :info (str "Exporting " (count pages) " pages")
      (stasis/export-pages pages (:powerpack/build-dir powerpack) ctx))
    (log/with-monitor :info "Exporting assets"
      (export/save-assets assets (:powerpack/build-dir powerpack)))
    (let [page-data (extract-data powerpack pages)]
      (when (:imagine/config powerpack)
        (log/info (str "Exporting images from:\n" (format-asset-targets powerpack "  ")))
        (log/with-monitor :info "Exporting images"
          (-> (update powerpack :imagine/config assoc :cacheable-urls? true)
              (export-images page-data))))
      {:pages pages
       :assets assets
       :page-data page-data})))

(defn validate-export [powerpack export]
  (let [broken-links (seq (page/find-broken-links powerpack export))]
    {:valid? (not broken-links)
     :validator :powerpack/link-check
     :problems (->> [(when broken-links
                       {:kind :broken-links
                        :data broken-links})]
                    (remove nil?))}))

(defn format-problem [{:keys [kind data]}]
  (case kind
    :broken-links (page/format-broken-links data)))

(defn format-report [validation]
  (case (:validator validation)
    :m1p/validator
    (str "i18n dictionaries are not in good shape\n"
         (v/format-report
      (:dictionaries validation)
      (:problems validation)))

    :powerpack/link-check
    (->> (:problems validation)
         (map format-problem)
         (str/join "\n\n"))))

(defn print-report [powerpack {:keys [old-files validation format]}]
  (let [new-files (load-export-dir (:powerpack/build-dir powerpack))]
    (if (= format :json)
      (log/info (-> (stasis/diff-maps old-files new-files)
                    (dissoc :unchanged)
                    (merge validation)
                    json/write-str))
      (if (:valid? validation)
        (do
          (log/info "Export complete:")
          (stasis/report-differences old-files new-files))
        (do
          (log/info "Detected problems in exported site, deployment is not advised")
          (log/info (format-report validation)))))))

(defn validate-app [app]
  (or (when-let [dicts (some-> app :i18n/dictionaries deref)]
        (when-let [problems (seq (concat
                                  (v/find-non-kw-keys dicts)
                                  (v/find-unqualified-keys dicts)
                                  (v/find-missing-keys dicts)
                                  (v/find-misplaced-interpolations dicts)
                                  (v/find-type-discrepancies dicts)
                                  (v/find-interpolation-discrepancies dicts)
                                  (v/find-fn-get-param-discrepancies dicts)))]
          {:valid? false
           :validator :m1p/validator
           :dictionaries dicts
           :problems problems}))
      {:valid? true}))

(defn conclude-export [powerpack opt]
  (app/stop powerpack)
  (print-report powerpack opt)
  (when-let [stop (:stop (:logger opt))]
    (stop))
  {:success? (:valid? (:validation opt))})

(defn export [app-options & [opt]]
  (log/with-timing :info "Ran Powerpack export"
    (let [powerpack (log/with-monitor :info "Creating app" (app/create-app app-options))
          logger (log/start-logger (:powerpack/log-level powerpack))
          old-files (log/with-monitor :info "Loading previous export"
                      (load-export-dir (:powerpack/build-dir powerpack)))
          _ (app/start powerpack)
          pre-validation (validate-app powerpack)]
      (if-not (:valid? pre-validation)
        (conclude-export powerpack {:logger logger :validation pre-validation})
        (do
          (log/with-monitor :info "Clearing build directory"
            (stasis/empty-directory! (:powerpack/build-dir powerpack)))
          (let [export (export-site powerpack)
                validation (log/with-monitor :info "Validating export"
                             (validate-export powerpack export))]
            (->> (merge opt {:old-files old-files
                             :validation validation
                             :logger logger})
                 (conclude-export powerpack))))))))

(defn export! [app-options & [opt]]
  (let [res (export app-options opt)]
    (when-not (:success? res)
      (System/exit 1))))
