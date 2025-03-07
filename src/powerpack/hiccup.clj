(ns powerpack.hiccup
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [dev.onionpancakes.chassis.core :as chassis]
            [fivetonine.collage.util :as util]
            [imagine.core :as imagine]
            [m1p.core :as m1p]
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

(defn get-open-graph-image [page]
  (when-let [image (:open-graph/image page)]
    (cond-> {:image image}
      (:open-graph/image-width page) (assoc :width (:open-graph/image-width page))
      (:open-graph/image-height page) (assoc :height (:open-graph/image-height page)))))

(defn get-open-graph-metas [page config]
  (->> (concat
        [(when-let [description (:open-graph/description page)]
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
        (when-let [{:keys [image width height]} (get-open-graph-image page)]
          (cond-> [{:property "og:image"
                    :content image}]
            width (conj {:property "og:image:width" :content (str width)})
            height (conj {:property "og:image:height" :content (str height)}))))
       (remove nil?)
       (map render-meta)))

(defn get-favicon-links [req]
  (concat
   (when-let [favicon (link/file-path req "/favicon.ico")]
     (list [:link {:href favicon :rel "icon" :type "image/x-icon"}]
           [:link {:href favicon :rel "shortcut icon" :type "image/ico"}]
           [:link {:href favicon :rel "shortcut icon" :type "image/x-icon"}]
           [:link {:href favicon :rel "shortcut icon" :type "image/vnd.microsoft.icon"}]))
   (when-let [favicon (link/file-path req "/favicon-16x16.png")]
     (list [:link {:href favicon :rel "icon" :type "image/png" :sizes "16x16"}]))
   (when-let [favicon (link/file-path req "/favicon-32x32.png")]
     (list [:link {:href favicon :rel "icon" :type "image/png" :sizes "32x32"}]))
   (when-let [favicon (link/file-path req "/apple-touch-icon.png")]
     (list [:link {:href favicon :rel "icon" :type "image/png" :sizes "180x180"}]))))

(defn get-bundles [req kind]
  (->> req :optimus-assets (keep :bundle) set
       (filter #(re-find (re-pattern (str "\\." kind "$")) %))
       seq))

(defn link-to-css-bundles [req bundles]
  (for [bundle-path (link/bundle-paths req bundles)]
    (let [original-path (when (:powerpack/live-reload? req)
                          (->> (:optimus-assets req)
                               (filter (comp #{bundle-path} :path))
                               first
                               :original-path))]
      [:link (cond-> {:rel "stylesheet"
                      :href bundle-path}
               (seq original-path)
               (assoc :path original-path))])))

(defn link-to-js-bundles [req bundles]
  (for [bundle-path (link/bundle-paths req bundles)]
    [:script {:type "text/javascript" :src bundle-path}]))

(defn format-title [context page]
  (when-let [title (not-empty (head-title (:powerpack/app context) (:page/title page)))]
    [:title title]))

(defn set-attribute [hiccup k v]
  (if (map? (second hiccup))
    (assoc-in hiccup [1 k] v)
    (into [(first hiccup) {k v}] (rest hiccup))))

(defn set-lang [context page hiccup]
  (let [locale (or (:page/locale page)
                   (-> context :powerpack/app :site/default-locale))]
    (cond-> hiccup
      (and locale (not (contains? (second hiccup) :lang)))
      (set-attribute :lang (name locale)))))

(defn set-og-prefix [hiccup]
  (cond-> hiccup
    (not (contains? (second hiccup) :prefix))
    (set-attribute :prefix "og: http://ogp.me/ns#")))

(defn ensure-head [hiccup]
  (let [n (if (map? (second hiccup)) 2 1)]
    (if (= :head (get-in hiccup [n 0]))
      hiccup
      (into (conj (vec (take n hiccup)) [:head]) (drop n hiccup)))))

(defn add-to-head [hiccup & elements]
  (update hiccup 2 into (remove empty? elements)))

(defn add-to-body [hiccup & elements]
  (if (= :body (first (last hiccup)))
    (update hiccup (dec (count hiccup)) into elements)
    (into hiccup elements)))

(defn ensure-title [context page hiccup]
  (if (->> (tree-seq coll? identity hiccup)
           (filter vector?)
           (filter (comp #{:title} first))
           empty?)
    (add-to-head hiccup (format-title context page))
    hiccup))

(defn get-tags [hiccup tag]
  (->> (tree-seq coll? identity hiccup)
       (filter vector?)
       (filter (comp #{tag} first))))

(defn ensure-utf8 [hiccup]
  (cond-> hiccup
    (->> (get-tags hiccup :meta)
         (filter (comp :charset second))
         empty?)
    (add-to-head [:meta {:charset "utf-8"}])))

(defn ensure-viewport [hiccup]
  (cond-> hiccup
    (->> (get-tags hiccup :meta)
         (filter (comp #{"viewport"} :name second))
         empty?)
    (add-to-head [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}])))

(defn ensure-open-graphs-metas [context page hiccup]
  (let [og-property (comp :property second)
        existing (set (map og-property (get-tags hiccup :meta)))]
    (->> (get-open-graph-metas page (:powerpack/app context))
         (remove (comp existing og-property))
         (apply add-to-head hiccup))))

(defn ensure-favicon-links [context hiccup]
  (if (->> (get-tags hiccup :link)
           (filter (comp #{"icon"} :rel second))
           empty?)
    (apply add-to-head hiccup (get-favicon-links context))
    hiccup))

(defn ensure-css-bundles [context hiccup]
  (->> (get-bundles context "css")
       (link-to-css-bundles context)
       (apply add-to-head hiccup)))

(defn ensure-js-bundles [context hiccup]
  (->> (get-bundles context "js")
       (link-to-js-bundles context)
       (apply add-to-body hiccup)))

(defn ensure-bundles [context hiccup]
  (->> (ensure-css-bundles context hiccup)
       (ensure-js-bundles context)))

(defn translate [hiccup context locale]
  (m1p/interpolate
   (walk/prewalk
    (fn [x]
      (cond->> x
        (:db/id x) (into {})))
    hiccup)
   {:locale locale
    :on-missing-dictionary-key
    (fn [_opt _params k]
      [:pre (cond
              (nil? locale)
              (str "Can't look up i18n key " k " without a locale")

              (not (contains? (:i18n/dictionaries context) locale))
              (str "Can't look up i18n key " k ", no " locale " dictionary")

              :else
              (str "Unknown i18n key " k " for locale " locale))])
    :dictionaries
    {(:m1p/k (:powerpack/app context))
     (get (:i18n/dictionaries context) locale)}}))

(defn interpolate-i18n [context page hiccup]
  (let [locale (or (:page/locale page)
                   (-> context :powerpack/app :site/default-locale))]
    (cond-> hiccup
      (:i18n/dictionaries context)
      (translate context locale))))

(defn embellish-document [context page hiccup]
  (->> hiccup
       (set-lang context page)
       set-og-prefix
       ensure-head
       (ensure-title context page)
       ensure-utf8
       ensure-viewport
       (ensure-open-graphs-metas context page)
       (ensure-favicon-links context)
       (ensure-bundles context)
       (interpolate-i18n context page)))

(defn build-doc [context page & body]
  (embellish-document context page (into [:html] body)))

(defn get-tag-name [hiccup]
  (some->> (first hiccup) name (re-find #"^[a-z0-9]+")))

(defn ^:export unescape
  "Do not HTML escape the contents of this string when rendering it as part of a
  hiccup document."
  [s]
  (chassis/raw s))

(defn ^:export render-html [hiccup]
  (str (when (= "html" (get-tag-name hiccup))
         "<!DOCTYPE html>")
       (chassis/html hiccup)))

(defn hiccup? [data]
  (or (and (vector? data)
           (keyword? (first data))
           (not (keyword? (second data))))
      (and (coll? data)
           (not (map? data))
           (every? hiccup? data))))
