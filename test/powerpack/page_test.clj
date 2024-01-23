(ns powerpack.page-test
  (:require [clojure.test :refer [deftest is testing]]
            [powerpack.page :as sut]))

(deftest extract-page-data-test
  (testing "Extracts links"
    (is (= (sut/extract-page-data
            {}
            "/test/"
            "<!DOCTYPE html><html>
             <head><link rel=\"canonical\" href=\"/my/real/url/\"></head>
             <body>
             <a href=\"/page/\">Link 1</a>
             <ul>
             <li><a href=\"https://external.com/page/\">li link 1</a></li>
             <li><a href=\"/page/#some-id\">li link 2</a></li>
             <li><a href=\"#some-id\">li link 3</a></li>
             </ul>
             </body></html>")
           {:uri "/test/"
            :links #{{:href "/page/" :url "/page/", :text "Link 1"}
                     {:href "https://external.com/page/" :url "https://external.com/page/", :text "li link 1"}
                     {:href "/page/#some-id" :url "/page/", :id "some-id", :text "li link 2"}
                     {:href "#some-id" :url "/test/", :id "some-id", :text "li link 3"}
                     {:href "/my/real/url/" :url "/my/real/url/" :kind "canonical"}}})))

  (testing "Extracts page ids"
    (is (= (sut/extract-page-data
            {}
            "/test/"
            "<!DOCTYPE html><html><body>
             <div id=\"section-1\"><h1>Section 1</h1></div>
             <div id=\"section-2\"><h2>Section 2</h2></div>
             </body></html>")
           {:uri "/test/"
            :ids #{"section-1" "section-2"}}))))

(deftest broken-links-test
  (testing "Finds broken link"
    (is (= (sut/find-broken-links
            {}
            {:urls #{"/"}}
            {:uri "/"
             :links [{:url "/missing-page/"
                      :text "Click"}]})
           [{:page/uri "/"
             :url "/missing-page/"
             :text "Click"}])))

  (testing "Does not consider external link broken"
    (is (empty? (sut/find-broken-links
                 {}
                 {:urls #{"/"}}
                 {:uri "/"
                  :links [{:url "https://elsewhere.com/potentially-missing-page/"
                           :text "Click"}]}))))

  (testing "Does not care about query parameters"
    (is (empty? (sut/find-broken-links
                 {}
                 {:urls #{"/"}}
                 {:uri "/"
                  :links [{:url "/?yes=no"
                           :text "Click"}]}))))

  (testing "Does not consider asset link broken"
    (is (empty? (sut/find-broken-links
                 {}
                 {:urls #{"/"}
                  :asset-urls #{"/favicon.ico"}}
                 {:uri "/"
                  :links [{:url "/favicon.ico"
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
                  :links [{:url "/image-assets/vcard-small/53497fa722f0413f0479b0c28fd7722c0cc7f124/images/ducks.jpg"
                           :text "See some ducks"}]})))))

(deftest format-broken-links-test
  (testing "Formats broken links nicely"
    (is (= (sut/format-broken-links
            [{:page/uri "/"
              :url "/broken1/"
              :href "/broken1/"
              :text "#1"}
             {:page/uri "/"
              :url "/broken2/"
              :href "/broken2/"
              :text "#2"}
             {:page/uri "/other/"
              :url "/broken3/"
              :href "/broken3/"
              :text "#3"}])
           (str "Found broken links\n\n"
                "Page: /\n"
                "<a href=\"/broken1/\">#1</a>\n"
                "<a href=\"/broken2/\">#2</a>\n\n"
                "Page: /other/\n"
                "<a href=\"/broken3/\">#3</a>")))))
