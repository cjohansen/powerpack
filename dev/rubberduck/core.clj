(ns rubberduck.core
  (:require [datomic-type-extensions.api :as d]
            [powerpack.dev :as dev]
            [powerpack.export :as export]
            [powerpack.highlight :as highlight]
            [rubberduck.page :as page])
  (:import (java.text NumberFormat)
           (java.util Locale)))

(def locales
  {:nb (Locale/forLanguageTag "nb-NO")
   :en (Locale/forLanguageTag "en-GB")})

(defn format-number [locale n & [{:keys [decimals]}]]
  (let [formatter (NumberFormat/getNumberInstance (locales locale))]
    (when decimals
      (.setMaximumFractionDigits formatter decimals))
    (.format formatter n)))

(defn m1p-fn-num [{:keys [locale]} _params n & [opt]]
  (format-number locale n opt))

(defn create-tx [_file-name data]
  data)

(defn get-context []
  {:date (str (java.time.LocalDate/now))})

(defn on-started [powerpack-app]
  (->> [{:page/uri "/build-date.edn"
         :page/response-type :edn
         :page/kind ::build-date
         :page/etag "0acbd"}
        {:page/uri "/build-date.json"
         :page/response-type :json
         :page/kind ::build-date
         :page/etag "99f8d3"}
        {:page/uri "/test.png"
         :page/kind ::png}
        {:page/uri "/"
         :page/kind ::redirect
         :page/redirect-url "/blog/sample/"}]
       (d/transact (:datomic/conn powerpack-app))
       deref))

(def powerpack
  (-> {:site/default-locale :en
       :site/title "Rubberduck"

       :powerpack/content-dir "dev-resources"
       :powerpack/source-dirs ["src" "dev"]
       :powerpack/resource-dirs ["dev-resources" "dev"]
       :powerpack/log-level :debug
       :powerpack/dev-assets-root-path "dev-assets"

       :powerpack/build-dir "build"
       :optimus/assets [{:public-dir "public"
                         :paths [#"/images/*.*"]}]
       :optimus/bundles {"styles.css"
                         {:public-dir "public"
                          :paths ["/styles/powerpack.css"
                                  "/styles.css"]}

                         "ducks.js"
                         {:public-dir "public"
                          :paths ["/scripts/lib.js"
                                  "/scripts/app.js"]}}

       :powerpack/port 5051

       :imagine/config {:prefix "image-assets"
                        :resource-path "public"
                        :disk-cache? true
                        :transformations
                        {:vcard-small
                         {:transformations [[:fit {:width 184 :height 184}]
                                            [:crop {:preset :square}]]
                          :retina-optimized? true
                          :retina-quality 0.4
                          :width 184}}}

       :datomic/uri "datomic:mem://rubberduck"
       :datomic/schema-file "dev-resources/schema.edn"

       :m1p/dictionary-fns {:fn/format-number #'m1p-fn-num}

       :m1p/dictionaries
       {:nb ["dev/i18n/nb.edn"]
        :en ["dev/i18n/en.edn"]}

       :site/base-url "https://rubberduck.example"

       :powerpack/create-ingest-tx #'create-tx
       :powerpack/render-page #'page/render-page
       :powerpack/get-context #'get-context
       :powerpack/on-started #'on-started}
      highlight/install))

(defmethod dev/configure! :default []
  powerpack)

(defn export! [& args]
  (export/export! powerpack))

(comment

  (set! *print-namespace-maps* false)

  (dev/start)
  (dev/stop)
  (dev/reset)

  (require 'integrant.repl.state)

  (def app (:powerpack/app integrant.repl.state/system))

  (d/q '[:find ?e ?uri
         :where [?e :page/uri ?uri]]
       (d/db (:datomic/conn app)))

  (-> powerpack
      (assoc :powerpack/log-level :info)
      export/export)

)
