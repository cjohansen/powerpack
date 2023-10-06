(ns powerpack.ingest
  (:require [clojure.core.async :refer [<! chan go put! tap untap]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datomic-type-extensions.api :as d]
            [mapdown.core :as mapdown]
            [powerpack.db :as db]
            [powerpack.files :as files]
            [powerpack.logger :as log])
  (:import (java.net URI)))

(defn parse-vals-as [f]
  (fn [s]
    (let [res (read-string s)]
      (if (coll? res)
        (map f res)
        (f res)))))

(def conversions
  {:db.type/bigdec read-string
   :db.type/bigint read-string
   :db.type/double (parse-vals-as double)
   :db.type/float (parse-vals-as float)
   :db.type/keyword read-string
   :db.type/long (parse-vals-as long)
   :db.type/boolean read-string
   :db.type/instant read-string
   :db.type/ref read-string
   :db.type/symbol read-string
   :db.type/tuple read-string
   :db.type/uuid read-string
   :db.type/uri (parse-vals-as #(URI/create %))})

(defn get-conversion [db k]
  (let [attr (db/get-attr db k)]
    (or (conversions (:db/valueType attr))
        (when (:dte/valueType attr)
          read-string))))

(defn align-with-schema [data db]
  (->> data
       (map (fn [[k v]]
              (let [f (get-conversion db k)]
                [k (cond-> v
                     f f)])))
       (into {})))

(defmulti parse-file (fn [db file-name content]
                       (keyword (last (str/split file-name #"\.")))))

(defn suggest-url [url file-name]
  (or url (-> (str "/" (str/replace file-name #"\.[^\.]+$" "/"))
              (str/replace #"index\/$" "")
              (str/replace #"\/\/+" "/"))))

(defn parse-markdown [content]
  (or (try
        (let [parsed (mapdown/parse content)]
          (cond-> parsed
            (map? parsed) vector))
        (catch Exception _))
      [{:page/body content}]))

(defmethod parse-file :md [db file-name content]
  (for [md (parse-markdown content)]
    (-> md
        (align-with-schema db)
        (update :page/uri suggest-url file-name))))

(defmethod parse-file :edn [_db _file-name content]
  (let [data (edn/read-string content)]
    (if (and (coll? data) (not (map? data)))
      data
      [data])))

(defmethod parse-file :default [db file-name content]
  content)

(defn load-data [db {:keys [config error-events]} file-name]
  (when-let [r (io/file (str (:powerpack/content-dir config) "/" file-name))]
    (try
      (vec (parse-file db file-name (slurp r)))
      (catch Exception e
        (put! (:ch error-events)
              {:exception e
               :file-name file-name
               :kind ::parse-file})
        nil))))

(def attrs-to-keep #{:db/ident
                     :db/txInstant})

(defn get-retract-tx [db file-name]
  (when-let [tx-id (d/q '[:find ?e .
                          :in $ ?file-name
                          :where
                          [?e :tx-source/file-name ?file-name]]
                        db
                        file-name)]
    (keep
     (fn [[e a v t]]
       (when (= tx-id t)
         (let [attr (:ident (d/attribute db a))]
           (when (not (attrs-to-keep attr))
             ;; Load the entity at the time of the tx to find the value instead
             ;; of using the raw value from the index. This makes sure that
             ;; values asserted to datomic-type-extensions attributes are
             ;; retracted properly
             (if (:dte/valueType (d/entity db attr))
               [:db/retract e attr (attr (d/entity (d/as-of db t) e))]
               [:db/retract e attr v])))))
     (d/datoms db :eavt))))

(defmulti validate-attribute (fn [_m k _v] k))

(defmethod validate-attribute :open-graph/title [_m _k v]
  (if (and (string? v) (<= (count v) 70))
    {:valid? true}
    {:valid? false
     :message "Open graph title should be no longer than 70 characters to avoid being truncated."}))

(defmethod validate-attribute :open-graph/description [_m _k v]
  (if (and (string? v) (<= (count v) 200))
    {:valid? true}
    {:valid? false
     :message "Open graph description should be no longer than 200 characters to avoid being truncated."}))

(defmethod validate-attribute :default [m k v]
  {:valid? true})

(defn validate-transaction! [tx]
  (when (or (map? tx) (not (coll? tx)) (keyword? (first tx)))
    (throw (ex-info "Transaction is not a collection of transaction entities"
                    {:kind ::transact
                     :message "Make sure transaction data is a collection of maps or transaction functions"
                     :tx tx})))
  (when-let [attrs (->> (tree-seq coll? identity tx)
                        (filter map?)
                        (mapcat (fn [m]
                                  (map (fn [[k v]]
                                         (assoc (validate-attribute m k v) :k k :v v))
                                       m)))
                        (filter (comp false? :valid?))
                        seq)]
    (throw (ex-info "Invalid attributes"
                    {:kind ::transact
                     :message (str "Some attributes have invalid values, please inspect:\n"
                                   (->> (for [{:keys [message k v] }attrs]
                                          (str "  " message "\n  " k "\n  " v))
                                        (str/join "\n")))
                     :tx tx}))))

(defn ingest-data [{:keys [conn create-ingest-tx]} file-name data]
  (let [db (d/db conn)
        tx (some-> (cond->> data
                     (ifn? create-ingest-tx) (create-ingest-tx db file-name))
                   (conj [:db/add (d/tempid :db.part/tx) :tx-source/file-name file-name]))]
    (when tx
      (validate-transaction! tx)
      (try
        (d/with db tx)
        (catch Exception e
          (throw (ex-info "Unable to assert"
                          {:kind ::transact
                           :tx tx
                           :file-name file-name
                           :exception e} e)))))
    (when-let [retractions (get-retract-tx (d/db conn) file-name)]
      (try
        @(d/transact conn retractions)
        (catch Exception e
          (throw (ex-info "Unable to retract"
                          {:kind ::retract
                           :tx retractions
                           :file-name file-name
                           :exception e} e)))))
    (when tx
      @(d/transact conn tx)
      (log/info "Ingested" file-name))))

(defn ingest [{:keys [conn error-events] :as opt} file-name]
  (when-let [data (load-data (d/db conn) opt file-name)]
    (try
      (ingest-data opt file-name data)
      (catch Exception e
        (put! (:ch error-events)
              (merge {:kind ::ingest-data
                      :file-name file-name
                      :data data
                      :exception e}
                     (ex-data e)))))))

(defn call-ingest-callback [{:keys [config conn on-ingested ch-ch-ch-changes error-events]}]
  (try
    (when (ifn? on-ingested)
      (on-ingested {:config config :conn conn}))
    (catch Exception e
      (put! (:ch error-events)
            {:kind ::callback
             :exception e})))
  (when-let [ch (:ch ch-ch-ch-changes)]
    (put! ch {:kind :powerpack/ingested-content})))

(defn get-files-pattern [config]
  (let [suffixes (:powerpack/content-file-suffixes config)]
    (re-pattern (str "(" (str/join "|" suffixes) ")$"))))

(defn get-content-files [config paths]
  (let [dir (:powerpack/content-dir config)
        schema-file (.getPath (.toURL (io/file (:datomic/schema-file config))))]
    (->> paths
         (remove #(= schema-file (.getPath (.toURL (io/file (str dir "/" %)))))))))

(defn ingest-all [{:keys [config] :as opt}]
  (doseq [file-name (->> (get-files-pattern config)
                         (files/find-file-names (:powerpack/content-dir config))
                         (get-content-files config))]
    (ingest opt file-name))
  (call-ingest-callback opt))

(defn start-watching! [{:keys [fs-events] :as opt}]
  (let [watching? (atom true)
        fs-ch (chan)]
    (tap (:mult fs-events) fs-ch)
    (go
      (loop []
        (when-let [{:keys [kind type path]} (<! fs-ch)]
          (when (= :powerpack/edited-content kind)
            (log/debug "Content edited")
            (when (ingest opt path)
              (call-ingest-callback opt)
              (log/info (case type
                          :create "Ingested"
                          :modify "Updated"
                          :delete "Removed"
                          :overflow "Overflowed(?)")
                        path)))
          (when @watching? (recur)))))
    (fn []
      (untap (:mult fs-events) fs-ch)
      (reset! watching? false))))

(defn stop-watching! [stop]
  (stop))
