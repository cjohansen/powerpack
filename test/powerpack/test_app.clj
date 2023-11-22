(ns powerpack.test-app
  (:require [datomic-type-extensions.api :as d])
  (:import (java.awt Color)
           (java.awt.image BufferedImage)
           (java.io ByteArrayOutputStream)
           (java.text NumberFormat)
           (java.util Locale)
           (javax.imageio ImageIO)))

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

(defn generate-image []
  (let [width 100
        height 100
        image (BufferedImage. width height BufferedImage/TYPE_INT_ARGB)
        graphics (.getGraphics image)]
    (.setColor graphics (Color. 255 0 0))
    (.fillRect graphics 0 0 width height)
    (.dispose graphics)
    (with-open [baos (ByteArrayOutputStream.)]
      (ImageIO/write image "png" baos)
      (.toByteArray baos))))

(defn render-png []
  {:status 200
   :headers {"Content-Type" "image/png"}
   :body (generate-image)})

(defn render-page [context page]
  (cond
    (= ::build-date (:page/kind page))
    {:status 200
     :content-type (:page/response-type page)
     :body {:build-date (:date context)}}

    (= ::redirect (:page/kind page))
    {:status 302
     :headers {"Location" (:page/redirect-url page)}}

    (= ::png (:page/kind page))
    (render-png)

    :else
    [:html
     [:body
      [:h1 (:page/title page)]
      [:p [:i18n ::greeting]]
      [:p [:i18n ::num {:n 45.12}]]
      [:p [:i18n ::uri page]]
      (when-let [published (:blog-post/published page)]
        [:p [:i18n ::published {:published published}]])
      [:pre [:code {:class "language-clj"}
             "(prn 'Hello :there 'tihi)"]]
      [:a {:href "/blog/sample/"} "Broken link"]
      [:a {:href "https://elsewhere.com/blog/samp/"} "External link"]
      [:img {:src "/vcard-small/images/ducks.jpg"}]]]))

(def app
  {:site/default-locale :en
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

   :datomic/uri "datomic:mem://rubberduck-export"
   :datomic/schema-file "dev-resources/schema.edn"

   :m1p/dictionary-fns {:fn/format-number #'m1p-fn-num}

   :m1p/dictionaries
   {:nb ["dev/i18n/nb.edn"]
    :en ["dev/i18n/en.edn"]}

   :site/base-url "https://rubberduck.example"

   :powerpack/render-page #'render-page
   :powerpack/get-context (fn [] {:date (str (java.time.LocalDate/now))})
   :powerpack/on-started
   (fn [powerpack-app]
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
          deref))})
