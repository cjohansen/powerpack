(ns powerpack.export
  (:require [clojure.core.memoize :as memoize]
            [clojure.data.json :as json]
            [datomic-type-extensions.api :as d]
            [imagine.core :as imagine]
            [optimus.export :as export]
            [optimus.optimizations :as optimizations]
            [powerpack.app :as app]
            [powerpack.assets :as assets]
            [powerpack.web :as web]
            [stasis.core :as stasis]))

(def optimize
  (-> (fn [assets options]
        (-> assets
            (optimizations/all options)
            (->> (remove :bundled)
                 (remove :outdated))))
      (memoize/lru {} :lru/threshold 3)))

(defn get-image-assets [powerpack]
  (->> (stasis/slurp-directory (:powerpack/build-dir powerpack) #"\.html+$")
       vals
       (mapcat #(assets/extract-asset-urls {:powerpack/app powerpack} %))
       (filter #(imagine/image-url? % (:imagine/config powerpack)))
       set))

(defn export-images [powerpack]
  (doseq [image (get-image-assets powerpack)]
    (-> image
        imagine/image-spec
        (imagine/inflate-spec (:imagine/config powerpack))
        (imagine/transform-image-to-file (str (:powerpack/build-dir powerpack) image)))))

(defn- load-export-dir [export-directory]
  (stasis/slurp-directory export-directory #"\.[^.]+$"))

(defn print-report [powerpack old-files {:keys [format]}]
  (let [new-files (load-export-dir (:powerpack/build-dir powerpack))]
    (if (= format :json)
      (println (json/write-str (dissoc (stasis/diff-maps old-files new-files) :unchanged)))
      (do
        (println)
        (println "Export complete:")
        (stasis/report-differences old-files new-files)
        (println)))))

(defn export [options & [opt]]
  (let [powerpack (app/create-app options)
        export-directory (:powerpack/build-dir powerpack)
        old-files (load-export-dir (:powerpack/build-dir powerpack))
        assets (optimize (assets/get-assets powerpack) {})
        request {:optimus-assets assets}]
    (app/start powerpack)
    (stasis/empty-directory! export-directory)
    (export/save-assets assets export-directory)
    (stasis/export-pages
     (web/get-pages (d/db (:datomic/conn powerpack)) request powerpack)
     export-directory
     request)
    (println "Exporting images from <img> <source> <meta property=\"og:image\"> and select style attributes")
    (when (:imagine/config powerpack)
      (export-images (update powerpack :imagine/config assoc :cacheable-urls? true)))
    (print-report powerpack old-files opt)))
