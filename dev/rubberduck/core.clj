(ns rubberduck.core
  (:require [datomic-type-extensions.api :as d]
            [integrant.core :as ig]
            [powerpack.dev :as dev]
            [powerpack.export :as export]
            [powerpack.highlight :as highlight]))

(defn create-tx [_file-name data]
  data)

(defn render-page [context page]
  (if (= ::build-date (:page/kind page))
    {:status 200
     :content-type (:page/response-type page)
     :body {:build-date (:date context)}}
    [:html
     [:body
      [:h1 (:page/title page)]
      [:p [:i18n ::greeting]]
      ;;(throw (ex-info "Oh noes!" {}))
      [:p [:i18n ::uri page]]
      (when-let [published (:blog-post/published page)]
        [:p [:i18n ::published {:published published}]])
      [:pre [:code {:class "language-clj"}
             "(prn 'Hello :there)"]]
      [:a {:href "/blog/sample/"} "Broken link"]
      [:a {:href "https://elsewhere.com/blog/samp/"} "External link"]
      [:img {:src "/vcard-small/images/ducks.jpg"}]
      [:script {:src "/dev-debug.js"}]]]))

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
                          :paths ["/styles/powerpack.css"]}}

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

       :m1p/dictionaries
       {:nb ["dev/i18n/nb.edn"]
        :en ["dev/i18n/en.edn"]}

       :powerpack/create-ingest-tx #'create-tx
       :powerpack/render-page #'render-page
       :powerpack/get-context (fn [] {:date (str (java.time.LocalDate/now))})
       :powerpack/on-started
       (fn [powerpack-app]
         (->> [{:page/uri "/build-date.edn"
                :page/response-type :edn
                :page/kind ::build-date}
               {:page/uri "/build-date.json"
                :page/response-type :json
                :page/kind ::build-date}]
              (d/transact (:datomic/conn powerpack-app))
              deref))}
      highlight/install))

(defmethod ig/init-key :powerpack/powerpack [_ _]
  powerpack)

(comment

  (set! *print-namespace-maps* false)

  (dev/start)
  (dev/stop)
  (dev/reset)

  (require 'integrant.repl.state)

  integrant.repl.state/system

  (-> powerpack
      (assoc-in [:config :site/base-url] "https://rubberduck.example")
      export/export)

)
