(ns powerpack.ingest
  (:require [clojure.core.async :refer [put!]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datomic-type-extensions.api :as d]
            [mapdown.core :as mapdown]
            [powerpack.async :refer [create-watcher]]
            [powerpack.db :as db]
            [powerpack.errors :as errors]
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
   :db.type/uri (parse-vals-as (comp #(URI/create %) str))})

(defn get-conversion [attr]
  (when-let [f (or (conversions (:db/valueType attr))
                   (when (:dte/valueType attr)
                     read-string))]
    (fn [v]
      (let [val (f v)]
        (when (and (= :db.cardinality/many (:db/cardinality attr))
                   (not (coll? val)))
          (throw (ex-info (format "%s has single value %s, but should have many according to the schema. Did you forget to enclose the value%s in a bracket? E.g. %s"
                                  (:db/ident attr)
                                  val
                                  (if (re-find #" " v) "s" "")
                                  (str "[" v "]"))
                          {:attribute (:db/ident attr)
                           :raw-value v
                           :coerced-value val})))
        val))))

(defn align-with-schema [data db]
  (->> data
       (map (fn [[k v]]
              (let [attr (db/get-attr db k)
                    f (get-conversion attr)]
                [k (cond-> v
                     f f)])))
       (into {})))

(defmulti parse-file (fn [_db file-name _file]
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

(defmethod parse-file :md [db file-name file]
  (for [md (parse-markdown (slurp file))]
    (-> md
        (align-with-schema db)
        (update :page/uri suggest-url file-name))))

(defmethod parse-file :edn [_db _file-name file]
  (let [data (read-string (slurp file))]
    (if (and (coll? data) (not (map? data)))
      data
      [data])))

(defmethod parse-file :default [_db _file-name file]
  (slurp file))

(defn load-data [db powerpack file-name & [opt]]
  (let [file (io/file (str (:powerpack/content-dir powerpack) "/" file-name))]
    (when (.exists file)
      (try
        (let [data (vec (parse-file db file-name file))]
          (errors/resolve-error opt [::parse-file file-name])
          data)
        (catch Exception e
          (->> {:exception e
                :file-name file-name
                :message (str "Failed to parse file " file-name)
                :kind ::parse-file
                :id [::parse-file file-name]}
               (errors/report-error opt))
          nil)))))

(def attrs-to-keep #{:db/ident
                     :db/txInstant})

(defn get-sources [db e]
  (d/q '[:find ?file-name ?a
         :in $ ?e
         :where
         [?e ?a _ ?t]
         [?t :tx-source/file-name ?file-name]]
       db e))

(defn get-file-references [db e]
  (->> (d/q '[:find [?t ...]
              :in $ ?e
              :where
              [_ _ ?e ?t]]
            db e)
       (map #(d/entity db %))
       (map :tx-source/file-name)
       set))

(defn retractable? [db file-name [e a]]
  (let [attr (d/attribute db a)
        other-txes (->> (get-sources db e)
                        (remove (comp #{file-name} first)))
        external-refs (seq (remove #{file-name} (get-file-references db e)))]
    (cond
      ;; Never remove certain named attributes
      (attrs-to-keep (:ident attr))
      false

      ;; Other files reference this entity
      external-refs
      false

      ;; No other files changing this entity, safe to remove
      (empty? other-txes)
      true

      ;; Other files change this specific attribute on this entity, keep
      ((set (map second other-txes)) a)
      false

      ;; Other files change the entity and the attribute is a unique one, keep
      (:unique attr)
      false

      ;; No objections, your honor. Remove at will.
      :else
      true)))

(defn get-retract-tx [db file-name]
  (when-let [tx-id (d/q '[:find ?e .
                          :in $ ?file-name
                          :where
                          [?e :tx-source/file-name ?file-name]]
                        db
                        file-name)]
    (->> (d/datoms db :eavt)
         (filter (fn [[_ _ _ t]] (= tx-id t)))
         (filter #(retractable? db file-name %))
         (group-by (fn [[e]] e))
         (mapcat
          (fn [[e xs]]
            (if (and (nil? (:db/txInstant (d/entity db e)))
                     (= #{file-name} (set (map first (get-sources db e)))))
              [[:db/retractEntity e]]
              (for [[e a v t] xs]
                (let [attr (:ident (d/attribute db a))]
                  ;; Load the entity at the time of the tx to find the value instead
                  ;; of using the raw value from the index. This makes sure that
                  ;; values asserted to datomic-type-extensions attributes are
                  ;; retracted properly
                  (if (:dte/valueType (d/entity db attr))
                    [:db/retract e attr (attr (d/entity (d/as-of db t) e))]
                    [:db/retract e attr v])))))))))

(defmulti validate-attribute (fn [_m k _v] k))

(defmethod validate-attribute :open-graph/title [_m _k v]
  (if (and (string? v) (<= (count v) 70))
    {:valid? true}
    {:valid? false
     :message "Open graph title should not exceed 70 characters, or it may be truncated during presentation."}))

(defmethod validate-attribute :open-graph/description [_m _k v]
  (if (and (string? v) (<= (count v) 200))
    {:valid? true}
    {:valid? false
     :message "Open graph description should not exceed 200 characters or it may be truncated during presentation."}))

(defmethod validate-attribute :default [_m _k _v]
  {:valid? true})

(defn validate-transaction! [tx]
  (when (or (map? tx) (not (coll? tx)) (keyword? (first tx)))
    (throw (ex-info "Transaction is not a collection of transaction entities"
                    {:kind ::transact
                     :description "Make sure transaction data is a collection of maps or transaction functions"
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
                     :description "Some attributes have invalid values, please inspect"
                     :errors (for [{:keys [message k v]} attrs]
                               {:message message
                                :k k
                                :v v})
                     :tx tx}))))

(defn retract-file-data [powerpack file-name opt]
  (when-let [retractions (get-retract-tx (d/db (:datomic/conn powerpack)) file-name)]
    (try
      (let [res @(d/transact (:datomic/conn powerpack) retractions)]
        (errors/resolve-error opt [::ingest-data file-name])
        res)
      (catch Exception e
        (throw (ex-info "Unable to retract"
                        {:kind ::retract
                         :id [::transact file-name]
                         :tx retractions
                         :file-name file-name
                         :message (str "Failed while clearing previous content from " file-name)
                         :description "This is most certainly a bug in powerpack, please report it."
                         :exception e} e))))))

(defn ingest-data [powerpack file-name data & [opt]]
  (let [create-ingest-tx (:powerpack/create-ingest-tx powerpack)
        tx (some-> (cond->> data
                     (ifn? create-ingest-tx) (create-ingest-tx file-name))
                   (conj [:db/add (d/tempid :db.part/tx) :tx-source/file-name file-name]))]
    (when tx
      (log/debug "Validating tx data from" file-name)
      (validate-transaction! tx)
      (try
        (d/with (d/db (:datomic/conn powerpack)) tx)
        (catch Exception e
          (throw (ex-info "Unable to assert"
                          {:kind ::transact
                           :id [::transact file-name]
                           :tx (let [txes (take 11 tx)]
                                 (cond-> (vec (take 10 txes))
                                   (< 10 (count txes)) (conj ["There were more than 10 txes, not shown here"])))
                           :description (if (= (-> e ex-data :db/error) :db.error/not-an-entity)
                                          (str "Can't transact attribute " (-> e ex-data :entity) ", check spelling or make sure the schema is up to date.")
                                          "This is most likely due to a schema violation.")
                           :file-name file-name
                           :exception e} e)))))
    (log/debug "Retracting existing data from" file-name)
    (retract-file-data powerpack file-name opt)
    (log/info (str "Ingesting " (count tx) " txes from " file-name))
    (let [res (when tx
                @(d/transact (:datomic/conn powerpack) tx))]
      (when res (log/info "Ingested" file-name))
      (errors/resolve-error opt [::transact file-name])
      res)))

(defn ingest [powerpack file-name & [opt]]
  (if-let [data (load-data (d/db (:datomic/conn powerpack)) powerpack file-name opt)]
    (try
      (let [ingested (ingest-data powerpack file-name data opt)]
        (errors/resolve-error opt [::ingest-data file-name])
        ingested)
      (catch Exception e
        (->> (ex-data e)
             (merge {:kind ::ingest-data
                     :id [::ingest-data file-name]
                     :file-name file-name
                     :message (str "Failed to transact content from " file-name " to Datomic.")
                     :description "This is likely a Powerpack bug, please report it."
                     :data data
                     :exception e})
             (errors/report-error opt))))
    (retract-file-data powerpack file-name opt)))

(defn call-ingest-callback [powerpack opt results]
  (try
    (let [on-ingested (:powerpack/on-ingested powerpack)]
      (when (ifn? on-ingested)
        (on-ingested powerpack results)))
    (catch Exception e
      (->> {:kind ::callback
            :id [::callback]
            :message "Encountered an exception while calling your `on-ingested` hook, please investigate."
            :exception e}
           (errors/report-error opt))))
  (when-let [ch (-> opt :app-events :ch)]
    (log/debug "Emit app-event :powerpack/ingested-content")
    (put! ch {:kind :powerpack/ingested-content
              :action "reload"})))

(defn get-files-pattern [config]
  (let [suffixes (:powerpack/content-file-suffixes config)]
    (re-pattern (str "(" (str/join "|" suffixes) ")$"))))

(defn get-content-files [config paths]
  (let [dir (:powerpack/content-dir config)
        schema-file (.getPath (.toURL (io/file (:datomic/schema-file config))))]
    (->> paths
         (remove #(= schema-file (.getPath (.toURL (io/file (str dir "/" %)))))))))

(defn ingest-all [powerpack & [opt]]
  (->> (for [file-name (->> (get-files-pattern powerpack)
                            (files/find-file-names (:powerpack/content-dir powerpack))
                            (get-content-files powerpack))]
         (ingest powerpack file-name opt))
       doall
       (call-ingest-callback powerpack opt)))

(defn start-watching! [powerpack opt]
  (create-watcher [message (-> opt :fs-events :mult)]
    (when-let [{:keys [kind type path]} message]
      (when (= :powerpack/edited-content kind)
        (log/debug "Content edited" kind type path)
        (when-let [res (ingest powerpack path opt)]
          (call-ingest-callback powerpack opt [res]))))))

(defn stop-watching! [stop]
  (stop))
