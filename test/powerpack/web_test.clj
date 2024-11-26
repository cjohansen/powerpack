(ns powerpack.web-test
  (:require [clojure.test :refer [deftest is testing]]
            [dev.onionpancakes.chassis.core :as chassis]
            [powerpack.app :as app]
            [powerpack.assets :as assets]
            [powerpack.hiccup :as hiccup]
            [powerpack.web :as sut]))

(deftest get-response-map-test
  (testing "Stringifies EDN data"
    (is (= (sut/get-response-map {:uri "/"} {} {:data 42})
           {:status 200
            :headers {"Content-Type" "application/edn"}
            :body "{:data 42}"})))

  (testing "Stringifies EDN body data"
    (is (= (sut/get-response-map
            {:uri "/"}
            {}
            {:status 200
             :headers {"Content-Type" "application/edn"}
             :body {:data 42}})
           {:status 200
            :headers {"Content-Type" "application/edn"}
            :body "{:data 42}"})))

  (testing "Returns HTML"
    (is (= (sut/get-response-map {:uri "/"} {} "<h1>Hello world</h1>")
           {:status 200
            :headers {"Content-Type" "text/html"}
            :body "<h1>Hello world</h1>"})))

  (testing "Returns JSON"
    (is (= (sut/get-response-map
            {:uri "/"}
            {}
            {:body {:data 42}
             :content-type :json})
           {:status 200
            :headers {"Content-Type" "application/json"}
            :body "{\"data\":42}"})))

  (testing "Returns body JSON"
    (is (= (sut/get-response-map
            {:uri "/"}
            {}
            {:body {:data 42}
             :headers {"Content-Type" "application/json"}})
           {:status 200
            :headers {"Content-Type" "application/json"}
            :body "{\"data\":42}"})))

  (testing "Returns hiccup as HTML"
    (is (= (sut/get-response-map
            {:uri "/"}
            {}
            [:body [:h1 "Hello world"]])
           {:status 200
            :headers {"Content-Type" "text/html"}
            :body "<body><h1>Hello world</h1></body>"})))

  (testing "Returns hiccup body as HTML"
    (is (= (sut/get-response-map
            {:uri "/"}
            {}
            {:body [:body [:h1 "Hello world"]]})
           {:status 200
            :headers {"Content-Type" "text/html"}
            :body "<body><h1>Hello world</h1></body>"})))

  (testing "Returns map with string body as html"
    (is (= (sut/get-response-map
            {:uri "/"}
            {}
            {:status 200
             :body "<h1>Hello</h1>"})
           {:status 200
            :body "<h1>Hello</h1>"
            :headers {"Content-Type" "text/html"}})))

  (testing "Does not treat XML as HTML"
    (is (= (sut/get-response-map
            {:uri "/atom.xml"}
            {}
            {:status 200
             :body "<h1>Hello</h1>"})
           {:status 200
            :body "<h1>Hello</h1>"
            :headers {"Content-Type" "text/xml"}}))))

(defn qsa [hiccup tag]
  (set (hiccup/get-tags hiccup tag)))

