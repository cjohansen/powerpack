(ns rubberduck.page
  (:import (java.awt Color)
           (java.awt.image BufferedImage)
           (java.io ByteArrayOutputStream)
           (javax.imageio ImageIO)))

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
    (do
      (Thread/sleep 3000)
      {:status 200
       :content-type (:page/response-type page)
       :body {:build-date (:date context)}})

    (= ::redirect (:page/kind page))
    {:status 302
     :headers {"Location" (:page/redirect-url page)}}

    (= ::png (:page/kind page))
    (render-png)

    :else
    [:html
     [:body.bg-stone-100.text-zinc-900
      [:h1 (:page/title page)]
      [:p [:i18n :rubberduck.core/greeting]]
      [:p [:i18n :rubberduck.core/num {:n 45.12}]]
      ;;(throw (ex-info "Oh noes!" {}))
      [:p [:i18n :rubberduck.core/uri page]]
      (when-let [published (:blog-post/published page)]
        [:p [:i18n ::published {:published published}]])
      [:pre [:code {:class "language-clj"}
             "(prn 'Hello :there 'tihi)"]]
      [:a {:href "/blog/sample/"} "Broken link"]
      [:a {:href "https://elsewhere.com/blog/samp/"} "External link"]
      [:img {:src "/vcard-small/images/ducks.jpg"}]
      #_[:script {:src "/dev-debug.js"}]]]))
