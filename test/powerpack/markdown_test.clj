(ns powerpack.markdown-test
  (:require [clojure.test :refer [deftest is testing]]
            [powerpack.markdown :as sut]))

(deftest render-html-test
  (testing "Opts out of auto linking"
    (is (= (-> "Here's a URL https://url.com. Don't auto link it, please"
               (sut/render-html {:auto-link? false
                                 :typograhy? false})
               str)
           "<p>Here's a URL https://url.com. Don't auto link it, please</p>\n"))))
