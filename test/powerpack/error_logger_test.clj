(ns powerpack.error-logger-test
  (:require [powerpack.error-logger :as sut]
            [clojure.test :refer [deftest is testing]]))

(deftest readable-lines-test
  (testing "Breaks up long lines"
    (is (= (-> "This line is rather long and the function should probably chop it in two to spare the reader horizontalitis."
               sut/get-readable-lines)
           ["This line is rather long and the function should probably chop it in two to"
            "spare the reader horizontalitis."])))

  (testing "Does not join lines"
    (is (= (-> "Here's a short line.\nAnd here's another"
               sut/get-readable-lines)
           ["Here's a short line."
            "And here's another"])))

  (testing "Maintains indent on broken lines"
    (is (= (-> "Line 1\n    And here's another, which is much to long and should be broken down in order to be easier to read.\nThird"
               sut/get-readable-lines)
           ["Line 1"
            "    And here's another, which is much to long and should be broken down in order"
            "    to be easier to read."
            "Third"]))))
