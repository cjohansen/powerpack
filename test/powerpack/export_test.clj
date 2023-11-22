(ns powerpack.export-test
  (:require [clojure.core.async :refer [<! <!! chan close! go put!]]
            [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [datomic-type-extensions.api :as d]
            [optimus.asset :as asset]
            [powerpack.export :as sut]
            [powerpack.logger :as log]
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
        (->> (for [[file-path content] (->> (keys @fs)
                                            (filter #(str/starts-with? % path))
                                            (filter #(re-find re %))
                                            (select-keys @fs))]
               [(str/replace file-path (re-pattern (str "^" path)) "") content])
             (into {})))

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
                              (->> [{:page/uri "/build-date"
                                     :page/response-type :edn
                                     :page/kind ::build-date
                                     :page/etag "0acbd"}]
                                   (d/transact (:datomic/conn powerpack-app))
                                   deref)))]
             (sut/export* exporter app {}))
           {:powerpack/problem :powerpack.export/unservable-urls
            :urls ["/build-date"]
            :success? false})))

  (testing "Detects bad links"
    (is (= (let [exporter (create-test-exporter)
                 app (assoc test-app/app
                            :powerpack/on-started (fn [& _args])
                            :powerpack/render-page
                            (fn [_context _page]
                              [:html
                               [:a {:href "/blog/sampl/"} "Broken link"]]))]
             (sut/export* exporter app {}))
           {:success? false
            :powerpack/problem :powerpack/broken-links
            :links [{:page/uri "/blog/sample/"
                     :url "/blog/sampl/"
                     :href "/blog/sampl/"
                     :text "Broken link"}
                    {:page/uri "/blog/eksempel/"
                     :url "/blog/sampl/"
                     :href "/blog/sampl/"
                     :text "Broken link"}]})))

  (testing "Detects bad hash links"
    (is (= (let [exporter (create-test-exporter)
                 app (assoc test-app/app
                            :powerpack/on-started (fn [& _args])
                            :powerpack/render-page
                            (fn [_context _page]
                              [:html
                               [:a {:href "#elsewhere"} "Broken link"]]))]
             (sut/export* exporter app {}))
           {:success? false
            :powerpack/problem :powerpack/broken-links
            :links [{:page/uri "/blog/sample/"
                     :url "/blog/sample/"
                     :href "#elsewhere"
                     :text "Broken link"
                     :id "elsewhere"}
                    {:page/uri "/blog/eksempel/"
                     :url "/blog/eksempel/"
                     :href "#elsewhere"
                     :text "Broken link"
                     :id "elsewhere"}]})))

  (testing "Can format detected bad links"
    (is (= (let [exporter (create-test-exporter)
                 app (assoc test-app/app
                            :powerpack/on-started (fn [& _args])
                            :powerpack/render-page
                            (fn [_context _page]
                              [:html
                               [:a {:href "#elsewhere"} "Broken link"]]))
                 result (sut/export* exporter app {})]
             (sut/format-report app result))
           (str "Found broken links\n\n"
                "Page: /blog/sample/\n"
                "<a href=\"#elsewhere\">Broken link</a>\n\n"
                "Page: /blog/eksempel/\n"
                "<a href=\"#elsewhere\">Broken link</a>"))))

  (testing "Accepts good hash link"
    (is (true? (-> (let [exporter (create-test-exporter)
                         app (assoc test-app/app
                                    :powerpack/on-started (fn [& _args])
                                    :powerpack/render-page
                                    (fn [_context _page]
                                      [:html
                                       [:body#page
                                        [:a {:href "/blog/sample/#page"} "Ok link"]]]))]
                     (sut/export* exporter app {}))
                   :success?))))

  (testing "Can opt out of link hash verification"
    (is (true? (-> (let [exporter (create-test-exporter)
                         app (assoc test-app/app
                                    :powerpack/on-started (fn [& _args])
                                    :powerpack/render-page
                                    (fn [_context _page]
                                      [:html
                                       [:a {:href "#elsewhere"} "Broken link"]]))]
                     (sut/export* exporter app {:skip-link-hash-verification? true}))
                   :success?))))

  (testing "Reuses files with unchanged etags"
    (is (= (->> (let [exporter (create-test-exporter
                                {"build/etags.edn" "{\"/build-date.edn\" \"0acbd\"}"
                                 "build/build-date.edn" "{:i-am \"Cached\"}"})]
                  (sut/export*
                   exporter
                   (assoc test-app/app
                          :powerpack/on-started
                          (fn [powerpack-app]
                            (->> [{:page/uri "/build-date.edn"
                                   :page/response-type :edn
                                   :page/kind ::build-date
                                   :page/etag "0acbd"}]
                                 (d/transact (:datomic/conn powerpack-app))
                                 deref)))
                   {}))
                :exported-pages
                (filter (comp #{"/build-date.edn"} :uri))
                first)
           {:uri "/build-date.edn"
            :elapsed 0
            :cached? true
            :page-data {:uri "/build-date.edn"}})))

  (testing "Does not reuse files with changed etags"
    (is (not (->> (let [exporter (create-test-exporter
                                  {"build/etags.edn" "{\"/build-date.edn\" \"xxx666\"}"
                                   "build/build-date.edn" "{:i-am \"Cached\"}"})]
                    (sut/export*
                     exporter
                     (assoc test-app/app
                            :powerpack/on-started
                            (fn [powerpack-app]
                              (->> [{:page/uri "/build-date.edn"
                                     :page/response-type :edn
                                     :page/kind ::build-date
                                     :page/etag "0acbd"}]
                                   (d/transact (:datomic/conn powerpack-app))
                                   deref)))
                     {}))
                  :exported-pages
                  (filter (comp #{"/build-date.edn"} :uri))
                  first
                  :cached?)))))
