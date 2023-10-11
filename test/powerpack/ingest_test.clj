(ns powerpack.ingest-test
  (:require [clojure.core.async :refer [chan close!]]
            [clojure.test :refer [deftest is testing]]
            [datomic-type-extensions.api :as d]
            [powerpack.db :as db]
            [powerpack.ingest :as sut]))

(defmacro with-test-system [schema binding & body]
  `(do
     (let [uri# (str "datomic:mem://" (random-uuid))]
       (db/create-database uri# ~schema)
       (let [conn# (d/connect uri#)
             errors# (chan)
             ~binding {:conn conn#
                       :error-events {:ch errors#}}]
         (try
           ~@body
           (finally
             (close! errors#)
             (d/delete-database uri#)))))))

(defmacro with-schema-db [schema binding & body]
  `(do
     (let [uri# (str "datomic:mem://" (random-uuid))]
       (d/create-database uri#)
       (let [conn# (d/connect uri#)
             _# @(d/transact conn# ~schema)
             ~binding (d/db conn#)]
         (try
           ~@body
           (finally
             (d/delete-database uri#)))))))

(deftest align-with-schema
  (testing "Parses numbers from strings"
    (is (= (with-schema-db [{:db/ident :person/age
                             :db/valueType :db.type/bigdec
                             :db/cardinality :db.cardinality/one}]
             db
             (sut/align-with-schema {:person/age "1.0M"} db))
           {:person/age 1.0M}))

    (is (= (with-schema-db [{:db/ident :person/age
                             :db/valueType :db.type/bigint
                             :db/cardinality :db.cardinality/one}]
             db
             (sut/align-with-schema {:person/age "7N"} db))
           {:person/age 7N}))

    (is (= (-> (with-schema-db [{:db/ident :person/age
                                 :db/valueType :db.type/double
                                 :db/cardinality :db.cardinality/one}]
                 db
                 (sut/align-with-schema {:person/age "3.2"} db))
               :person/age
               type)
           java.lang.Double))

    (is (= (->> (with-schema-db [{:db/ident :person/ages
                                  :db/valueType :db.type/double
                                  :db/cardinality :db.cardinality/many}]
                  db
                  (sut/align-with-schema {:person/ages "[3.2 1.2]"} db))
                :person/ages
                (map type))
           [java.lang.Double
            java.lang.Double]))

    (is (= (-> (with-schema-db [{:db/ident :person/age
                                 :db/valueType :db.type/float
                                 :db/cardinality :db.cardinality/one}]
                 db
                 (sut/align-with-schema {:person/age "3.2"} db))
               :person/age
               type)
           java.lang.Float))

    (is (= (->> (with-schema-db [{:db/ident :person/ages
                                  :db/valueType :db.type/float
                                  :db/cardinality :db.cardinality/many}]
                  db
                  (sut/align-with-schema {:person/ages "[3.2 1.2]"} db))
                :person/ages
                (map type))
           [java.lang.Float
            java.lang.Float]))

    (is (= (-> (with-schema-db [{:db/ident :person/age
                                 :db/valueType :db.type/long
                                 :db/cardinality :db.cardinality/one}]
                 db
                 (sut/align-with-schema {:person/age "3"} db))
               :person/age
               type)
           java.lang.Long))

    (is (= (->> (with-schema-db [{:db/ident :person/ages
                                  :db/valueType :db.type/long
                                  :db/cardinality :db.cardinality/one}]
                  db
                  (sut/align-with-schema {:person/ages "[3 2]"} db))
                :person/ages
                (map type))
           [java.lang.Long
            java.lang.Long])))

  (testing "Parses boolean"
    (is (= (with-schema-db [{:db/ident :person/of-age?
                             :db/valueType :db.type/boolean
                             :db/cardinality :db.cardinality/one}]
             db
             (sut/align-with-schema {:person/of-age? "true"} db))
           {:person/of-age? true})))

  (testing "Parses instant"
    (is (= (with-schema-db [{:db/ident :person/date-of-birth
                             :db/valueType :db.type/instant
                             :db/cardinality :db.cardinality/one}]
             db
             (sut/align-with-schema {:person/date-of-birth "#inst \"2017-09-16T11:43:32.450-00:00\""} db))
           {:person/date-of-birth #inst "2017-09-16T11:43:32.450-00:00"})))

  (testing "Parses keyword"
    (is (= (with-schema-db [{:db/ident :person/ident
                             :db/valueType :db.type/keyword
                             :db/cardinality :db.cardinality/one}]
             db
             (sut/align-with-schema {:person/ident ":person/christian"} db))
           {:person/ident :person/christian})))

  (testing "Parses many keywords"
    (is (= (with-schema-db [{:db/ident :person/idents
                             :db/valueType :db.type/keyword
                             :db/cardinality :db.cardinality/many}]
             db
             (sut/align-with-schema {:person/idents "[:person/christian :person/chris]"} db))
           {:person/idents [:person/christian :person/chris]})))

  (testing "Parses symbol"
    (is (= (with-schema-db [{:db/ident :person/nickname
                             :db/valueType :db.type/symbol
                             :db/cardinality :db.cardinality/one}]
             db
             (sut/align-with-schema {:person/nickname "chris"} db))
           {:person/nickname 'chris})))

  (testing "Parses tuple"
    (is (= (with-schema-db [{:db/ident :person/nicknames
                             :db/valueType :db.type/tuple
                             :db/tupleTypes [:db.type/string :db.type/string]
                             :db/cardinality :db.cardinality/one}]
             db
             (sut/align-with-schema {:person/nicknames "[\"chris\" \"man\"]"} db))
           {:person/nicknames ["chris" "man"]})))

  (testing "Parses uuid"
    (is (= (with-schema-db [{:db/ident :person/id
                             :db/valueType :db.type/uuid
                             :db/cardinality :db.cardinality/one}]
             db
             (sut/align-with-schema {:person/id "#uuid \"40af375b-0fed-44fb-a60c-98d9c3d0e851\""} db))
           {:person/id #uuid "40af375b-0fed-44fb-a60c-98d9c3d0e851"})))

  (testing "Parses URI"
    (is (= (with-schema-db [{:db/ident :person/url
                             :db/valueType :db.type/uri
                             :db/cardinality :db.cardinality/one}]
             db
             (sut/align-with-schema {:person/url "https://cjohansen.no"} db))
           {:person/url (java.net.URI/create "https://cjohansen.no")})))

  (testing "Parses many URIs"
    (is (= (with-schema-db [{:db/ident :person/urls
                             :db/valueType :db.type/uri
                             :db/cardinality :db.cardinality/many}]
             db
             (sut/align-with-schema {:person/urls "[\"https://cjohansen.no\" \"https://mattilsynet.no\"]"} db))
           {:person/urls [(java.net.URI/create "https://cjohansen.no")
                          (java.net.URI/create "https://mattilsynet.no")]})))

  (testing "Parses ref"
    (is (= (with-schema-db [{:db/ident :person/id
                             :db/valueType :db.type/uuid
                             :db/cardinality :db.cardinality/one
                             :db/unique :db.unique/identity}
                            {:db/ident :person/friend
                             :db/valueType :db.type/ref
                             :db/cardinality :db.cardinality/one}]
             db
             (sut/align-with-schema {:person/friend "{:person/id #uuid \"40af375b-0fed-44fb-a60c-98d9c3d0e851\"}"} db))
           {:person/friend {:person/id #uuid "40af375b-0fed-44fb-a60c-98d9c3d0e851"}})))

  (testing "Parses multiple refs"
    (is (= (with-schema-db [{:db/ident :person/id
                             :db/valueType :db.type/string
                             :db/cardinality :db.cardinality/one
                             :db/unique :db.unique/identity}
                            {:db/ident :person/friends
                             :db/valueType :db.type/ref
                             :db/cardinality :db.cardinality/many}]
             db
             (sut/align-with-schema {:person/friends "[{:person/id \"p1\"} {:person/id \"p2\"}]"} db))
           {:person/friends [{:person/id "p1"}
                             {:person/id "p2"}]})))

  (testing "Parses java-time literals"
    (is (= (with-schema-db [{:db/ident :person/birthday
                             :dte/valueType :java.time/local-date
                             :db/cardinality :db.cardinality/one}]
             db
             (sut/align-with-schema {:person/birthday "#time/ld \"2007-12-03\""} db))
           {:person/birthday #time/ld "2007-12-03"}))))

(defn mapify [e]
  (into {} e))

(deftest ingest-data-test
  (testing "Ingests data from file"
    (is (= (-> (with-test-system {} system
                 (->> [{:page/uri "/some-file/"
                        :page/title "Hello"}]
                      (sut/ingest-data system "some-file.md")))
               :db-after
               (d/entity [:page/uri "/some-file/"])
               mapify)
           {:page/uri "/some-file/", :page/title "Hello"})))

  (testing "Does not retract data before attempting failing ingest"
    (is (= (with-test-system {} system
             (->> [{:page/uri "/some-file/"
                    :page/title "Hello"}]
                  (sut/ingest-data system "some-file.md"))
             (try
               (->> [{:bogus/wow "Boom"}]
                    (sut/ingest-data system "some-file.md"))
               (catch Exception _e nil))
             (mapify (d/entity (d/db (:conn system)) [:page/uri "/some-file/"])))
           {:page/uri "/some-file/", :page/title "Hello"})))

  (testing "Retracts datomic type extension attrs"
    (is (= (with-test-system [{:db/ident :blog-post/published
                               :dte/valueType :java.time/local-date-time
                               :db/cardinality :db.cardinality/one}] system
             (->> [{:page/uri "/blog/post/"
                    :blog-post/published #time/ldt "2023-09-30T09:00"}]
                  (sut/ingest-data system "post.md"))
             (->> [{:page/uri "/blog/post/"
                    :blog-post/published #time/ldt "2023-09-29T09:00"}]
                  (sut/ingest-data system "post.md"))
             (mapify (d/entity (d/db (:conn system)) [:page/uri "/blog/post/"])))
           {:page/uri "/blog/post/"
            :blog-post/published #time/ldt "2023-09-29T09:00"})))

  (testing "Retracts ref attrs"
    (is (= (with-test-system [{:db/ident :blog-post/author
                               :db/valueType :db.type/ref
                               :db/cardinality :db.cardinality/one}
                              {:db/ident :person/id
                               :db/valueType :db.type/string
                               :db/cardinality :db.cardinality/one
                               :db/unique :db.unique/identity}] system
             (->> [{:page/uri "/blog/post/"
                    :blog-post/author {:person/id "person1"}}]
                  (sut/ingest-data system "post.md"))
             (->> [{:page/uri "/blog/post/"
                    :blog-post/author {:person/id "person1"}}]
                  (sut/ingest-data system "post.md"))
             (:page/uri (d/entity (d/db (:conn system)) [:page/uri "/blog/post/"])))
           "/blog/post/"))))
