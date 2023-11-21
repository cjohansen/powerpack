(ns powerpack.export-test
  (:require [clojure.core.async :refer [<! <!! chan close! go put!]]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [datomic-type-extensions.api :as d]
            [optimus.asset :as asset]
            [powerpack.export :as sut]
            [powerpack.logger :as log]
            [powerpack.logger :as logger]
            [powerpack.protocols :as powerpack]
            [powerpack.test-app :as test-app]
            [ring.util.codec :refer [url-decode]]))

;; Borrowed from Stasis
(defn normalize-uri [^String uri]
  (let [decoded-uri (url-decode uri)]
    (cond
      (.endsWith decoded-uri ".html") decoded-uri
      (.endsWith decoded-uri "/") (str decoded-uri "index.html")
      :else decoded-uri)))

(defn create-test-exporter [& [files]]
  (let [fs (atom files)]
    (reify
      powerpack/IFileSystem
      (read-file [_ path]
        (get @fs path))

      (get-entries [_ path]
        (filter #(str/starts-with? % path) (keys @fs)))

      (write-file [_ path content]
        (swap! fs assoc path content))

      powerpack/IOptimus
      (export-assets [_ assets build-dir]
        (doseq [asset assets]
          (swap! fs assoc (str build-dir (asset/path asset)) (:contents asset))))

      powerpack/IStasis
      (slurp-directory [_ path re]
        (->> (keys @fs)
             (filter #(str/starts-with? % path))
             (filter #(re-find re %))
             (select-keys @fs)))

      (export-page [_ uri body build-dir]
        (swap! fs assoc (str build-dir (normalize-uri uri)) body))

      (empty-directory! [_ dir]
        (->> (keys @fs)
             (filter #(str/starts-with? % dir))
             (swap! fs (partial apply dissoc))))

      powerpack/IImagine
      (transform-image-to-file [_ transformation path]
        (swap! fs assoc path {:kind :image :transformation transformation})))))

(defmacro with-logger [& body]
  `(let [ch# (chan)
         res# (chan 1024)
         messages# (atom [])]
     (go
       (loop []
         (if-let [msg# (<! ch#)]
           (do
             (swap! messages# conj (str/join " " msg#))
             (recur))
           (do
             (put! res# @messages#)
             (close! res#)))))
     (binding [log/logger-ch ch#]
       (try
         ~@body
         res#
         (finally
           (close! ch#))))))

(deftest export-test
  (testing "Empties previous export dir"
    (is (< (-> (let [exporter (create-test-exporter {"build/README.md" "..."})]
                 (sut/export* exporter test-app/app {})
                 (powerpack/get-entries exporter "build"))
               (.indexOf "build/README.md"))
           0)))

  (testing "Finds dictionary errors"
    (is (= (-> (let [exporter (create-test-exporter)
                     app (assoc-in test-app/app
                                   [:m1p/dictionaries :en]
                                   ["dev/i18n/en-incomplete.edn"])]
                 (sut/export* exporter app {}))
               (select-keys [:problems :success?]))
           {:success? false
            :problems [{:kind :missing-key
                        :dictionary :en
                        :key :rubberduck.core/uri}]})))

  (testing "Reports dictionary errors"
    (is (->> (let [exporter (create-test-exporter)
                   app (assoc-in test-app/app
                                 [:m1p/dictionaries :en]
                                 ["dev/i18n/en-incomplete.edn"])]
               (<!! (with-logger (sut/export* exporter app {}))))
             (filter #(re-find #"i18n dictionaries" %))
             seq)))

  (testing "Detects relative URLs"
    (is (= (let [exporter (create-test-exporter)
                 app (assoc test-app/app
                            :powerpack/on-started
                            (fn [powerpack-app]
                              (->> [{:page/uri "build-date.edn"
                                     :page/response-type :edn
                                     :page/kind ::build-date
                                     :page/etag "0acbd"}]
                                   (d/transact (:datomic/conn powerpack-app))
                                   deref)))]
             (sut/export* exporter app {}))
           {:powerpack/problem :powerpack.export/relative-urls
            :urls ["build-date.edn"]
            :success? false})))

  (testing "Detects URLs that aren't static-friendly"
    (is (= (let [exporter (create-test-exporter)
                 app (assoc test-app/app
                            :powerpack/on-started
                            (fn [powerpack-app]
                              (->> [{:page/uri "/uild-date"
                                     :page/response-type :edn
                                     :page/kind ::build-date
                                     :page/etag "0acbd"}]
                                   (d/transact (:datomic/conn powerpack-app))
                                   deref)))]
             (sut/export* exporter app {}))
           {:powerpack/problem :powerpack.export/unservable-urls
            :urls ["/uild-date"]
            :success? false}))))
