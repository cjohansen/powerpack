(ns powerpack.watcher-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [powerpack.watcher :as sut]))

(deftest get-watch-paths
  (testing "Filters out non-existent dirs"
    (is (= (sut/get-watch-paths
            {:powerpack/source-dirs ["src" "dev" "non-existent"]
             :powerpack/content-dir "content"
             :datomic/schema-file "dev-resources/schema.edn"})
           ["src" "dev" "dev-resources"])))

  (testing "Only watches the top-level distinct dirs"
    (is (= (sut/get-watch-paths
            {:powerpack/source-dirs ["src"]
             :powerpack/resource-dirs ["dev-resources"]
             :powerpack/content-dir "dev-resources/public"
             :datomic/schema-file "dev-resources/schema.edn"})
           ["src" "dev-resources"])))

  (testing "Adds i18n directories to the watch path"
    (is (= (sut/get-watch-paths
            {:powerpack/source-dirs ["src"]
             :powerpack/resource-dirs ["dev-resources"]
             :powerpack/content-dir "dev-resources/public"
             :datomic/schema-file "dev-resources/schema.edn"
             :m1p/dictionaries {:nb ["dev/i18n/nb.edn"]
                                :en ["dev/i18n/en.edn"]}})
           ["dev/i18n" "src" "dev-resources"])))

  (testing "Accepts raw dirs as i18n places"
    (is (= (sut/get-watch-paths
            {:powerpack/source-dirs ["src"]
             :powerpack/resource-dirs ["dev-resources"]
             :powerpack/content-dir "dev-resources/public"
             :datomic/schema-file "dev-resources/schema.edn"
             :m1p/dictionaries {:nb ["dev/i18n"]
                                :en ["dev/i18n"]}})
           ["dev/i18n" "src" "dev-resources"]))))

(deftest get-app-event
  (testing "Edits schema"
    (is (= (sut/get-app-event
            {:datomic/schema-file "dev-resources/schema.edn"}
            {:path (.toPath (io/file "dev-resources/schema.edn"))})
           {:kind :powerpack/edited-schema
            :action "reload"})))

  (testing "Edits content"
    (is (= (sut/get-app-event
            {:datomic/schema-file "dev-resources/schema.edn"
             :powerpack/content-dir "dev-resources"
             :powerpack/content-file-suffixes ["md" "edn"]}
            {:type :modify
             :path (.toPath (io/file "dev-resources/blog/sample.md"))})
           {:kind :powerpack/edited-content
            :type :modify
            :path "blog/sample.md"
            :action "reload"})))

  (testing "Respects content file suffixes"
    (is (= (sut/get-app-event
            {:datomic/schema-file "dev-resources/schema.edn"
             :powerpack/content-dir "dev-resources"
             :powerpack/content-file-suffixes ["edn"]}
            {:type :modify
             :path (.toPath (io/file "dev-resources/blog/sample.md"))})
           nil)))

  (testing "Edits source"
    (is (= (sut/get-app-event
            {:datomic/schema-file "dev-resources/schema.edn"
             :powerpack/content-dir "dev-resources"
             :powerpack/source-dirs ["src" "dev"]}
            {:type :modify
             :path (.toPath (io/file "dev/rubberduck/core.clj"))})
           {:kind :powerpack/edited-source
            :action "reload"
            :path "dev/rubberduck/core.clj"})))

  (testing "Edits asset"
    (is (= (sut/get-app-event
            {:datomic/schema-file "dev-resources/schema.edn"
             :powerpack/content-dir "dev-resources"
             :powerpack/source-dirs ["src" "dev"]
             :powerpack/resource-dirs ["dev-resources"]
             :powerpack/content-file-suffixes ["edn"]
             :optimus/assets [{:public-dir "public"
                               :paths [#"/images/*.*"]}]}
            {:type :modify
             :path (.toPath (io/file "dev-resources/public/images/ducks.jpg"))})
           {:kind :powerpack/edited-asset
            :type :modify
            :path "/images/ducks.jpg"
            :action "reload"})))

  (testing "Edits asset in bundle"
    (is (= (sut/get-app-event
            {:datomic/schema-file "dev-resources/schema.edn"
             :powerpack/content-dir "dev-resources"
             :powerpack/source-dirs ["src" "dev"]
             :powerpack/resource-dirs ["dev-resources"]
             :powerpack/content-file-suffixes ["edn"]
             :optimus/bundles {"styles.css"
                               {:public-dir "public"
                                :paths ["/styles/test.css"]}}}
            {:type :modify
             :path (.toPath (io/file "dev-resources/public/styles/test.css"))})
           {:kind :powerpack/edited-asset
            :action "reload-css"
            :type :modify
            :path "/styles/test.css"
            :updatedPath "/styles/b8936d0bc201/test.css"})))

  (testing "Edits i18n dictionary"
    (is (= (sut/get-app-event
            {:datomic/schema-file "dev-resources/schema.edn"
             :powerpack/content-dir "dev-resources"
             :powerpack/source-dirs ["src" "dev"]
             :powerpack/resource-dirs ["dev-resources"]
             :powerpack/content-file-suffixes ["edn"]
             :m1p/dictionaries {:nb ["dev/i18n/nb.edn"]
                                :en ["dev/i18n/en.edn"]}}
            {:type :modify
             :path (.toPath (io/file "dev/i18n/nb.edn"))})
           {:kind :powerpack/edited-dictionary
            :action "reload"
            :type :modify
            :path "dev/i18n/nb.edn"}))))
