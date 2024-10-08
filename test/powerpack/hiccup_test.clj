(ns powerpack.hiccup-test
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [powerpack.hiccup :as sut]))

(defn get-open-graph-metas [html]
  (->> (tree-seq coll? identity html)
       (filter vector?)
       (filter (comp #{:meta} first))
       (filter (comp :property second))
       (filter #(str/starts-with? (:property (second %)) "og:"))))

(deftest build-doc
  (testing "Renders a reasonable document"
    (is (= (sut/build-doc {} {} [:h1 "Hello world"])
           [:html {:prefix "og: http://ogp.me/ns#"}
            [:head
             [:meta {:charset "utf-8"}]
             [:meta {:name "viewport", :content "width=device-width, initial-scale=1.0"}]]
            [:h1 "Hello world"]])))

  (testing "Renders link and script tags"
    (is (= (sut/build-doc {:optimus-assets [{:path "/source1.css"
                                             :bundle "/styles.css"}
                                            {:path "/source2.css"
                                             :bundle "/styles.css"}
                                            {:path "/source1.js"
                                             :bundle "/app.js"}
                                            {:path "/source2.js"
                                             :bundle "/app.js"}]}
                          {} [:h1 "Hello world"])
           [:html {:prefix "og: http://ogp.me/ns#"}
            [:head
             [:meta {:charset "utf-8"}]
             [:meta {:name "viewport", :content "width=device-width, initial-scale=1.0"}]
             [:link {:href "/source1.css", :rel "stylesheet"}]
             [:link {:href "/source2.css", :rel "stylesheet"}]]
            [:h1 "Hello world"]
            [:script {:type "text/javascript", :src "/source1.js"}]
            [:script {:type "text/javascript", :src "/source2.js"}]])))

  (testing "Indicates original paths for non-concatenated bundles when live reload is active"
    (is (= (->> (sut/build-doc
                 {:powerpack/live-reload? true
                  :optimus-assets [{:path "/source1.css"
                                    :bundle "/styles.css"
                                    :outdated true}
                                   {:path "/source2.css"
                                    :bundle "/styles.css"
                                    :outdated true}
                                   {:path "/styles/398036080389/source1.css"
                                    :original-path "/source1.css"
                                    :bundle "/styles.css"}
                                   {:path "/styles/b8936d0bc201/source2.css"
                                    :original-path "/source2.css"
                                    :bundle "/styles.css"}]}
                 {} [:h1 "Hello world"])
                (tree-seq coll? identity)
                (filter vector?)
                (filter (comp #{:link} first)))
           [[:link {:rel "stylesheet", :href "/styles/398036080389/source1.css", :path "/source1.css"}]
            [:link {:rel "stylesheet", :href "/styles/b8936d0bc201/source2.css", :path "/source2.css"}]])))

  (testing "Does not indicates original paths when live reload is not active"
    (is (= (->> (sut/build-doc
                 {:optimus-assets [{:path "/source1.css"
                                    :bundle "/styles.css"
                                    :outdated true}
                                   {:path "/source2.css"
                                    :bundle "/styles.css"
                                    :outdated true}
                                   {:path "/styles/398036080389/source1.css"
                                    :original-path "/source1.css"
                                    :bundle "/styles.css"}
                                   {:path "/styles/b8936d0bc201/source2.css"
                                    :original-path "/source2.css"
                                    :bundle "/styles.css"}]}
                 {} [:h1 "Hello world"])
                (tree-seq coll? identity)
                (filter vector?)
                (filter (comp #{:link} first)))
           [[:link {:rel "stylesheet", :href "/styles/398036080389/source1.css"}]
            [:link {:rel "stylesheet", :href "/styles/b8936d0bc201/source2.css"}]])))

  (testing "Includes lang attribute with default lang"
    (is (= (-> (sut/build-doc {:powerpack/app {:site/default-locale :nb}} {} [:h1 "Hello world"])
               second
               :lang)
           "nb")))

  (testing "Includes page language when available"
    (is (= (-> (sut/build-doc
                {:powerpack/app {:site/default-locale :nb}}
                {:page/locale :en}
                [:h1 "Hello world"])
               second
               :lang)
           "en")))

  (testing "Includes open graph meta tags"
    (is (= (-> (sut/build-doc
                {:powerpack/app {:site/base-url "https://greetings.world"}}
                {:open-graph/description "A greeting of worlds"
                 :open-graph/title "Hello!"
                 :page/uri "/hello-world/"}
                [:h1 "Hello world"])
               get-open-graph-metas)
           [[:meta {:property "og:description", :content "A greeting of worlds"}]
            [:meta {:property "og:title", :content "Hello!"}]
            [:meta {:property "og:url", :content "https://greetings.world/hello-world/"}]])))

  (testing "Truncates open graph description"
    (is (= (-> (sut/build-doc
                {:powerpack/app {:site/base-url "https://greetings.world"}}
                {:open-graph/description "A greeting of worlds that is much too long to have any hope of appearing in full on the relevant social media platforms that one typically authors open graph descriptions for - and thus, is shortened somewhat brutally."
                 :open-graph/title "Hello!"
                 :page/uri "/hello-world/"}
                [:h1 "Hello world"])
               get-open-graph-metas
               first
               second
               :content)
           (str "A greeting of worlds that is much too long to have any hope "
                "of appearing in full on the relevant social media platforms "
                "that one typically authors open graph descriptions for - and "
                "thus, is shortened…"))))

  (testing "Truncates open graph title"
    (is (= (->> (sut/build-doc
                 {:powerpack/app {:site/base-url "https://greetings.world"}}
                 {:open-graph/description "A greeting of worlds"
                  :open-graph/title "A few chosen words to greet the peoples of the world that have come here for kindness"
                  :page/uri "/hello-world/"}
                 [:h1 "Hello world"])
                get-open-graph-metas
                (drop 1)
                first
                second
                :content)
           "A few chosen words to greet the peoples of the world that have come h…")))

  (testing "Escapes title and description"
    (is (= (-> (sut/build-doc
                {:powerpack/app {:site/base-url "https://greetings.world"}}
                {:open-graph/description "A greeting of worlds & people"
                 :open-graph/title "Hello <world>"
                 :page/uri "/hello-world/"}
                [:h1 "Hello world"])
               get-open-graph-metas)
           [[:meta {:property "og:description", :content "A greeting of worlds &amp; people"}]
            [:meta {:property "og:title", :content "Hello &lt;world&gt;"}]
            [:meta {:property "og:url", :content "https://greetings.world/hello-world/"}]])))

  (testing "Renders open graph image"
    (is (= (->> (sut/build-doc
                 {:powerpack/app {:site/base-url "https://greetings.world"}}
                 {:open-graph/title "Hello world"
                  :open-graph/image "/images/ducks.jpg"
                  :page/uri "/hello-world/"}
                 [:h1 "Hello world"])
                get-open-graph-metas
                (drop 2))
           [[:meta {:property "og:image", :content "/images/ducks.jpg"}]])))

  (testing "Renders open graph image with suggested dimensions"
    (is (= (->> (sut/build-doc
                 {:powerpack/app {:site/base-url "https://greetings.world"}}
                 {:open-graph/title "Hello world"
                  :open-graph/image "/images/ducks.jpg"
                  :open-graph/image-width 640
                  :open-graph/image-height 427
                  :page/uri "/hello-world/"}
                 [:h1 "Hello world"])
                get-open-graph-metas
                (drop 2))
           [[:meta {:property "og:image", :content "/images/ducks.jpg"}]
            [:meta {:property "og:image:width", :content "640"}]
            [:meta {:property "og:image:height", :content "427"}]])))

  (testing "Renders external open graph image"
    (is (= (->> (sut/build-doc
                 {:powerpack/app {:site/base-url "https://greetings.world"}}
                 {:open-graph/title "Hello world"
                  :open-graph/image "https://service/images/ducks.jpg"
                  :page/uri "/hello-world/"}
                 [:h1 "Hello world"])
                get-open-graph-metas
                (drop 2))
           [[:meta {:property "og:image", :content "https://service/images/ducks.jpg"}]])))

  (testing "Renders open graph image with imagine filter"
    (is (= (->> (sut/build-doc
                 {:powerpack/app
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
                (drop 2))
           [[:meta {:property "og:image", :content "/vcard-small/images/ducks.jpg"}]]))))

(deftest hiccup?-test
  (testing "Recognizes hiccup"
    (is (sut/hiccup? [:html]))
    (is (sut/hiccup? [:a {:href "#"} "Click"]))
    (is (sut/hiccup? [:h1 "Hello"]))
    (is (sut/hiccup? [:div {:style {:border "1px solid"}}
                      [:h1 "Hello"]]))
    (is (not (sut/hiccup? {:style {:border "1px solid"}})))
    (is (not (sut/hiccup? "1px solid")))
    (is (sut/hiccup? (list [:h1 "Hello"]
                           [:p "World"])))))

(deftest set-attribute-test
  (testing "Adds attribute map"
    (is (= (sut/set-attribute [:html] :lang "nb")
           [:html {:lang "nb"}])))

  (testing "Adds attribute map before children"
    (is (= (sut/set-attribute [:html [:body]] :lang "nb")
           [:html {:lang "nb"} [:body]])))

  (testing "Updates attribute map"
    (is (= (sut/set-attribute [:html {:prefix "og: http://ogp.me/ns#"} [:body]] :lang "nb")
           [:html {:prefix "og: http://ogp.me/ns#"
                   :lang "nb"} [:body]]))))

(deftest render-html-test
  (testing "Adds a DOCTYPE"
    (is (= (sut/render-html [:html [:h1 "Hello!"]])
           "<!DOCTYPE html><html><h1>Hello!</h1></html>")))

  (testing "Adds a DOCTYPE even when the HTML tag has a class"
    (is (= (sut/render-html [:html.mmm [:h1 "Hello!"]])
           "<!DOCTYPE html><html class=\"mmm\"><h1>Hello!</h1></html>"))))
