(ns powerpack.ingest-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [datomic-type-extensions.api :as d]
            [powerpack.db :as db]
            [powerpack.ingest :as sut]))

(defmacro with-test-powerpack
  {:clj-kondo/lint-as 'clojure.core/with-open}
  [binding & body]
  `(let [uri# (str "datomic:mem://" (random-uuid))]
     (db/create-database uri# ~(second binding))
     (let [conn# (d/connect uri#)
           ~(first binding) {:datomic/conn conn#}]
       (try
         ~@body
         (finally
           (d/delete-database uri#))))))

(defmacro with-schema-db [[binding schema] & body]
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
  (testing "Parses strings"
    (is (= (with-schema-db [db [{:db/ident :person/name
                                 :db/valueType :db.type/string
                                 :db/cardinality :db.cardinality/one}]]
             (sut/align-with-schema {:person/name "Christian"} db))
           {:person/name "Christian"})))

  (testing "Parses quoted strings"
    (is (= (with-schema-db [db [{:db/ident :person/name
                                 :db/valueType :db.type/string
                                 :db/cardinality :db.cardinality/one}]]
             (sut/align-with-schema {:person/name "\"Christian\""} db))
           {:person/name "Christian"})))

  (testing "Does not treat quotes inside strings as quoted strings"
    (is (= (with-schema-db [db [{:db/ident :person/name
                                 :db/valueType :db.type/string
                                 :db/cardinality :db.cardinality/one}]]
             (sut/align-with-schema {:person/name "\"Christian\" is a dude"} db))
           {:person/name "\"Christian\" is a dude"})))

  (testing "Allows multiple quoted sub-strings in strings"
    (is (= (with-schema-db [db [{:db/ident :person/name
                                 :db/valueType :db.type/string
                                 :db/cardinality :db.cardinality/one}]]
             (sut/align-with-schema {:person/name "\"Christian\" is a \"dude\""} db))
           {:person/name "\"Christian\" is a \"dude\""})))

  (testing "Parses numbers from strings"
    (is (= (with-schema-db [db [{:db/ident :person/age
                                 :db/valueType :db.type/bigdec
                                 :db/cardinality :db.cardinality/one}]]
             (sut/align-with-schema {:person/age "1.0M"} db))
           {:person/age 1.0M}))

    (is (= (with-schema-db [db [{:db/ident :person/age
                                 :db/valueType :db.type/bigint
                                 :db/cardinality :db.cardinality/one}]]
             (sut/align-with-schema {:person/age "7N"} db))
           {:person/age 7N}))

    (is (= (-> (with-schema-db [db [{:db/ident :person/age
                                     :db/valueType :db.type/double
                                     :db/cardinality :db.cardinality/one}]]
                 (sut/align-with-schema {:person/age "3.2"} db))
               :person/age
               type)
           java.lang.Double))

    (is (= (->> (with-schema-db [db [{:db/ident :person/ages
                                      :db/valueType :db.type/double
                                      :db/cardinality :db.cardinality/many}]]
                  (sut/align-with-schema {:person/ages "[3.2 1.2]"} db))
                :person/ages
                (map type))
           [java.lang.Double
            java.lang.Double]))

    (is (= (-> (with-schema-db [db [{:db/ident :person/age
                                     :db/valueType :db.type/float
                                     :db/cardinality :db.cardinality/one}]]
                 (sut/align-with-schema {:person/age "3.2"} db))
               :person/age
               type)
           java.lang.Float))

    (is (= (->> (with-schema-db [db [{:db/ident :person/ages
                                      :db/valueType :db.type/float
                                      :db/cardinality :db.cardinality/many}]]
                  (sut/align-with-schema {:person/ages "[3.2 1.2]"} db))
                :person/ages
                (map type))
           [java.lang.Float
            java.lang.Float]))

    (is (= (-> (with-schema-db [db [{:db/ident :person/age
                                     :db/valueType :db.type/long
                                     :db/cardinality :db.cardinality/one}]]
                 (sut/align-with-schema {:person/age "3"} db))
               :person/age
               type)
           java.lang.Long))

    (is (= (->> (with-schema-db [db [{:db/ident :person/ages
                                      :db/valueType :db.type/long
                                      :db/cardinality :db.cardinality/one}]]
                  (sut/align-with-schema {:person/ages "[3 2]"} db))
                :person/ages
                (map type))
           [java.lang.Long
            java.lang.Long])))

  (testing "Parses boolean"
    (is (= (with-schema-db [db [{:db/ident :person/of-age?
                                 :db/valueType :db.type/boolean
                                 :db/cardinality :db.cardinality/one}]]
             (sut/align-with-schema {:person/of-age? "true"} db))
           {:person/of-age? true})))

  (testing "Parses instant"
    (is (= (with-schema-db [db [{:db/ident :person/date-of-birth
                                 :db/valueType :db.type/instant
                                 :db/cardinality :db.cardinality/one}]]
             (sut/align-with-schema {:person/date-of-birth "#inst \"2017-09-16T11:43:32.450-00:00\""} db))
           {:person/date-of-birth #inst "2017-09-16T11:43:32.450-00:00"})))

  (testing "Parses keyword"
    (is (= (with-schema-db [db [{:db/ident :person/ident
                                 :db/valueType :db.type/keyword
                                 :db/cardinality :db.cardinality/one}]]
             (sut/align-with-schema {:person/ident ":person/christian"} db))
           {:person/ident :person/christian})))

  (testing "Parses many keywords"
    (is (= (with-schema-db [db [{:db/ident :person/idents
                                 :db/valueType :db.type/keyword
                                 :db/cardinality :db.cardinality/many}]]
             (sut/align-with-schema {:person/idents "[:person/christian :person/chris]"} db))
           {:person/idents [:person/christian :person/chris]})))

  (testing "Parses symbol"
    (is (= (with-schema-db [db [{:db/ident :person/nickname
                                 :db/valueType :db.type/symbol
                                 :db/cardinality :db.cardinality/one}]]
             (sut/align-with-schema {:person/nickname "chris"} db))
           {:person/nickname 'chris})))

  (testing "Parses tuple"
    (is (= (with-schema-db [db [{:db/ident :person/nicknames
                                 :db/valueType :db.type/tuple
                                 :db/tupleTypes [:db.type/string :db.type/string]
                                 :db/cardinality :db.cardinality/one}]]
             (sut/align-with-schema {:person/nicknames "[\"chris\" \"man\"]"} db))
           {:person/nicknames ["chris" "man"]})))

  (testing "Parses uuid"
    (is (= (with-schema-db [db [{:db/ident :person/id
                                 :db/valueType :db.type/uuid
                                 :db/cardinality :db.cardinality/one}]]
             (sut/align-with-schema {:person/id "#uuid \"40af375b-0fed-44fb-a60c-98d9c3d0e851\""} db))
           {:person/id #uuid "40af375b-0fed-44fb-a60c-98d9c3d0e851"})))

  (testing "Parses URI"
    (is (= (with-schema-db [db [{:db/ident :person/url
                                 :db/valueType :db.type/uri
                                 :db/cardinality :db.cardinality/one}]]
             (sut/align-with-schema {:person/url "https://cjohansen.no"} db))
           {:person/url (java.net.URI/create "https://cjohansen.no")})))

  (testing "Parses many URIs"
    (is (= (with-schema-db [db [{:db/ident :person/urls
                                 :db/valueType :db.type/uri
                                 :db/cardinality :db.cardinality/many}]]
             (sut/align-with-schema {:person/urls "[\"https://cjohansen.no\" \"https://mattilsynet.no\"]"} db))
           {:person/urls [(java.net.URI/create "https://cjohansen.no")
                          (java.net.URI/create "https://mattilsynet.no")]})))

  (testing "Parses ref"
    (is (= (with-schema-db [db [{:db/ident :person/id
                                 :db/valueType :db.type/uuid
                                 :db/cardinality :db.cardinality/one
                                 :db/unique :db.unique/identity}
                                {:db/ident :person/friend
                                 :db/valueType :db.type/ref
                                 :db/cardinality :db.cardinality/one}]]
             (sut/align-with-schema {:person/friend "{:person/id #uuid \"40af375b-0fed-44fb-a60c-98d9c3d0e851\"}"} db))
           {:person/friend {:person/id #uuid "40af375b-0fed-44fb-a60c-98d9c3d0e851"}})))

  (testing "Parses multiple refs"
    (is (= (with-schema-db [db [{:db/ident :person/id
                                 :db/valueType :db.type/string
                                 :db/cardinality :db.cardinality/one
                                 :db/unique :db.unique/identity}
                                {:db/ident :person/friends
                                 :db/valueType :db.type/ref
                                 :db/cardinality :db.cardinality/many}]]
             (sut/align-with-schema {:person/friends "[{:person/id \"p1\"} {:person/id \"p2\"}]"} db))
           {:person/friends [{:person/id "p1"}
                             {:person/id "p2"}]})))

  (testing "Parses java-time literals"
    (is (= (with-schema-db [db [{:db/ident :person/birthday
                                 :dte/valueType :java.time/local-date
                                 :db/cardinality :db.cardinality/one}]]
             (sut/align-with-schema {:person/birthday "#time/ld \"2007-12-03\""} db))
           {:person/birthday #time/ld "2007-12-03"})))

  (testing "Fails on many attribute with a single value"
    (is (= (try
             (with-schema-db [db [{:db/ident :person/tags
                                   :db/valueType :db.type/keyword
                                   :db/cardinality :db.cardinality/many}]]
               (sut/align-with-schema {:person/tags ":tag1 :tag2"} db))
             (catch Exception e
               {:message (.getMessage e)
                :data (ex-data e)}))
           {:message (str ":person/tags has single value :tag1, but should have many "
                          "according to the schema. Did you forget to enclose the "
                          "values in a bracket? E.g. [:tag1 :tag2]")
            :data {:attribute :person/tags
                   :raw-value ":tag1 :tag2"
                   :coerced-value :tag1}}))))

(deftest parse-file-test
  (testing "Reads edn with java.time literals"
    (is (= (sut/parse-file nil "sample.edn" (io/resource "sample.edn"))
           [{:blog-post/time #time/md "--10-01"}]))))

(defn mapify [e]
  (into {} e))

(deftest ingest-data-test
  (testing "Ingests data from file"
    (is (= (-> (with-test-powerpack [powerpack {}]
                 (->> [{:page/uri "/some-file/"
                        :page/title "Hello"}]
                      (sut/ingest-data powerpack "some-file.md")))
               :db-after
               (d/entity [:page/uri "/some-file/"])
               mapify)
           {:page/uri "/some-file/", :page/title "Hello"})))

  (testing "Does not retract data before attempting failing ingest"
    (is (= (with-test-powerpack [powerpack {}]
             (->> [{:page/uri "/some-file/"
                    :page/title "Hello"}]
                  (sut/ingest-data powerpack "some-file.md"))
             (try
               (->> [{:bogus/wow "Boom"}]
                    (sut/ingest-data powerpack "some-file.md"))
               (catch Exception _e nil))
             (->> [:page/uri "/some-file/"]
                  (d/entity (d/db (:datomic/conn powerpack)))
                  mapify))
           {:page/uri "/some-file/", :page/title "Hello"})))

  (testing "Retracts datomic type extension attrs"
    (is (= (with-test-powerpack [powerpack [{:db/ident :blog-post/published
                                             :dte/valueType :java.time/local-date-time
                                             :db/cardinality :db.cardinality/one}]]
             (->> [{:page/uri "/blog/post/"
                    :blog-post/published #time/ldt "2023-09-30T09:00"}]
                  (sut/ingest-data powerpack "post.md"))
             (->> [{:page/uri "/blog/post/"
                    :blog-post/published #time/ldt "2023-09-29T09:00"}]
                  (sut/ingest-data powerpack "post.md"))
             (->> [:page/uri "/blog/post/"]
                  (d/entity (d/db (:datomic/conn powerpack)))
                  mapify))
           {:page/uri "/blog/post/"
            :blog-post/published #time/ldt "2023-09-29T09:00"})))

  (testing "Retracts ref attrs"
    (is (= (with-test-powerpack [powerpack [{:db/ident :blog-post/author
                                             :db/valueType :db.type/ref
                                             :db/cardinality :db.cardinality/one}
                                            {:db/ident :person/id
                                             :db/valueType :db.type/string
                                             :db/cardinality :db.cardinality/one
                                             :db/unique :db.unique/identity}]]
             (->> [{:page/uri "/blog/post/"
                    :blog-post/author {:person/id "person1"}}]
                  (sut/ingest-data powerpack "post.md"))
             (->> [{:page/uri "/blog/post/"
                    :blog-post/author {:person/id "person1"}}]
                  (sut/ingest-data powerpack "post.md"))
             (:page/uri (d/entity (d/db (:datomic/conn powerpack)) [:page/uri "/blog/post/"])))
           "/blog/post/")))

  (testing "Does not retract entities asserted from multiple files"
    (is (= (-> (with-test-powerpack [powerpack [{:db/ident :blog-post/author
                                                 :db/valueType :db.type/ref
                                                 :db/cardinality :db.cardinality/one}
                                                {:db/ident :person/id
                                                 :db/valueType :db.type/string
                                                 :db/cardinality :db.cardinality/one
                                                 :db/unique :db.unique/identity}
                                                {:db/ident :person/fullname
                                                 :db/valueType :db.type/string
                                                 :db/cardinality :db.cardinality/one}]]
                 ;; Create blog post
                 (->> [{:page/uri "/some-file/"
                        :page/title "Hello"
                        :blog-post/author {:person/id "christian"}}]
                      (sut/ingest-data powerpack "blog-post.md"))
                 ;; Add some details about the person
                 (->> [{:person/id "christian"
                        :person/fullname "Christian Johansen"}]
                      (sut/ingest-data powerpack "christian.md"))
                 ;; Update the blog post - do not retract the author entity
                 (->> [{:page/uri "/some-file/"
                        :page/title "Hello!"
                        :blog-post/author {:person/id "christian"}}]
                      (sut/ingest-data powerpack "blog-post.md")))
               :db-after
               (d/entity [:person/id "christian"])
               mapify)
           {:person/id "christian"
            :person/fullname "Christian Johansen"})))

  (testing "Retract entities asserted from multiple files when the last file ditches it"
    (is (nil? (-> (with-test-powerpack [powerpack [{:db/ident :blog-post/author
                                                    :db/valueType :db.type/ref
                                                    :db/cardinality :db.cardinality/one}
                                                   {:db/ident :person/id
                                                    :db/valueType :db.type/string
                                                    :db/cardinality :db.cardinality/one
                                                    :db/unique :db.unique/identity}
                                                   {:db/ident :person/fullname
                                                    :db/valueType :db.type/string
                                                    :db/cardinality :db.cardinality/one}]]
                    ;; Create blog post
                    (->> [{:page/uri "/some-file/"
                           :page/title "Hello"
                           :blog-post/author {:person/id "christian"}}]
                         (sut/ingest-data powerpack "blog-post.md"))
                    ;; Add some details about the person
                    (->> [{:person/id "christian"
                           :person/fullname "Christian Johansen"}]
                         (sut/ingest-data powerpack "christian.md"))
                    ;; Update the blog post
                    (->> [{:page/uri "/some-file/"
                           :page/title "Hello!"}]
                         (sut/ingest-data powerpack "blog-post.md"))
                    ;; Remove person
                    (sut/ingest-data powerpack "christian.md" []))
                  :db-after
                  (d/entity [:person/id "christian"])))))

  (testing "Does not retract entities referenced from other files"
    (is (= (-> (with-test-powerpack [powerpack [{:db/ident :blog-post/author
                                                 :db/valueType :db.type/ref
                                                 :db/cardinality :db.cardinality/one}
                                                {:db/ident :person/id
                                                 :db/valueType :db.type/string
                                                 :db/cardinality :db.cardinality/one
                                                 :db/unique :db.unique/identity}
                                                {:db/ident :person/fullname
                                                 :db/valueType :db.type/string
                                                 :db/cardinality :db.cardinality/one}]]
                 ;; Add some details about the person
                 (->> [{:person/id "christian"
                        :person/fullname "Christian Johansen"}]
                      (sut/ingest-data powerpack "christian.md"))
                 ;; Create blog post
                 (->> [{:page/uri "/some-file/"
                        :page/title "Hello"
                        :blog-post/author {:person/id "christian"}}]
                      (sut/ingest-data powerpack "blog-post.md"))
                 ;; Change some details - do not recreate the entity, it will
                 ;; break the blog post ref
                 (->> [{:person/id "christian"
                        :person/fullname "Christian"}]
                      (sut/ingest-data powerpack "christian.md")))
               :db-after
               (d/entity [:page/uri "/some-file/"])
               :blog-post/author
               mapify)
           {:person/id "christian"
            :person/fullname "Christian"})))

  (testing "Does not retract entities referenced transactions outside files (e.g. on-ingested/on-started)"
    (is (= (-> (with-test-powerpack [powerpack [{:db/ident :blog-post/author
                                                 :db/valueType :db.type/ref
                                                 :db/cardinality :db.cardinality/one}
                                                {:db/ident :person/id
                                                 :db/valueType :db.type/string
                                                 :db/cardinality :db.cardinality/one
                                                 :db/unique :db.unique/identity}
                                                {:db/ident :person/fullname
                                                 :db/valueType :db.type/string
                                                 :db/cardinality :db.cardinality/one}]]
                 ;; Add some details about the person
                 (->> [{:person/id "christian"
                        :person/fullname "Christian Johansen"}]
                      (sut/ingest-data powerpack "christian.md"))
                 ;; Create blog post
                 (->> [{:page/uri "/some-file/"
                        :page/title "Hello"
                        :blog-post/author {:person/id "christian"}}]
                      (d/transact (:datomic/conn powerpack)))
                 ;; Change some details - do not recreate the entity, it will
                 ;; break the blog post ref
                 (->> [{:person/id "christian"
                        :person/fullname "Christian"}]
                      (sut/ingest-data powerpack "christian.md")))
               :db-after
               (d/entity [:page/uri "/some-file/"])
               :blog-post/author
               mapify)
           {:person/id "christian"
            :person/fullname "Christian"}))))
