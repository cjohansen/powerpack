(ns powerpack.highlight-test
  (:require [clojure.test :refer [deftest is testing]]
            [html5-walker.core :as html5-walker]
            [powerpack.highlight :as sut]))

(deftest highlight-test
  (testing "Highlights code in pre>code"
    (is (= (->> (sut/get-code-block-highlighters {})
                (html5-walker/replace-in-document
                 "<!DOCTYPE>
<html>
  <pre><code class=\"language-clj\">(prn {:a 1
       :b 2
       :c 3})</code></pre>
</html>"))
           (str "<html><head></head><body><pre class=\"codehilite\">"
                "<code class=\"language-clj\"><span></span>"
                "<span class=\"p\">(</span><span class=\"nb\">prn </span>"
                "<span class=\"p\">{</span><span class=\"ss\">:a</span> "
                "<span class=\"mi\">1</span>\n       <span class=\"ss\">:b</span> "
                "<span class=\"mi\">2</span>\n       <span class=\"ss\">:c</span> "
                "<span class=\"mi\">3</span><span class=\"p\">})</span>\n"
                "</code></pre>\n</body></html>")))))
