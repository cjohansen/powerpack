(ns powerpack.html
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [dumdom.string :as dumdom]
            [fivetonine.collage.util :as util]
            [imagine.core :as imagine]
            [optimus.link :as link]))

(defn escape-str [s]
  (when s
    (str/escape
     (str/replace (str/trim s) #"[ \t\n\r]+" " ")
     {\< "&lt;"
      \> "&gt;"
      \& "&amp;"
      \" "\""})))

(defn truncate-str [s len]
  (when s
    (if (< len (count s))
      (str (subs s 0 (Math/max 0 (dec len)))
           (str "…"))
      s)))

(defn render-meta [meta]
  (when (:content meta)
    [:meta meta]))

(defn head-title [config title]
  (->> [title (:site/title config)]
       (remove empty?)
       (str/join " | ")))

(defn load-image [imagine-config image-url]
  (some-> (or (when-let [url (imagine/realize-url imagine-config image-url)]
                (imagine/get-image-from-url imagine-config url))
              (io/resource (str "public/" image-url)))
          util/load-image))

(defn get-open-graph-metas [page config]
  (->> (concat
        [{:property "og:type"
          :content "article"}
         (when-let [description (:open-graph/description page)]
           {:property "og:description"
            :content (-> description
                         (truncate-str 200)
                         escape-str)})
         (when-let [title (or (:open-graph/title page)
                              (:page/title page))]
           {:property "og:title"
            :content (-> title
                         (truncate-str 70)
                         escape-str)})
         (when-let [base-url (:site/base-url config)]
           {:property "og:url"
            :content (str base-url (:page/uri page))})]
        (when-let [image (:open-graph/image page)]
          (let [buffered-image (load-image (:imagine/config config) image)]
            [{:property "og:image"
              :content image}
             {:property "og:image:width"
              :content (str (.getWidth buffered-image))}
             {:property "og:image:height"
              :content (str (.getHeight buffered-image))}])))
       (remove nil?)
       (map render-meta)))

(defn get-favicon-links [req]
  (when-let [favicon (link/file-path req "/favicon.ico")]
    (list [:link {:href favicon :rel "icon" :type "image/x-icon"}]
          [:link {:href favicon :rel "shortcut icon" :type "image/ico"}]
          [:link {:href favicon :rel "shortcut icon" :type "image/x-icon"}]
          [:link {:href favicon :rel "shortcut icon" :type "image/vnd.microsoft.icon"}])))

(defn get-bundles [req kind]
  (->> req :optimus-assets (keep :bundle) set
       (filter #(re-find (re-pattern (str "\\." kind "$")) %))
       seq))

(defn link-to-css-bundles [req bundles]
  (for [path (link/bundle-paths req bundles)]
    [:link {:rel "stylesheet" :href path}]))

(defn link-to-js-bundles [req bundles]
  (for [path (link/bundle-paths req bundles)]
    [:script {:type "text/javascript" :src path}]))

(defn get-head [req page]
  (into [:head]
        (concat
         [[:meta {:charset "utf-8"}]
          [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]]
         (get-open-graph-metas page (:powerpack/config req))
         (->> (get-bundles req "css")
              (link-to-css-bundles req))
         (get-favicon-links req)
         (:page/head-elements page)
         [[:title (head-title (:powerpack/config req) (:page/title page))]])))

(defn build-doc [req page body]
  (into [:html (let [lang (or (:page/locale page)
                              (-> req :powerpack/config :site/default-locale))]
                 (cond-> {:prefix "og: http://ogp.me/ns#"}
                   lang (assoc :lang (name lang))))
         (get-head req page)
         body]
        (->> (get-bundles req "js")
             (link-to-js-bundles req))))

(defn render-hiccup [req page body]
  (str "<!DOCTYPE html>"
       (dumdom/render (build-doc req page body))))
