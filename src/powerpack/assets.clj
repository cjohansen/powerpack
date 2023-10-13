(ns powerpack.assets
  (:require [optimus.assets :as assets]
            [optimus.optimizations :as optimizations]))

(defn get-assets [config]
  (concat
   (mapcat (fn [{:keys [public-dir paths]}]
             (assets/load-assets (or public-dir "public") paths))
           (:optimus/assets config))
   (mapcat (fn [[bundle {:keys [public-dir paths]}]]
             (assets/load-bundle (or public-dir "public") bundle paths))
           (:optimus/bundles config))))

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

(comment

  (-> {:optimus/bundles
       {"styles.css" {:public-dir "public"
                      :paths ["/styles/powerpack.css"
                              "/styles/test.css"]}}}
      get-assets
      (optimus.optimizations/all {}))

)
