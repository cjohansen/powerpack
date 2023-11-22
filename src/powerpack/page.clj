(ns powerpack.page
  (:require [clojure.string :as str]
            [html5-walker.walker :as walker]
            [imagine.core :as imagine]
            [powerpack.assets :as assets])
  (:import (ch.digitalfondue.jfiveparse Parser)))

(defn find-links [uri doc]
  (->> (walker/create-matcher ["a[href]"])
       (.getAllNodesMatching doc)
       (map (fn [node]
              (let [[href id] (str/split (.getAttribute node "href") #"#")]
                (cond-> {:url (or (not-empty href) uri)
                         :text (.getTextContent node)}
                  id (assoc :id id)))))
       set))

(defn find-ids [doc]
  (->> (walker/create-matcher ["[id]"])
       (.getAllNodesMatching doc)
       (map #(.getAttribute % "id"))
       set))

(defn extract-page-data [ctx uri html]
  (let [doc (.parse (Parser.) html)
        assets (assets/extract-document-asset-urls ctx doc)
        links (find-links uri doc)
        ids (find-ids doc)]
    (cond-> {:uri (str/replace uri #"index\.html$" "")}
      (seq assets) (assoc :assets assets)
      (seq links) (assoc :links links)
      (seq ids) (assoc :ids ids))))

(defn remove-non-substantive-url-segments [url]
  (first (str/split url #"\?")))

(defn find-broken-links [powerpack {:keys [urls assets]} {:keys [links]}]
  (->> links
       (map #(update % :url remove-non-substantive-url-segments))
       (filter #(re-find #"^/[^\/]" (:url %)))
       (remove (comp (set urls) :url))
       (remove (comp (set (map :path (remove :outdated assets))) :url))
       (remove #(imagine/image-url? (:url %) (:imagine/config powerpack)))
       seq))

(defn format-broken-links [links]
  (->> links
       (group-by :page/uri)
       (map (fn [[uri links]]
              (str "Page: " uri "\n"
                   (->> (for [{:keys [href text]} (map :link links)]
                          (str "<a href=\"" href "\">" text "</a>"))
                        (str/join "\n")))))
       (str/join "\n\n")
       (str "Found broken links\n\n")))
