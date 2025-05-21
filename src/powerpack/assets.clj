(ns powerpack.assets
  (:require [clojure.string :as str]
            [html5-walker.walker :as walker]
            [imagine.core :as imagine]
            [optimus.assets :as assets]
            [optimus.link :as link]
            [optimus.optimizations :as optimizations]
            [powerpack.errors :as errors])
  (:import (ch.digitalfondue.jfiveparse Parser)
           (java.io FileNotFoundException)))

(defn validate-asset-path [public-dir path]
  (into
   {:path path}
   (try
     (assets/load-assets public-dir #{path})
     {:valid? true}
     (catch FileNotFoundException _e
       {:valid? false
        :error-message (str "Unable to find resource `" public-dir path "`, make sure the path is spelled correctly and that `" public-dir "` is on your classpath")})
     (catch Exception e
       {:valid? false
        :error-message (.getMessage e)}))))

(defn validate-asset-paths [{:keys [public-dir paths]}]
  (let [public-dir (or public-dir "public")
        results (loop [paths (seq paths)
                       results #{}]
                  (if (nil? paths)
                    results
                    (recur
                     (next paths)
                     (conj results (validate-asset-path public-dir (first paths))))))]
    {:valid? (empty? (remove :valid? results))
     :public-dir public-dir
     :paths results}))

(defn prune-and-validate-assets [k assets opt]
  (doall
   (for [asset-config assets]
     (let [results (-> (validate-asset-paths asset-config)
                       (select-keys [:public-dir :paths]))]
       (when-let [problems (seq (remove :valid? (:paths results)))]
         (->> {:id [::validate-assets k]
               :kind ::validate-assets
               :message (str "Unable to load all assets in " (str/join " " (map pr-str k)))
               :errors (for [{:keys [path error-message]} problems]
                         {:v path
                          :message error-message})}
              (errors/report-error opt)))
       (update results :paths #(->> % (filter :valid?) (map :path)))))))

(defn remove-invalid-assets [assets opt]
  (prune-and-validate-assets [:optimus/assets] assets opt))

(defn remove-invalid-bundles [bundles opts]
  (update-vals bundles #(first (remove-invalid-assets [%] opts))))

(defn load-assets [powerpack]
  (mapcat (fn [{:keys [public-dir paths]}]
            (assets/load-assets (or public-dir "public") paths))
          (:optimus/assets powerpack)))

(defn load-bundles [powerpack]
  (mapcat (fn [[bundle {:keys [public-dir paths]}]]
            (assets/load-bundle (or public-dir "public") bundle paths))
          (:optimus/bundles powerpack)))

(defn get-assets [powerpack]
  (concat (load-assets powerpack) (load-bundles powerpack)))

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

(defn get-optimized-asset [ctx opt node spec path]
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
                               (->> (cond-> {:path path
                                             :spec spec
                                             :powerpack/problem :powerpack/missing-asset}
                                      (:uri ctx) (assoc :uri (:uri ctx))
                                      node (assoc :html (.getOuterHTML node)))
                                    (ex-info message)
                                    throw)))))]
      (errors/resolve-error opt [::missing-asset path])
      asset)))

(defn strip-base-url [ctx url]
  (str/replace url (re-pattern (str "^" (-> ctx :powerpack/app :site/base-url))) ""))

(defn external-url? [powerpack url]
  (when (re-find #"^(https?:)?//" url)
    (not (some->> (:site/base-url powerpack)
                  (str/starts-with? url)))))

(defn optimize-asset-url [ctx opt node spec src]
  (if (external-url? (:powerpack/app ctx) src)
    src
    (let [[url hash] (str/split src #"#")]
      (if url
        (str (when (:qualified? spec)
               (-> ctx :powerpack/app :site/base-url))
             (get-optimized-asset ctx opt node spec (strip-base-url ctx url))
             (some->> hash (str "#")))
        src))))

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
  (when-let [v (.getAttribute node attr)]
    (.setAttribute node attr (f v))))

(defn replace-attr [node attr-before attr-after f]
  (let [v (or (.getAttribute node attr-before)
              (.getAttribute node attr-after))]
    (.setAttribute node attr-after (f v))
    (.removeAttribute node attr-before)))

(defmulti optimize-attr-urls (fn [_ctx _opt spec _node] (:attr spec)))

(defmethod optimize-attr-urls "srcset" [ctx opt spec node]
  (->> (partial optimize-asset-url ctx opt node spec)
       (partial update-srcset)
       (update-attr node (:attr spec))))

(defmethod optimize-attr-urls "xlink:href" [ctx opt spec node]
  (->> (partial optimize-asset-url ctx opt node spec)
       (replace-attr node "href" (:attr spec))))

(defmethod optimize-attr-urls "style" [ctx opt spec node]
  (->> (partial optimize-asset-url ctx opt node spec)
       (partial update-style-urls)
       (update-attr node (:attr spec))))

(defmethod optimize-attr-urls :default [ctx opt spec node]
  (->> (partial optimize-asset-url ctx opt node spec)
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
