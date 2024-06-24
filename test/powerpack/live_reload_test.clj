(ns powerpack.live-reload-test
  (:require [clojure.test :refer [deftest is testing]]
            [powerpack.live-reload :as sut]))

(deftest get-ns-test
  (testing "Extracts namespace from compact namespace"
    (is (= (sut/get-ns "(ns mmm.components.card)")
           'mmm.components.card)))

  (testing "Extracts namespace from namespace with require"
    (is (= (sut/get-ns "(ns powerpack.live-reload
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]))")
           'powerpack.live-reload))))

(deftest compares-config-regexes-as-strings
  (is (false? (sut/config-changed?
               {:optimus/assets [{:public-dir "public" :paths [#".*\.png"]}]}
               {:optimus/assets [{:public-dir "public" :paths [#".*\.png"]}]}))))
