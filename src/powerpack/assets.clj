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

(def asset-targets
  [{:selector ["img[src]"]
    :attr "src"}
   {:selector ["img[srcset]"]
    :attr "srcset"}
   {:selector ["head" "meta[property=og:image]"]
    :attr "content"
    :qualified? true}
   {:selector ["[style]"]
    :attr "style"}
   {:selector ["source[src]"]
    :attr "src"}
   {:selector ["source[srcset]"]
    :attr "srcset"}
   {:selector '[svg use]
    :attr "xlink:href"}
   {:selector '[a]
    :attr "href"
    :optional? true}])

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

(defn optimize-attr-urls [ctx opt spec node]
  (let [optimize (partial optimize-asset-url ctx opt spec)]
    (case (:attr spec)
      "srcset"
      (update-attr node (:attr spec) (partial update-srcset optimize))

      "xlink:href"
      (replace-attr node "href" (:attr spec) optimize)

      "style"
      (update-attr node (:attr spec) (partial update-style-urls optimize))

      (update-attr node (:attr spec) optimize))))

(defn get-markup-url-optimizers [ctx & [opt]]
  (for [{:keys [selector] :as spec} asset-targets]
    [selector #(optimize-attr-urls ctx opt spec %)]))

(defn asset? [ctx path]
  (or (imagine/image-url? path (-> ctx :powerpack/app :imagine/config))
      (->> (:optimus-assets ctx)
           (remove :outdated)
           (some (comp #{path} :path)))))

(defn extract-assets [ctx spec node]
  (let [attr-val (.getAttribute node (last (str/split (:attr spec) #":")))]
    (->> (case (:attr spec)
           "srcset"
           (->> (str/split attr-val #",")
                (map str/trim)
                (map #(first (str/split % #" "))))

           "style"
           (map second (re-seq style-url-re attr-val))

           [attr-val])
         (remove nil?)
         (map #(cond->> %
                 (:qualified? spec) (strip-base-url ctx)))
         (remove #(and (:optional? spec) (not (asset? ctx %)))))))

(defn extract-document-asset-urls [ctx doc]
  (->> asset-targets
       (mapcat
        (fn [spec]
          (->> (:selector spec)
               walker/create-matcher
               (.getAllNodesMatching doc)
               (mapcat #(extract-assets ctx spec %)))))
       set))

(defn extract-asset-urls [ctx html]
  (extract-document-asset-urls ctx (.parse (Parser.) html)))
