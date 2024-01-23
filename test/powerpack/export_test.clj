(ns powerpack.export-test
  (:require [clojure.core.async :refer [<! <!! chan close! go put!]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datomic-type-extensions.api :as d]
            [optimus.asset :as asset]
            [powerpack.export :as sut]
            [powerpack.logger :as log]
            [powerpack.mockfs :as mockfs]
            [powerpack.protocols :as powerpack]
            [powerpack.test-app :as test-app]
            [stasis.core :as stasis]))

(defn create-test-exporter [& [files]]
  (let [fs (atom files)
        tmp (atom 0)]
    (reify
      powerpack/IFileSystem
      (file-exists? [_ path]
        (mockfs/file-exists? @fs path))

      (read-file [_ path]
        (mockfs/read-file @fs path))

      (get-entries [_ path]
        (mockfs/get-entries @fs path))

      (write-file [_ path content]
        (swap! fs mockfs/write-file path content))

      (delete-file [_ path]
        (swap! fs mockfs/delete-file path))

      (move [_ source dest]
        (swap! fs mockfs/move source dest))

      (copy [_ source dest]
        (swap! fs mockfs/copy source dest))

      (get-tmp-path [_]
        (str "/tmp/" (swap! tmp inc)))

      powerpack/IOptimus
      (export-assets [_ assets build-dir]
        (doseq [asset assets]
          (swap! fs assoc (str build-dir (asset/path asset)) (:contents asset))))

      powerpack/IStasis
      (export-page [_ uri body build-dir]
        (swap! fs assoc (str build-dir (stasis/normalize-uri uri)) body))

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

  (testing "Manually OKs links unknown to Powerpack"
    (is (let [exporter (create-test-exporter)
              app (assoc test-app/app
                         :powerpack/on-started (fn [& _args])
                         :powerpack/render-page
                         (fn [_context _page]
                           [:html
                            [:a {:href "/nginx-rewrite/lol/"} "Ok link"]]))]
          (->> {:link-ok? (fn [powerpack ctx link]
                            (re-find #"/nginx-rewrite" (:href link)))}
               (sut/export* exporter app)
               :success?))))

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

  (testing "Can format bad canonical link"
    (is (= (let [exporter (create-test-exporter)
                 app (assoc test-app/app
                            :powerpack/on-started (fn [& _args])
                            :powerpack/render-page
                            (fn [_context _page]
                              [:html
                               [:head [:link {:href "/bad" :rel "canonical"}]]]))
                 result (sut/export* exporter app {})]
             (sut/format-report app result))
           (str "Found broken links\n\n"
                "Page: /blog/sample/\n"
                "<link rel=\"canonical\" href=\"/bad\">\n\n"
                "Page: /blog/eksempel/\n"
                "<link rel=\"canonical\" href=\"/bad\">"))))

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
            :cached? true})))

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
                  :cached?))))

  (testing "Extracts page data from cached HTML file"
    (is (= (->> (let [exporter (create-test-exporter
                                {"build/blog/sample/index.html" "<a href=\"/blog/sample/\">Link</a>"
                                 "build/etags.edn" "{\"/blog/sample/\" \"xxx666\"}"})]
                  (sut/export*
                   exporter
                   (assoc test-app/app
                          :powerpack/on-started
                          (fn [powerpack-app]
                            (->> [{:page/uri "/blog/sample/"
                                   :page/etag "xxx666"}]
                                 (d/transact (:datomic/conn powerpack-app))
                                 deref)))
                   {}))
                :exported-pages
                (filter (comp #{"/blog/sample/"} :uri))
                first)
           {:uri "/blog/sample/"
            :elapsed 0
            :cached? true
            :page-data
            {:uri "/blog/sample/"
             :links #{{:href "/blog/sample/"
                       :url "/blog/sample/"
                       :text "Link"}}}}))))
