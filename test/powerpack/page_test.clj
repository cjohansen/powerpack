(ns powerpack.page-test
  (:require [clojure.test :refer [deftest is testing]]
            [powerpack.page :as sut]))

(deftest broken-links-test
  (testing "Finds broken link"
    (is (= (sut/find-broken-links
            {}
            {:pages {"/" ""}
             :page-data [{:uri "/"
                          :links [{:href "/missing-page/"
                                   :text "Click"}]}]})
           [{:uri "/"
             :link {:href "/missing-page/"
                    :text "Click"}}])))

  (testing "Does not consider external link broken"
    (is (= (sut/find-broken-links
            {}
            {:pages {"/" ""}
             :page-data [{:uri "/"
                          :links [{:href "https://elsewhere.com/potentially-missing-page/"
                                   :text "Click"}]}]})
           [])))

  (testing "Does not consider asset link broken"
    (is (= (sut/find-broken-links
            {}
            {:pages {"/" ""}
             :assets [{:path "/favicon.ico"}]
             :page-data [{:uri "/"
                          :links [{:href "/favicon.ico"
                                   :text "See our favicon"}]}]})
           [])))

  (testing "Does not consider imagine URL broken"
    (is (= (sut/find-broken-links
            {:imagine/config
             {:prefix "image-assets"
              :resource-path "public"
              :disk-cache? true
              :transformations
              {:vcard-small
               {:transformations [[:fit {:width 184 :height 184}]
                                  [:crop {:preset :square}]]
                :retina-optimized? true
                :retina-quality 0.4
                :width 184}}}}
            {:pages {"/" ""}
             :assets []
             :page-data [{:uri "/"
                          :links [{:href "/image-assets/vcard-small/53497fa722f0413f0479b0c28fd7722c0cc7f124/images/ducks.jpg"
                                   :text "See some ducks"}]}]})
           []))))

(deftest format-broken-links-test
  (testing "Formats broken links nicely"
    (is (= (sut/format-broken-links
            [{:uri "/"
              :link {:href "/broken1/"
                     :text "#1"}}
             {:uri "/"
              :link {:href "/broken2/"
                     :text "#2"}}
             {:uri "/blog/"
              :link {:href "/broken3/"
                     :text "#3"}}])
           (str "Found broken links\n\n"
                "Page: /\n"
                "<a href=\"/broken1/\">#1</a>\n"
                "<a href=\"/broken2/\">#2</a>\n\n"
                "Page: /blog/\n"
                "<a href=\"/broken3/\">#3</a>")))))
