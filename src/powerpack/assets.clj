(ns powerpack.assets
  (:require [clojure.string :as str]
            [html5-walker.walker :as walker]
            [imagine.core :as imagine]
            [optimus.assets :as assets]
            [optimus.link :as link]
            [optimus.optimizations :as optimizations]
            [powerpack.errors :as errors])
  (:import (ch.digitalfondue.jfiveparse Parser)))

(defn get-assets [powerpack]
  (concat
   (mapcat (fn [{:keys [public-dir paths]}]
             (assets/load-assets (or public-dir "public") paths))
           (:optimus/assets powerpack))
   (mapcat (fn [[bundle {:keys [public-dir paths]}]]
             (assets/load-bundle (or public-dir "public") bundle paths))
           (:optimus/bundles powerpack))))

(defn optimizations [assets & [_options]]
  (-> assets
      optimizations/add-cache-busted-expires-headers))

(defn find-asset-path [assets path]
  (->> assets
       optimizations
       (remove :outdated)
       (filter #(= path (or (:original-path %) (:path %))))
       first
       :path))

(defn get-optimized-asset [ctx opt spec path]
  (let [imagine (-> ctx :powerpack/app :imagine/config)]
    (when-let [asset (or (not-empty (link/file-path ctx path))
                         (imagine/realize-url imagine path)
                         (when (imagine/image-url? path imagine)
                           path)
                         (if (:optional? spec)
                           path
                           (let [message (str "Asset " path " is not loaded, check optimus and imagine configs")]
                             (when-not (->> {:message message
                                             :kind ::missing-asset
                                             :id [::missing-asset path]}
                                            (errors/report-error opt))
                               (throw (ex-info message {:path path}))))))]
      (errors/resolve-error opt [::missing-asset path])
      asset)))

(defn strip-base-url [ctx url]
  (str/replace url (re-pattern (str "^" (-> ctx :powerpack/app :site/base-url))) ""))

(defn optimize-asset-url [ctx opt spec src]
  (let [[url hash] (str/split src #"#")]
    (str (when (:qualified? spec)
           (-> ctx :powerpack/app :site/base-url))
         (get-optimized-asset ctx opt spec (strip-base-url ctx url))
         (some->> hash (str "#")))))

(def style-url-re #"url\([\"']?(.+?)[\"']?\)")

(defn update-style-urls [f style]
  (when style
    (str/replace style style-url-re
                 (fn [[_ url]]
                   (str "url(" (f url) ")")))))

(defn update-srcset [f srcset]
  (when srcset
    (->> (str/split srcset #",")
         (map str/trim)
         (map (fn [candidate]
                (let [[url descriptor] (str/split candidate #" ")]
                  (->> [(f url) descriptor]
                       (remove empty?)
                       (str/join " ")))))
         (str/join ", "))))

(defn update-attr [node attr f]
  (.setAttribute node attr (f (.getAttribute node attr))))

(defn replace-attr [node attr-before attr-after f]
  (let [v (or (.getAttribute node attr-before)
              (.getAttribute node attr-after))]
    (.setAttribute node attr-after (f v))
    (.removeAttribute node attr-before)))

(defmulti optimize-attr-urls (fn [_ctx _opt spec _node] (:attr spec)))

(defmethod optimize-attr-urls "srcset" [ctx opt spec node]
  (->> (partial optimize-asset-url ctx opt spec)
       (partial update-srcset)
       (update-attr node (:attr spec))))

(defmethod optimize-attr-urls "xlink:href" [ctx opt spec node]
  (->> (partial optimize-asset-url ctx opt spec)
       (replace-attr node "href" (:attr spec))))

(defmethod optimize-attr-urls "style" [ctx opt spec node]
  (->> (partial optimize-asset-url ctx opt spec)
       (partial update-style-urls)
       (update-attr node (:attr spec))))

(defmethod optimize-attr-urls :default [ctx opt spec node]
  (->> (partial optimize-asset-url ctx opt spec)
       (update-attr node (:attr spec))))

(defn get-markup-url-optimizers [ctx & [opt]]
  (for [{:keys [selector] :as spec} (-> ctx :powerpack/app :powerpack/asset-targets)]
    [selector #(optimize-attr-urls ctx opt spec %)]))

(defn asset? [ctx path]
  (or (imagine/image-url? path (-> ctx :powerpack/app :imagine/config))
      (->> (:optimus-assets ctx)
           (remove :outdated)
           (some (comp #{path} :path)))))

(defmulti extract-asset-url (fn [_ctx spec _node] (:attr spec)))

(defmethod extract-asset-url :default [_ctx spec node]
  [(.getAttribute node (last (str/split (:attr spec) #":")))])

(defmethod extract-asset-url "srcset" [_ctx spec node]
  (let [attr-val (.getAttribute node (last (str/split (:attr spec) #":")))]
    (->> (str/split attr-val #",")
         (map str/trim)
         (map #(first (str/split % #" "))))))

(defmethod extract-asset-url "style" [_ctx spec node]
  (->> (.getAttribute node (last (str/split (:attr spec) #":")))
       (re-seq style-url-re)
       (map second)))

(defn extract-assets [ctx spec node]
  (->> (extract-asset-url ctx spec node)
       (remove nil?)
       (map #(cond->> %
               (:qualified? spec) (strip-base-url ctx)))
       (remove #(and (:optional? spec) (not (asset? ctx %))))))

(defn extract-document-asset-urls [ctx doc]
  (->> ctx :powerpack/app :powerpack/asset-targets
       (mapcat
        (fn [spec]
          (->> (:selector spec)
               walker/create-matcher
               (.getAllNodesMatching doc)
               (mapcat #(extract-assets ctx spec %)))))
       set))

(defn extract-asset-urls [ctx html]
  (extract-document-asset-urls ctx (.parse (Parser.) html)))
