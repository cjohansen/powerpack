(ns rubberduck.core
  (:require [integrant.core :as ig]
            [powerpack.app :as app]
            [powerpack.export :as export]
            [powerpack.highlight :as highlight]
            [powerpack.html :as html]))

(def config
  {:site/default-locale :en
   :site/title "Rubberduck"

   :stasis/build-dir "build"
   :powerpack/content-dir "dev-resources"
   :powerpack/db "datomic:mem://rubberduck"
   :powerpack/source-dirs ["src" "dev"]
   :powerpack/resource-dirs ["dev-resources" "dev"]
   :powerpack/log-level :debug

   :optimus/assets [{:public-dir "public"
                     :paths [#"/images/*.*"]}]
   :optimus/bundles {"styles.css"
                     {:public-dir "public"
                      :paths ["/styles/powerpack.css"]}}

   :powerpack.server/port 5051

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

   :datomic/schema-file "dev-resources/schema.edn"})

(defn create-tx [_file-name data]
  data)

(defn render-page [context page]
  (if (= ::build-date (:page/kind page))
    (html/render-hiccup
     context
     page
     [:div
      [:h1 "Build date: " (str (:date page))]
      [:p "Your custom context, Sir: " (:custom context)]])
    (html/render-hiccup
     context
     page
     [:div
      [:h1 (:page/title page)]
      [:p "Hi!"]
      ;;(throw (ex-info "Oh noes!" {}))
      (when-let [published (:blog-post/published page)]
        [:p "Published " (str published)])
      [:pre [:code {:class "language-clj"}
             "(prn 'Hello :there)"]]
      [:img {:src "/vcard-small/images/ducks.jpg"}]])))

(def app
  (-> {:config config
       :create-ingest-tx #'create-tx
       :render-page #'render-page
       :context {:custom "Context"}}
      highlight/install))

(defmethod ig/init-key :powerpack/app [_ _]
  app)

(comment

  (set! *print-namespace-maps* false)

  (app/start)
  (app/stop)
  (app/reset)

  (-> app
      (assoc-in [:config :site/base-url] "https://rubberduck.example")
      export/export)

)
