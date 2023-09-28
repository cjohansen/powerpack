(ns powerpack.export
  (:require [clojure.core.memoize :as memoize]
            [clojure.data.json :as json]
            [clojure.string :as str]
            [datomic-type-extensions.api :as d]
            [html5-walker.core :as html5-walker]
            [imagine.core :as imagine]
            [optimus.export :as export]
            [optimus.optimizations :as optimizations]
            [powerpack.db :as db]
            [powerpack.ingest :as ingest]
            [powerpack.web :as web]
            [stasis.core :as stasis]))

(def optimize
  (-> (fn [assets options]
        (-> assets
            (optimizations/all options)
            (->> (remove :bundled)
                 (remove :outdated))))
      (memoize/lru {} :lru/threshold 3)))

(defn extract-style-urls [node]
  (some->> (.getAttribute node "style")
           (re-seq #"url\((.+?)\)")
           (map second)))

(defn extract-source-set-urls [node]
  (when-let [srcset (.getAttribute node "srcset")]
    (map #(first (str/split % #" ")) (str/split srcset #","))))

(defn extract-images [html]
  (-> (for [node (html5-walker/find-nodes html [:img])]
        (.getAttribute node "src"))
      (into (mapcat extract-source-set-urls (html5-walker/find-nodes html [:source])))
      (into (mapcat extract-style-urls (html5-walker/find-nodes html [:.style-img])))
      (into (->> (html5-walker/find-nodes html [:meta])
                 (filter #(= "og:image" (.getAttribute % "property")))
                 (map #(.getAttribute % "content"))))))

(defn get-images [pages-dir]
  (->> (stasis/slurp-directory pages-dir #"\.html+$")
       vals
       (mapcat extract-images)
       (into #{})))

(defn get-image-assets [pages-dir asset-config]
  (->> (get-images pages-dir)
       (filter #(imagine/image-url? % asset-config))))

(defn export-images [pages-dir dir asset-config]
  (doseq [image (get-image-assets pages-dir asset-config)]
    (-> image
        imagine/image-spec
        (imagine/inflate-spec asset-config)
        (imagine/transform-image-to-file (str dir image)))))

(defn- load-export-dir [export-directory]
  (stasis/slurp-directory export-directory #"\.[^.]+$"))

(defn export [{:keys [config
                      create-ingest-tx
                      on-ingested
                      render-page
                      page-post-process-fns] :as fns} & [{:keys [format]}]]
  (let [export-directory (or (:stasis/build-dir config) "build")
        assets (optimize (web/get-assets config) {})
        old-files (load-export-dir export-directory)
        request {:optimus-assets assets
                 :config config}
        conn (db/create-database (str "datomic:mem://" (d/squuid)) (:datomic/schema config))]
    (ingest/ingest-all {:config config
                        :conn conn
                        :create-ingest-tx create-ingest-tx
                        :on-ingested on-ingested})
    (stasis/empty-directory! export-directory)
    (export/save-assets assets export-directory)
    (stasis/export-pages (web/get-pages (d/db conn) request fns) export-directory request)
    (println "Exporting images from <img> <source> <meta property=\"og:image\"> and select style attributes")
    (when-let [imagine-config (some-> (:imagine/config config)
                                      (assoc :cacheable-urls? true))]
      (export-images export-directory export-directory imagine-config))
    (if (= format :json)
      (println (json/write-str (dissoc (stasis/diff-maps old-files old-files) :unchanged)))
      (do
        (println)
        (println "Export complete:")
        (stasis/report-differences old-files old-files)
        (println)))))
