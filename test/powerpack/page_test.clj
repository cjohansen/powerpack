(ns powerpack.page-test
  (:require [clojure.test :refer [deftest is testing]]
            [powerpack.page :as sut]))

(deftest broken-links-test
  (testing "Finds broken link"
    (is (= (sut/find-broken-links
            {}
            {:urls #{"/"}}
            {:uri "/"
             :links [{:href "/missing-page/"
                      :text "Click"}]})
           [{:href "/missing-page/"
             :text "Click"}])))

  (testing "Does not consider external link broken"
    (is (empty? (sut/find-broken-links
                 {}
                 {:urls #{"/"}}
                 {:uri "/"
                  :links [{:href "https://elsewhere.com/potentially-missing-page/"
                           :text "Click"}]}))))

  (testing "Does not care about query parameters"
    (is (empty? (sut/find-broken-links
                 {}
                 {:urls #{"/"}}
                 {:uri "/"
                  :links [{:href "/?yes=no"
                           :text "Click"}]}))))

  (testing "Does not care about the fragment identifier"
    (is (empty? (sut/find-broken-links
                 {}
                 {:urls #{"/"}}
                 {:uri "/"
                  :links [{:href "/#my-fragment"
                           :text "Click"}]}))))

  (testing "Does not consider asset link broken"
    (is (empty? (sut/find-broken-links
                 {}
                 {:urls #{"/"}
                  :assets [{:path "/favicon.ico"}]}
                 {:uri "/"
                  :links [{:href "/favicon.ico"
                           :text "See our favicon"}]}))))

  (testing "Does not consider imagine URL broken"
    (is (empty? (sut/find-broken-links
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
                 {:urls #{"/"}
                  :assets []}
                 {:uri "/"
                  :links [{:href "/image-assets/vcard-small/53497fa722f0413f0479b0c28fd7722c0cc7f124/images/ducks.jpg"
                           :text "See some ducks"}]})))))

(deftest format-broken-links-test
  (testing "Formats broken links nicely"
    (is (= (sut/format-broken-links
            [{:uri "/"
              :href "/broken1/"
              :text "#1"}
             {:uri "/"
              :href "/broken2/"
              :text "#2"}
             {:uri "/other/"
              :href "/broken3/"
              :text "#3"}])
           (str "Found broken links\n\n"
                "Page: /\n"
                "<a href=\"\"></a>\n"
                "<a href=\"\"></a>\n\n"
                "Page: /other/\n"
                "<a href=\"\"></a>")))))
