(ns powerpack.export
  (:require [clojure.core.memoize :as memoize]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datomic-type-extensions.api :as d]
            [html5-walker.walker :as walker]
            [imagine.core :as imagine]
            [optimus.export :as export]
            [optimus.optimizations :as optimizations]
            [powerpack.app :as app]
            [powerpack.assets :as assets]
            [powerpack.logger :as log]
            [powerpack.web :as web]
            [stasis.core :as stasis])
  (:import (ch.digitalfondue.jfiveparse Parser)))

(def optimize
  (-> (fn [assets options]
        (-> assets
            (optimizations/all options)
            (->> (remove :bundled)
                 (remove :outdated))))
      (memoize/lru {} :lru/threshold 3)))

(defn extract-html-data [ctx path html]
  (let [doc (.parse (Parser.) html)]
    {:path path
     :assets (assets/extract-document-asset-urls ctx doc)
     :links (->> ["a[href]"]
                 walker/create-matcher
                 (.getAllNodesMatching doc)
                 (map (fn [node]
                        {:url (.getAttribute node "href")
                         :text (.getTextContent node)}))
                 set)}))

(defn extract-data
  "Loops over the generated pages, extracting link targets and images from them"
  [powerpack]
  (let [dir (io/as-file (:powerpack/build-dir powerpack))
        path-len (count (.getPath dir))
        path-from-dir #(subs (.getPath %) path-len)]
    (->> (file-seq dir)
         (filter #(re-find #"\.html$" (path-from-dir %)))
         (map #(extract-html-data {:powerpack/app powerpack} (path-from-dir %) (slurp %))))))

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

(defn format-asset-targets [indent]
  (->> (for [{:keys [selector attr]} assets/asset-targets]
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
        pages (web/get-pages (d/db (:datomic/conn powerpack)) ctx powerpack)
        export-data (extract-data powerpack)]
    (stasis/export-pages pages (:powerpack/build-dir powerpack) ctx)
    (export/save-assets assets (:powerpack/build-dir powerpack))
    (when (:imagine/config powerpack)
      (log/info (str "Exporting images from:\n" (format-asset-targets "  ")))
      (-> (update powerpack :imagine/config assoc :cacheable-urls? true)
          (export-images export-data)))
    {:pages pages
     :export-data export-data}))

(defn find-broken-links [pages export-data]
  (->> export-data
       (mapcat
        (fn [{:keys [path links]}]
          (for [link (->> links
                          (filter #(re-find #"^/[^\/]" (:url %)))
                          (remove (comp pages :url)))]
            {:url (str/replace path #"index\.html$" "")
             :link link})))))

(defn validate-export [pages export-data]
  (let [broken-links (seq (find-broken-links pages export-data))]
    {:valid? (not broken-links)
     :problems (->> [(when broken-links
                       {:kind :broken-links
                        :data broken-links})]
                    (remove nil?))}))

(defn format-problem [{:keys [kind data]}]
  (case kind
    :broken-links
    (->> data
         (group-by :url)
         (map (fn [[url links]]
                (str "Page: " url "\n"
                     (->> (for [{:keys [url text]} (map :link links)]
                            (str "<a href=\"" url "\">" text "</a>"))
                          (str/join "\n")))))
         (str/join "\n\n")
         (str "Found broken links\n\n"))))

(defn print-report [powerpack {:keys [old-files validation format]}]
  (let [new-files (load-export-dir (:powerpack/build-dir powerpack))]
    (if (= format :json)
      (log/info (-> (stasis/diff-maps old-files new-files)
                    (dissoc :unchanged)
                    (merge validation)
                    json/write-str))
      (do
        (when-not (:valid? validation)
          (log/info "Detected problems in exported site, deployment is not advised")
          (prn validation)
          (log/info (->> (:problems validation)
                        (map format-problem)
                        (str/join "\n\n")))
          (throw (Exception. "Export failed")))
        (log/info "Export complete:")
        (stasis/report-differences old-files new-files)))))

(defn export [options & [opt]]
  (let [powerpack (app/create-app options)
        old-files (load-export-dir (:powerpack/build-dir powerpack))]
    (app/start powerpack)
    (stasis/empty-directory! (:powerpack/build-dir powerpack))
    (let [{:keys [pages export-data]} (export-site powerpack)]
      (->> (merge opt {:old-files old-files
                       :validation (validate-export pages export-data)})
           (print-report powerpack)))))