(deftest hiccup-documents
  (testing "Returns hiccup document as HTML"
    (is (= (sut/get-response-map
            {:uri "/"}
            {}
            {:body [:html [:body [:h1 "Hello world"]]]})
           {:status 200
            :headers {"Content-Type" "text/html"}
            :body (str "<!DOCTYPE html>"
                       "<html prefix=\"og: http://ogp.me/ns#\">"
                       "<head>"
                       "<meta charset=\"utf-8\">"
                       "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">"
                       "</head>"
                       "<body>"
                       "<h1>Hello world</h1>"
                       "</body>"
                       "</html>")})))

  (testing "Adorns html element with locale"
    (is (= (-> (sut/embellish-hiccup
                {:uri "/"}
                {:page/locale :nb}
                [:html [:body [:h1 "Hello world"]]])
               second)
           {:lang "nb"
            :prefix "og: http://ogp.me/ns#"})))

  (testing "Finds html element even with class"
    (is (= (-> (sut/embellish-hiccup
                {:uri "/"}
                {:page/locale :nb}
                [:html.mmm [:body [:h1 "Hello world"]]])
               second)
           {:lang "nb"
            :prefix "og: http://ogp.me/ns#"})))

  (testing "Uses existing charset meta"
    (is (= (-> (sut/embellish-hiccup
                {:uri "/"}
                {:page/locale :nb}
                [:html
                 [:head [:meta {:charset "latin-1"}]]
                 [:body [:h1 "Hello world"]]])
               (qsa :meta))
           #{[:meta {:charset "latin-1"}]
             [:meta {:name "viewport"
                     :content "width=device-width, initial-scale=1.0"}]})))

  (testing "Uses existing viewport meta"
    (is (= (-> (sut/embellish-hiccup
                {:uri "/"}
                {}
                [:html
                 [:head [:meta {:name "viewport"
                                :content "width=100%"}]]])
               (qsa :meta))
           #{[:meta {:charset "utf-8"}]
             [:meta {:name "viewport"
                     :content "width=100%"}]})))

  (testing "Adds head element with title"
    (is (= (-> (sut/embellish-hiccup
                {:uri "/"}
                {:page/title "Hello world"}
                [:html [:body [:h1 "Hello world"]]])
               (qsa :head)
               (qsa :title))
           #{[:title "Hello world"]})))

  (testing "Does not override existing title"
    (is (= (-> (sut/embellish-hiccup
                {:uri "/"}
                {:page/title "Something else"}
                [:html
                 [:head [:title "Hello world"]]
                 [:body [:h1 "Hello world"]]])
               (qsa :title))
           #{[:title "Hello world"]})))

  (testing "Adds open graph meta tags"
    (is (= (-> (sut/embellish-hiccup
                {:uri "/"
                 :powerpack/app {:site/base-url "https://greetings.world"}}
                {:open-graph/description "A greeting of worlds"
                 :open-graph/title "Hello!"
                 :page/uri "/hello-world/"}
                [:html [:body [:h1 "Hello world"]]])
               (qsa :meta))
           #{[:meta {:charset "utf-8"}]
             [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
             [:meta {:property "og:url" :content "https://greetings.world/hello-world/"}]
             [:meta {:property "og:description" :content "A greeting of worlds"}]
             [:meta {:property "og:title" :content "Hello!"}]})))

  (testing "Keeps existing open graph tags"
    (is (= (-> (sut/embellish-hiccup
                {:uri "/"
                 :powerpack/app {:site/base-url "https://site"}}
                {:open-graph/description "Hello"
                 :open-graph/title "Yes"
                 :page/uri "/hello-world/"}
                [:html
                 [:head
                  [:meta {:property "og:url" :content "https://greetings.world/hello-world/"}]
                  [:meta {:property "og:description" :content "A greeting of worlds"}]
                  [:meta {:property "og:title" :content "Hello!"}]]
                 [:body [:h1 "Hello world"]]])
               (qsa :meta))
           #{[:meta {:charset "utf-8"}]
             [:meta {:name "viewport" :content "width=device-width, initial-scale=1.0"}]
             [:meta {:property "og:url" :content "https://greetings.world/hello-world/"}]
             [:meta {:property "og:description" :content "A greeting of worlds"}]
             [:meta {:property "og:title" :content "Hello!"}]})))

  (testing "Links to favicon"
    (is (= (-> (sut/embellish-hiccup
                {:uri "/"
                 :optimus-assets [{:path "/favicon.ico"}]}
                {}
                [:html [:body [:h1 "Hello world"]]])
               (qsa :link))
           #{[:link {:href "/favicon.ico" :rel "shortcut icon" :type "image/vnd.microsoft.icon"}]
             [:link {:href "/favicon.ico" :rel "icon" :type "image/x-icon"}]
             [:link {:href "/favicon.ico" :rel "shortcut icon" :type "image/ico"}]
             [:link {:href "/favicon.ico" :rel "shortcut icon" :type "image/x-icon"}]})))

  (testing "Links to fancy favicons"
    (is (= (-> (sut/embellish-hiccup
                {:uri "/"
                 :optimus-assets [{:path "/favicon-16x16.png"}
                                  {:path "/favicon-32x32.png"}
                                  {:path "/apple-touch-icon.png"}]}
                {}
                [:html [:body [:h1 "Hello world"]]])
               (qsa :link))
           #{[:link {:href "/favicon-32x32.png" :rel "icon" :type "image/png" :sizes "32x32"}]
             [:link {:href "/favicon-16x16.png" :rel "icon" :type "image/png" :sizes "16x16"}]
             [:link {:href "/apple-touch-icon.png" :rel "icon" :type "image/png" :sizes "180x180"}]})))

  (testing "Does not add extra favicon links"
    (is (= (-> (sut/embellish-hiccup
                {:uri "/"
                 :optimus-assets [{:path "/favicon.ico"}]}
                {}
                [:html
                 [:head [:link {:href "/favicon.ico" :rel "icon" :type "image/x-icon"}]]
                 [:body [:h1 "Hello world"]]])
               (qsa :link))
           #{[:link {:href "/favicon.ico" :rel "icon" :type "image/x-icon"}]})))

  (testing "Adds link tags for CSS bundles"
    (is (= (-> (sut/embellish-hiccup
                {:uri "/"
                 :optimus-assets [{:path "/source1.css"
                                   :bundle "/styles.css"}
                                  {:path "/source2.css"
                                   :bundle "/styles.css"}]}
                {}
                [:html [:body [:h1 "Hello world"]]])
               (qsa :link))
           #{[:link {:rel "stylesheet" :href "/source1.css"}]
             [:link {:rel "stylesheet" :href "/source2.css"}]})))

  (testing "Adds script tags for JS bundles"
    (is (= (-> (sut/embellish-hiccup
                {:uri "/"
                 :optimus-assets [{:path "/source1.js"
                                   :bundle "/app.js"}
                                  {:path "/source2.js"
                                   :bundle "/app.js"}]}
                {}
                [:html [:body]])
               (qsa :body))
           #{[:body
              [:script {:type "text/javascript" :src "/source1.js"}]
              [:script {:type "text/javascript" :src "/source2.js"}]]})))

  (testing "Translates content with m1p"
    (is (= (-> (sut/embellish-hiccup
                {:uri "/"
                 :i18n/dictionaries {:nb {::greeting "Hei, verden"}
                                     :en {::greeting "Hello world"}}
                 :powerpack/app {:m1p/k :i18n}}
                {:page/locale :nb}
                [:html [:body [:h1 [:i18n ::greeting]]]])
               last)
           [:body [:h1 "Hei, verden"]])))

  (testing "Reports missing i18n keys"
    (is (= (-> (sut/embellish-hiccup
                {:uri "/"
                 :i18n/dictionaries {:nb {}
                                     :en {}}
                 :powerpack/app {:m1p/k :i18n}}
                {:page/locale :nb}
                [:html [:body [:h1 [:i18n ::greeting]]]])
               last)
           [:body
            [:h1
             [:pre "Unknown i18n key :powerpack.web-test/greeting for locale :nb"]]])))

  (testing "Reports missing dictionary for locale"
    (is (= (-> (sut/embellish-hiccup
                {:uri "/"
                 :i18n/dictionaries {:nb {}}
                 :powerpack/app {:m1p/k :i18n}}
                {:page/locale :en}
                [:html [:body [:h1 [:i18n ::greeting]]]])
               last)
           [:body
            [:h1
             [:pre "Can't look up i18n key :powerpack.web-test/greeting, no :en dictionary"]]]))))

(deftest prepare-response-test
  (testing "Renders an HTML body for redirects"
    (is (= (->> {:status 302
                 :headers {"Location" "/elsewhere/"}}
                (sut/prepare-response {} {}))
           {:status 302
            :headers {"Location" "/elsewhere/"
                      "Content-Type" "text/html"}
            :body "<!DOCTYPE html><html><head><meta http-equiv=\"refresh\" content=\"0; url=/elsewhere/\"></head><body><a href=\"/elsewhere/\">Redirect</a></body></html>"})))

  (testing "Understands raw chassis body"
    (is (= (->> {:body (chassis/raw "<h1>Hello!</h1>")}
                (sut/prepare-response {} {}))
           {:body "<h1>Hello!</h1>"}))))

(deftest post-process-test
  (testing "Optimizes anchor href"
    (is (= (-> (sut/post-process-page
                {:headers {"content-type" "text/html"}
                 :body "<!DOCTYPE html><html><body><a href=\"/images/ducks.jpg\">Ducks</a></body></html>"}
                {:powerpack/app {:powerpack/asset-targets app/default-asset-targets}
                 :optimus-assets [{:original-path "/images/ducks.jpg"
                                   :path "/sproing/xyz.jpg"}]}
                [assets/get-markup-url-optimizers])
               :body)
           "<!DOCTYPE html><html><head></head><body><a href=\"/sproing/xyz.jpg\">Ducks</a></body></html>")))

  (testing "Accepts pre-optimized script sources"
    (is (= (-> (sut/post-process-page
                {:headers {"content-type" "text/html"}
                 :body "<html><body><script src=\"/scripts/d4dee5ef2aa4/app.js\"></script></body></html>"}
                {:powerpack/app {:powerpack/asset-targets app/default-asset-targets}
                 :optimus-assets [{:path "/scripts/app.js"
                                   :contents "console.log(\"Hello world!\");\n"
                                   :bundle "ducks.js"
                                   :outdated true}
                                  {:path "/scripts/d4dee5ef2aa4/app.js"
                                   :contents "console.log(\"Hello world!\");\n"
                                   :bundle "ducks.js"
                                   :original-path "/scripts/app.js"}]}
                [assets/get-markup-url-optimizers])
               :body)
           "<html><head></head><body><script src=\"/scripts/d4dee5ef2aa4/app.js\"></script></body></html>")))

  (testing "Does not optimize external scripts"
    (is (= (-> (sut/post-process-page
                {:headers {"content-type" "text/html"}
                 :body "<html><body><script src=\"https://cdn.com/app.js\"></script></body></html>"}
                {:powerpack/app {:powerpack/asset-targets app/default-asset-targets}
                 :optimus-assets [{:original-path "/app.js"
                                   :path "/xxxxx.js"}]}
                [assets/get-markup-url-optimizers])
               :body)
           "<html><head></head><body><script src=\"https://cdn.com/app.js\"></script></body></html>")))

  (testing "Does not trip on href-less anchors"
    (is (= (-> (sut/post-process-page
                {:headers {"content-type" "text/html"}
                 :body "<html><body><a>No ducks</a></body></html>"}
                {:powerpack/app {:powerpack/asset-targets app/default-asset-targets}
                 :optimus-assets [{:original-path "/images/ducks.jpg"
                                   :path "/sproing/xyz.jpg"}]}
                [assets/get-markup-url-optimizers])
               :body)
           "<html><head></head><body><a>No ducks</a></body></html>")))

  (testing "Does not trip on url-less hrefs on anchors"
    (is (= (-> (sut/post-process-page
                {:headers {"content-type" "text/html"}
                 :body "<html><body><a href=\"#\">No ducks</a></body></html>"}
                {:powerpack/app {:powerpack/asset-targets app/default-asset-targets}
                 :optimus-assets [{:original-path "/images/ducks.jpg"
                                   :path "/sproing/xyz.jpg"}]}
                [assets/get-markup-url-optimizers])
               :body)
           "<html><head></head><body><a href=\"#\">No ducks</a></body></html>")))

  (testing "Does not attempt to optimize fully qualified URLs"
    (is (= (-> (sut/post-process-page
                {:headers {"content-type" "text/html"}
                 :body "<html><body><img src=\"https://example.com/\"></body></html>"}
                {:powerpack/app {:powerpack/asset-targets app/default-asset-targets}
                 :optimus-assets []}
                [assets/get-markup-url-optimizers])
               :body)
           "<html><head></head><body><img src=\"https://example.com/\"></body></html>"))))
