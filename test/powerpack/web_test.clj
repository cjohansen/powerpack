(ns powerpack.web-test
  (:require [clojure.test :refer [deftest is testing]]
            [powerpack.web :as sut]))

(deftest get-response-map-test
  (testing "Stringifies EDN data"
    (is (= (sut/get-response-map {:uri "/"} {:data 42})
           {:status 200
            :headers {"Content-Type" "application/edn"}
            :body "{:data 42}"})))

  (testing "Stringifies EDN body data"
    (is (= (sut/get-response-map
            {:uri "/"}
            {:status 200
             :headers {"Content-Type" "application/edn"}
             :body {:data 42}})
           {:status 200
            :headers {"Content-Type" "application/edn"}
            :body "{:data 42}"})))

  (testing "Returns HTML"
    (is (= (sut/get-response-map {:uri "/"} "<h1>Hello world</h1>")
           {:status 200
            :headers {"Content-Type" "text/html"}
            :body "<h1>Hello world</h1>"})))

  (testing "Returns JSON"
    (is (= (sut/get-response-map
            {:uri "/"}
            {:body {:data 42}
             :content-type :json})
           {:status 200
            :headers {"Content-Type" "application/json"}
            :body "{\"data\":42}"})))

  (testing "Returns body JSON"
    (is (= (sut/get-response-map
            {:uri "/"}
            {:body {:data 42}
             :headers {"Content-Type" "application/json"}})
           {:status 200
            :headers {"Content-Type" "application/json"}
            :body "{\"data\":42}"})))

  (testing "Returns hiccup as HTML"
    (is (= (sut/get-response-map
            {:uri "/"}
            [:body [:h1 "Hello world"]])
           {:status 200
            :headers {"Content-Type" "text/html"}
            :body "<body><h1>Hello world</h1></body>"})))

  (testing "Returns hiccup body as HTML"
    (is (= (sut/get-response-map
            {:uri "/"}
            {:body [:body [:h1 "Hello world"]]})
           {:status 200
            :headers {"Content-Type" "text/html"}
            :body "<body><h1>Hello world</h1></body>"})))

  (testing "Returns map with string body as html"
    (is (= (sut/get-response-map
            {:uri "/"}
            {:status 200
             :body "<h1>Hello</h1>"})
           {:status 200,
            :body "<h1>Hello</h1>",
            :headers {"Content-Type" "text/html"}}))))

(deftest hiccup-documents
  (testing "Returns hiccup document as HTML"
    (is (= (sut/get-response-map
            {:uri "/"}
            {:body [:html [:body [:h1 "Hello world"]]]})
           {:status 200
            :headers {"Content-Type" "text/html"}
            :body "<!DOCTYPE html><html><body><h1>Hello world</h1></body></html>"}))))
