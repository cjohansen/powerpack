(ns powerpack.export-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest testing is]]
            [optimus.asset :as asset]
            [powerpack.export :as sut]
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

(deftest export-test
  (testing "Empties previous export dir"
    (is (< (-> (let [exporter (create-test-exporter {"build/README.md" "..."})]
                 (sut/export* exporter test-app/app {})
                 (powerpack/get-entries exporter "build"))
               (.indexOf "build/README.md"))
           0))))
