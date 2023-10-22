(ns powerpack.assets-test
  (:require [clojure.test :refer [deftest is testing]]
            [html5-walker.walker :as walker]
            [powerpack.assets :as sut]))

(deftest markup-url-optimizers-test
  (testing "optimizes img src optimus asset"
    (is (= (->> (sut/get-markup-url-optimizers
                 {:optimus-assets [{:original-path "/images/ducks.jpg"
                                    :path "/sproing/xyz.jpg"}]})
                (walker/replace-in-fragment
                 "<img src=\"/images/ducks.jpg\">"))
           "<img src=\"/sproing/xyz.jpg\">")))

  (testing "does not suffer missing assets"
    (is (= (try
             (->> (sut/get-markup-url-optimizers
                   {:optimus-assets []})
                  (walker/replace-in-fragment
                   "<img src=\"/images/ducks.jpg\">"))
             (catch Exception e
               (ex-data e)))
           {:path "/images/ducks.jpg"})))

  (testing "optimizes img src imagine image"
    (is (= (->> (sut/get-markup-url-optimizers
                 {:optimus-assets []
                  :powerpack/app
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
                      :width 184}}}}})
                (walker/replace-in-fragment
                 "<img src=\"/vcard-small/images/ducks.jpg\">"))
           "<img src=\"/image-assets/vcard-small/53497fa722f0413f0479b0c28fd7722c0cc7f124/images/ducks.jpg\">")))

  (testing "accepts pre-optimized imagine image"
    (is (= (->> (sut/get-markup-url-optimizers
                 {:optimus-assets []
                  :powerpack/app
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
                      :width 184}}}}})
                (walker/replace-in-fragment
                 "<img src=\"/image-assets/vcard-small/53497fa722f0413f0479b0c28fd7722c0cc7f124/images/ducks.jpg\">"))
           "<img src=\"/image-assets/vcard-small/53497fa722f0413f0479b0c28fd7722c0cc7f124/images/ducks.jpg\">")))

  (testing "optimizes img srcset"
    (is (= (->> (sut/get-markup-url-optimizers
                 {:optimus-assets [{:original-path "/images/ducks.jpg"
                                    :path "/sproing/xyz.jpg"}
                                   {:original-path "/images/storks.jpg"
                                    :path "/sproing/kzzzh.jpg"}]})
                (walker/replace-in-fragment
                 "<img src=\"/images/ducks.jpg\" srcset=\"/images/ducks.jpg, /images/storks.jpg 2x\">"))
           "<img src=\"/sproing/xyz.jpg\" srcset=\"/sproing/xyz.jpg, /sproing/kzzzh.jpg 2x\">")))

  (testing "optimizes og:image"
    (is (= (->> (sut/get-markup-url-optimizers
                 {:powerpack/app {:site/base-url "https://rubber.duck"}
                  :optimus-assets [{:original-path "/images/ducks.jpg"
                                    :path "/sproing/xyz.jpg"}]})
                (walker/replace-in-document
                 (str "<html><head>"
                      "<meta property=\"og:image\" content=\"/images/ducks.jpg\">"
                      "</head><body></body></html>")))
           (str "<html><head>"
                "<meta property=\"og:image\" content=\"https://rubber.duck/sproing/xyz.jpg\">"
                "</head><body></body></html>"))))

  (testing "optimizes pre-qualified og:image"
    (is (= (->> (sut/get-markup-url-optimizers
                 {:powerpack/app {:site/base-url "https://rubber.duck"}
                  :optimus-assets [{:original-path "/images/ducks.jpg"
                                    :path "/sproing/xyz.jpg"}]})
                (walker/replace-in-document
                 (str "<html><head>"
                      "<meta property=\"og:image\" content=\"https://rubber.duck/images/ducks.jpg\">"
                      "</head><body></body></html>")))
           (str "<html><head>"
                "<meta property=\"og:image\" content=\"https://rubber.duck/sproing/xyz.jpg\">"
                "</head><body></body></html>"))))

  (testing "optimizes style attributes"
    (is (= (->> (sut/get-markup-url-optimizers
                 {:optimus-assets [{:original-path "/images/ducks.jpg"
                                    :path "/sproing/xyz.jpg"}]})
                (walker/replace-in-fragment
                 "<div style=\"background: url(/images/ducks.jpg); border-image: url('/images/ducks.jpg');\"></div>"))
           "<div style=\"background: url(/sproing/xyz.jpg); border-image: url(/sproing/xyz.jpg);\"></div>")))

  (testing "optimizes source src"
    (is (= (->> (sut/get-markup-url-optimizers
                 {:optimus-assets [{:original-path "/images/ducks.jpg"
                                    :path "/sproing/xyz.jpg"}]})
                (walker/replace-in-fragment
                 "<picture><source src=\"/images/ducks.jpg\"></picture>"))
           "<picture><source src=\"/sproing/xyz.jpg\"></picture>")))

  (testing "optimizes source srcset"
    (is (= (->> (sut/get-markup-url-optimizers
                 {:optimus-assets [{:original-path "/images/ducks.jpg"
                                    :path "/sproing/xyz.jpg"}]})
                (walker/replace-in-fragment
                 "<picture><source srcset=\"/images/ducks.jpg\"></picture>"))
           "<picture><source srcset=\"/sproing/xyz.jpg\"></picture>")))

  (testing "optimizes source svg use"
    (is (= (->> (sut/get-markup-url-optimizers
                 {:optimus-assets [{:original-path "/images/ducks.svg"
                                    :path "/sproing/xyz.svg"}]})
                (walker/replace-in-fragment
                 "<svg><use href=\"/images/ducks.svg\"></use></svg>"))
           "<svg><use xlink:href=\"/sproing/xyz.svg\"></use></svg>")))

  (testing "optimizes source svg use with xlink:href"
    (is (= (->> (sut/get-markup-url-optimizers
                 {:optimus-assets [{:original-path "/images/ducks.svg"
                                    :path "/sproing/xyz.svg"}]})
                (walker/replace-in-fragment
                 "<svg><use xlink:href=\"/images/ducks.svg\"></use></svg>"))
           "<svg><use xlink:href=\"/sproing/xyz.svg\"></use></svg>")))

  (testing "optimizes source a href"
    (is (= (->> (sut/get-markup-url-optimizers
                 {:optimus-assets [{:original-path "/images/ducks.svg"
                                    :path "/sproing/xyz.svg"}]})
                (walker/replace-in-fragment
                 "<a href=\"/images/ducks.svg\">Click</a>"))
           "<a href=\"/sproing/xyz.svg\">Click</a>")))

  (testing "ignores non-asset a hrefs"
    (is (= (->> (sut/get-markup-url-optimizers
                 {:optimus-assets [{:original-path "/images/ducks.svg"
                                    :path "/sproing/xyz.svg"}]})
                (walker/replace-in-fragment
                 "<a href=\"/images/\">Click</a>"))
           "<a href=\"/images/\">Click</a>"))))