(ns powerpack.app
  (:require [clojure.java.io :as io]
            [clojure.spec.alpha :as s]
            [powerpack.db :as db]
            [powerpack.i18n :as i18n]
            [powerpack.ingest :as ingest]
            [powerpack.logger :as log]))

(defn file-exists? [path]
  (.exists (io/file path)))

(defn directory? [path]
  (.isDirectory (io/file path)))

(defn file? [path]
  (.isFile (io/file path)))

(s/def :file/directory (s/and file-exists? directory?))
(s/def :file/file (s/and file-exists? file?))

(s/def :datomic/uri string?)
(s/def :datomic/schema-file string?)

(s/def :imagine/prefix string?)
(s/def :imagine/resource-path string?)
(s/def :imagine/disk-cache? boolean?)
(s/def :imagine.transform/transformation (s/tuple keyword (s/map-of keyword? any?)))
(s/def :imagine.transform/transformations (s/coll-of :imagine.transform/transformation))
(s/def :imagine.transform/retina-optimized? boolean?)
(s/def :imagine.transform/retina-quality (s/and float? #(<= 0 % 1)))
(s/def :imagine.transform/width number?)
(s/def :imagine/transform (s/keys :req-un [:imagine.transform/transformations]
                                  :opt-un [:imagine.transform/retina-optimized?
                                           :imagine.transform/retina-quality
                                           :imagine.transform/width]))
(s/def :imagine/transformations (s/map-of keyword? :imagine/transform))
(s/def :imagine/config (s/keys :req-un [:imagine/prefix
                                        :imagine/transformations]
                               :opt-un [:imagine/resource-path
                                        :imagine/disk-cache?]))

(s/def :m1p/dictionaries (s/map-of keyword?
                                   (s/coll-of (s/or :file :file/file
                                                    :directory :file/directory))))
(s/def :m1p/dictionary-fns (s/coll-of ifn?))

(s/def :optimus/public-dir string?)
(s/def :optimus/paths (s/coll-of any?))
(s/def :optimus/asset-config (s/keys :req-un [:optimus/paths]
                                     :opt-un [:optimus/public-dir]))
(s/def :optimus/assets (s/coll-of :optimus/asset-config))
(s/def :optimus/bundles (s/map-of string? :optimus/asset-config))

(s/def :site/default-locale keyword?)
(s/def :site/title string?)

(s/def :powerpack/build-dir string?)
(s/def :powerpack/content-dir string?)
(s/def :powerpack/content-file-suffixes (s/coll-of string?))
(s/def :powerpack/create-ingest-tx ifn?)
(s/def :powerpack/dev-assets-root-path string?)
(s/def :powerpack/get-context ifn?)
(s/def :powerpack/live-reload-route string?)
(s/def :powerpack/log-level #{:info :debug})
(s/def :powerpack/on-started ifn?)
(s/def :powerpack/on-stopped ifn?)
(s/def :powerpack/page-post-process-fns (s/coll-of ifn?))
(s/def :powerpack/port number?)
(s/def :powerpack/render-page ifn?)
(s/def :powerpack/resource-dirs (s/coll-of :file/directory))
(s/def :powerpack/source-dirs (s/coll-of :file/directory))
(s/def :asset-target/selector (s/coll-of (s/or :string string? :symbol symbol? :keyword keyword?)))
(s/def :asset-target/attr string?)
(s/def :asset-target/optional? boolean?)
(s/def :asset-target/qualified? boolean?)
(s/def :powerpack/asset-target (s/keys :req-un [:asset-target/selector
                                                :asset-target/attr]
                                       :opt-un [:asset-target/optional?
                                                :asset-target/qualified?]))
(s/def :powerpack/asset-targets (s/coll-of :powerpack/asset-target))

(s/def :powerpack/powerpack
  (s/keys :req [:datomic/schema-file
                :optimus/assets
                :optimus/bundles
                :site/default-locale
                :site/title
                :powerpack/content-dir
                :powerpack/dev-assets-root-path
                :powerpack/build-dir
                :powerpack/render-page]
          :opt [:datomic/uri
                :imagine/config
                :m1p/dictionaries
                :m1p/dictionary-fns
                :powerpack/asset-targets
                :powerpack/content-file-suffixes
                :powerpack/create-ingest-tx
                :powerpack/get-context
                :powerpack/live-reload-route
                :powerpack/log-level
                :powerpack/on-started
                :powerpack/on-stopped
                :powerpack/page-post-process-fns
                :powerpack/port
                :powerpack/resource-dirs
                :powerpack/source-dirs]))

(def default-asset-targets
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

(def defaults
  {:datomic/schema-file "resources/schema.edn"
   :datomic/uri "datomic:mem://powerpack"
   :powerpack/asset-targets default-asset-targets
   :powerpack/build-dir "target/powerpack"
   :powerpack/content-file-suffixes ["md" "edn"]
   :powerpack/live-reload-route "/powerpack/live-reload"
   :powerpack/port 5050
   :powerpack/resource-dirs ["resources"]
   :powerpack/source-dirs ["src"]})

(defn with-defaults [x defaults]
  (merge x (into {} (for [k (keys defaults)]
                      [k (or (k x) (k defaults))]))))

(defn create-database [config]
  (log/with-timing :info "Created database"
    (->> (read-string (slurp (io/file (:datomic/schema-file config))))
         (db/create-database (:datomic/uri config)))))

(defn create-app [powerpack & [opt]]
  (assert (s/valid? :powerpack/powerpack powerpack)
          (s/explain-data :powerpack/powerpack powerpack))
  (let [powerpack (with-defaults powerpack defaults)]
    (cond-> powerpack
      (:m1p/dictionaries powerpack)
      (assoc :i18n/dictionaries (atom (i18n/load-dictionaries powerpack opt)))

      (:datomic/uri powerpack)
      (assoc :datomic/conn (create-database powerpack)))))

(defn start [app & [opt]]
  (log/with-timing :info "Ingested all data"
    (ingest/ingest-all app opt))
  (when-let [on-started (:powerpack/on-started app)]
    (log/with-timing :info "Ran on-started hook"
      (on-started app))))

(defn stop [app]
  (when-let [on-stopped (:powerpack/on-stopped app)]
    (log/with-timing :info "Ran on-stopped hook"
      (on-stopped app))))
