(ns powerpack.html
  (:require [clojure.string :as str]
            [dumdom.string :as dumdom]
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
  (->> [title
        (:site/title config)]
       (str/join " | ")))

(defn get-open-graph-metas [page config]
  (->> [{:property "og:type"
         :content "article"}
        (when-let [description (:open-graph/description page)]
          {:property "og:description"
           :content (-> (escape-str description)
                        (truncate-str 200))})
        (when-let [title (or (:open-graph/title page)
                             (:page/title page))]
          {:property "og:title"
           :content (-> (escape-str title)
                        (truncate-str 70))})
        (when-let [image (:open-graph/image page)]
          {:property "og:url"
           :content (str (:site/base-url config) (:page/uri page))})
        (when-let [image (:open-graph/image page)]
          {:property "og:image"
           :content image})]
       (map render-meta (:metas page))))

(defn get-bundle-links [req]
  (->> req :config :optimus/bundles keys
       (link/bundle-paths req)
       (map (fn [url] [:link {:rel "stylesheet" :href url}]))))

(defn get-favicon-links [req]
  (when-let [favicon (link/file-path req "/favicon.ico")]
    (list [:link {:href favicon :rel "icon" :type "image/x-icon"}]
          [:link {:href favicon :rel "shortcut icon" :type "image/ico"}]
          [:link {:href favicon :rel "shortcut icon" :type "image/x-icon"}]
          [:link {:href favicon :rel "shortcut icon" :type "image/vnd.microsoft.icon"}])))

(defn get-head [req page]
  [:head
   [:meta {:charset "utf-8"}]
   [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
   (get-open-graph-metas page (:config req))
   (get-bundle-links req)
   (get-favicon-links req)
   (:page/head-elements page)
   [:title (head-title (:config req) (:page/title page))]])

(defn render-hiccup [req page body]
  (str "<!DOCTYPE html>"
   (dumdom/render
    [:html {:lang (or (:page/language page)
                      (-> req :config :site/default-language))
            :prefix "og: http://ogp.me/ns#"}
     (get-head req page)
     body])))
