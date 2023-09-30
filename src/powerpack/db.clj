(ns powerpack.db
  (:require [datomic-type-extensions.api :as d]
            [datomic-type-extensions.types :refer [define-dte]]
            [java-time-dte.install :as jtl-dte]
            [java-time-literals.core :as jtl]))

::jtl/keep
::jtl-dte/keep

(define-dte :data/edn :db.type/string
  [this] (pr-str this)
  [^String s] (read-string s))

(def powerpack-schema
  [;; meta
   {:db/ident :tx-source/file-name
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}

   ;; page
   {:db/ident :page/kind
    :db/valueType :db.type/keyword
    :db/cardinality :db.cardinality/one}

   {:db/ident :page/uri
    :db/valueType :db.type/string
    :db/unique :db.unique/identity
    :db/cardinality :db.cardinality/one}

   {:db/ident :page/title
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident :page/body
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}

   ;; open graph
   {:db/ident :open-graph/title
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident :open-graph/description
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}

   {:db/ident :open-graph/image
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}

   ])

(defn create-database [uri schema]
  (d/create-database uri)
  (let [conn (d/connect uri)]
    @(d/transact conn (concat powerpack-schema schema))
    conn))
