(ns powerpack.files-test
  (:require [powerpack.files :as sut]
            [clojure.test :refer [deftest is testing]]
            [clojure.java.io :as io]))

(deftest same-file?
  (testing "Recognizes same file"
    (is (sut/same-file?
         "src/powerpack/files.clj"
         (io/file "src/powerpack/files.clj")))

    (is (sut/same-file?
         (io/resource "schema.edn")
         (io/file "dev-resources/schema.edn")))

    (is (sut/same-file?
         (io/resource "schema.edn")
         "dev-resources/schema.edn"))

    (is (not (sut/same-file? "schema.edn" "dev-resources/schema.edn")))))

(deftest parent?
  (testing "recognizes parent directories"
    (is (sut/parent? "resources" "resources/schema.edn"))

    (is (sut/parent? "src" "src/powerpack/core.clj"))

    (is (not (sut/parent? "test" "src/powerpack/core.clj")))

    (is (not (sut/parent? "src" "src")))

    (is (not (sut/parent? "src/" "src")))

    (is (not (sut/parent? "src" "src/")))

    (is (not (sut/parent? "dev" "dev-resources")))))

(deftest get-relative-path
  (testing "returns relative path"
    (is (= (sut/get-relative-path "src" "src/powerpack/core.clj")
           "powerpack/core.clj"))

    (is (= (sut/get-relative-path "src" "src/powerpack")
           "powerpack"))

    (is (= (sut/get-relative-path "" "index.html")
           "index.html"))))
