(ns powerpack.html-test
  (:require [powerpack.html :as sut]
            [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]))

(defn get-open-graph-metas [html]
  (->> (tree-seq coll? identity html)
       (filter vector?)
       (filter (comp #{:meta} first))
       (filter (comp :property second))
       (filter #(str/starts-with? (:property (second %)) "og:"))))

(deftest build-doc
  (testing "Renders a head element"
    (is (= (sut/build-doc {} {} [:h1 "Hello world"])
           [:html {:prefix "og: http://ogp.me/ns#"}
            [:head
             [:meta {:charset "utf-8"}]
             [:meta {:name "viewport", :content "width=device-width, initial-scale=1.0"}]
             [:meta {:property "og:type", :content "article"}]
             [:title ""]]
            [:h1 "Hello world"]])))

  (testing "Includes lang attribute with default lang"
    (is (= (-> (sut/build-doc {:powerpack/config {:site/default-language "nb"}} {} [:h1 "Hello world"])
               second
               :lang)
           "nb")))

  (testing "Includes page language when available"
    (is (= (-> (sut/build-doc
                {:powerpack/config {:site/default-language "nb"}}
                {:page/language "en"}
                [:h1 "Hello world"])
               second
               :lang)
           "en")))

  (testing "Includes open graph meta tags"
    (is (= (-> (sut/build-doc
                {:powerpack/config {:site/base-url "https://greetings.world"}}
              {:open-graph/description "A greeting of worlds"
               :open-graph/title "Hello!"
               :page/uri "/hello-world/"}
              [:h1 "Hello world"])
               get-open-graph-metas)
           [[:meta {:property "og:type", :content "article"}]
            [:meta {:property "og:description", :content "A greeting of worlds"}]
            [:meta {:property "og:title", :content "Hello!"}]
            [:meta {:property "og:url", :content "https://greetings.world/hello-world/"}]])))

  (testing "Truncates open graph description"
    (is (= (-> (sut/build-doc
                {:powerpack/config {:site/base-url "https://greetings.world"}}
                {:open-graph/description "A greeting of worlds that is much too long to have any hope of appearing in full on the relevant social media platforms that one typically authors open graph descriptions for - and thus, is shortened somewhat brutally."
                 :open-graph/title "Hello!"
                 :page/uri "/hello-world/"}
                [:h1 "Hello world"])
               get-open-graph-metas
               second
               second
               :content)
           (str "A greeting of worlds that is much too long to have any hope "
                "of appearing in full on the relevant social media platforms "
                "that one typically authors open graph descriptions for - and "
                "thus, is shortened…"))))

  (testing "Truncates open graph title"
    (is (= (->> (sut/build-doc
                 {:powerpack/config {:site/base-url "https://greetings.world"}}
                 {:open-graph/description "A greeting of worlds"
                  :open-graph/title "A few chosen words to greet the peoples of the world that have come here for kindness"
                  :page/uri "/hello-world/"}
                 [:h1 "Hello world"])
                get-open-graph-metas
                (drop 2)
                first
                second
                :content)
           "A few chosen words to greet the peoples of the world that have come h…")))

  (testing "Escapes title and description"
    (is (= (-> (sut/build-doc
                {:powerpack/config {:site/base-url "https://greetings.world"}}
                {:open-graph/description "A greeting of worlds & people"
                 :open-graph/title "Hello <world>"
                 :page/uri "/hello-world/"}
                [:h1 "Hello world"])
               get-open-graph-metas)
           [[:meta {:property "og:type", :content "article"}]
            [:meta {:property "og:description", :content "A greeting of worlds &amp; people"}]
            [:meta {:property "og:title", :content "Hello &lt;world&gt;"}]
            [:meta {:property "og:url", :content "https://greetings.world/hello-world/"}]])))

  (testing "Renders open graph image"
    (is (= (->> (sut/build-doc
                 {:powerpack/config {:site/base-url "https://greetings.world"}}
                 {:open-graph/title "Hello world"
                  :open-graph/image "/images/ducks.jpg"
                  :page/uri "/hello-world/"}
                 [:h1 "Hello world"])
                get-open-graph-metas
                (drop 3))
           [[:meta {:property "og:image", :content "/images/ducks.jpg"}]
            [:meta {:property "og:image:width", :content "640"}]
            [:meta {:property "og:image:height", :content "427"}]])))

  (testing "Renders open graph image with imagine filter"
    (is (= (->> (sut/build-doc
                 {:powerpack/config
                  {:site/base-url "https://greetings.world"
                   :imagine/config
                   {:prefix "image-assets"
                    :resource-path "public"
                    :disk-cache? true
                    :transformations
                    {:vcard-small
                     {:transformations [[:fit {:width 184 :height 184}]
                                        [:crop {:preset :square}]]
                      :retina-optimized? true
                      :retina-quality 0.4
                      :width 184}}}}}
                 {:open-graph/title "Hello world"
                  :open-graph/image "/vcard-small/images/ducks.jpg"
                  :page/uri "/hello-world/"}
                 [:h1 "Hello world"])
                get-open-graph-metas
                (drop 3))
           [[:meta {:property "og:image", :content "/vcard-small/images/ducks.jpg"}]
            [:meta {:property "og:image:width", :content "184"}]
            [:meta {:property "og:image:height", :content "184"}]]))))
