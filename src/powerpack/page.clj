(ns powerpack.page
  (:require [clojure.string :as str]
            [html5-walker.walker :as walker]
            [imagine.core :as imagine]
            [powerpack.assets :as assets])
  (:import (ch.digitalfondue.jfiveparse Parser)))

(defn extract-page-data [ctx uri html]
  (let [doc (.parse (Parser.) html)]
    {:uri (str/replace uri #"index\.html$" "")
     :assets (assets/extract-document-asset-urls ctx doc)
     :links (->> ["a[href]"]
                 walker/create-matcher
                 (.getAllNodesMatching doc)
                 (map (fn [node]
                        {:href (.getAttribute node "href")
                         :text (.getTextContent node)}))
                 set)}))

(defn remove-non-substantive-url-segments [url]
  (-> url
      (str/split #"\?") first
      (str/split #"#") first))

(defn find-broken-links [powerpack {:keys [urls assets]} {:keys [links]}]
  (->> links
       (map #(update % :href remove-non-substantive-url-segments))
       (filter #(re-find #"^/[^\/]" (:href %)))
       (remove (comp (set urls) :href))
       (remove (comp (set (map :path (remove :outdated assets))) :href))
       (remove #(imagine/image-url? (:href %) (:imagine/config powerpack)))
       seq))

(defn format-broken-links [{:keys [uri links]}]
  (str "On URL " uri ":\n"
       (->> (for [{:keys [href text]} links]
              (str "<a href=\"" href "\">" text "</a>"))
            (str/join "\n"))))

(defn format-broken-links [links]
  (->> links
       (group-by :uri)
       (map (fn [[uri links]]
              (str "Page: " uri "\n"
                   (->> (for [{:keys [href text]} (map :link links)]
                          (str "<a href=\"" href "\">" text "</a>"))
                        (str/join "\n")))))
       (str/join "\n\n")
       (str "Found broken links\n\n")))
