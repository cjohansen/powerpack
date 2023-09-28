(ns rubberduck.core
  (:require [powerpack.app :as app]
            [powerpack.export :as export]
            [powerpack.html :as html]
            [powerpack.highlight :as highlight]
            [datomic-type-extensions.api :as d]))

(def config
  {:site/base-url "https://rubberduck.example"
   :site/default-language "en"
   :site/title "Rubberduck"

   :stasis/build-dir "build"
   :powerpack/content-dir "dev-resources"
   :powerpack/db "datomic:mem://rubberduck"
   :powerpack/source-dirs ["src" "dev"]

   :optimus/assets [{:public-dir "public"
                     :paths [#"/images/*.*"]}]
   :optimus/bundles {"styles.css"
                     {:public-dir "public"
                      :paths ["/styles/powerpack.css"]}}

   :ring/port 5051

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

   :datomic/schema [{:db/ident :blog-post/title
                     :db/valueType :db.type/string
                     :db/cardinality :db.cardinality/one}

                    {:db/ident :blog-post/description
                     :db/valueType :db.type/string
                     :db/cardinality :db.cardinality/one}

                    {:db/ident :blog-post/body
                     :db/valueType :db.type/string
                     :db/cardinality :db.cardinality/one}]})

(defn load-image [config url]
  (some-> (imagine.core/image-spec url)
          (imagine.core/inflate-spec config)
          :resource
          fivetonine.collage.util/load-image))

(defn get-image-dimensions [config url]
  (when-let [image (load-image config url)]
    {:width (.getWidth image)
     :height (.getHeight image)}))

(defn create-tx [db file-name data]
  [data])

(defn render-page [req page]
  (html/render-hiccup
   req
   page
   [:div
    [:h1 (:page/title page)]
    [:p "Hi!"]
    [:pre [:code {:class "language-clj"}
           "(prn 'Hello :there)"]]
    [:img {:src "/vcard-small/images/ducks.jpg"}]]))

(comment

  (def app (-> {:config config
                :create-ingest-tx #'create-tx
                :render-page #'render-page}
               app/create-app
               highlight/install))

  (app/start app)
  (app/reset)
  (export/export app)

  (def system integrant.repl.state/system)

  (->> [:page/uri "/blog/sample/"]
       (d/entity (d/db (:datomic/conn integrant.repl.state/system)))
       (into {}))

)
