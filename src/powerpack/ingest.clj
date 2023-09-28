(ns powerpack.ingest
  (:require [clojure.core.async :refer [put!]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datomic-type-extensions.api :as d]
            [mapdown.core :as mapdown]
            [nextjournal.beholder :as beholder]
            [powerpack.files :as files]
            [powerpack.logger :refer [log]]))

(defn align-with-schema [data db]
  data)

(defmulti parse-file (fn [db file-name content]
                       (keyword (last (str/split file-name #"\.")))))

(defn suggest-url [url file-name]
  (or url (str "/" (str/replace file-name #"\.[^\.]+$" "/"))))

(defmethod parse-file :md [db file-name content]
  (-> content
      mapdown/parse
      (align-with-schema db)
      (update :page/uri suggest-url file-name)))

(defmethod parse-file :edn [db file-name content]
  (edn/read-string content))

(defmethod parse-file :default [db file-name content]
  content)

;; Se på ingesta data:
;; - Valider lengde på open-graph-ting
;; - Align data med skjema

(defn load-data [db {:keys [config]} file-name]
  (when-let [r (io/file (str (:powerpack/content-dir config) "/" file-name))]
    (try
      (parse-file db file-name (slurp r))
      (catch Exception e
        (log "Failed to ingest" file-name)
        (log e)))))

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
             [:db/retract e attr v]))))
     (d/datoms db :eavt))))

(defn ingest [{:keys [conn config create-ingest-tx] :as opt} file-name]
  (when-let [tx (get-retract-tx (d/db conn) file-name)]
    (try
      @(d/transact conn tx)
      (catch Exception e
        (throw (ex-info "Unable to retract" {:tx tx
                                             :file-name file-name} e)))))
  (let [db (d/db conn)]
    (when-let [tx (create-ingest-tx db file-name (load-data db opt file-name))]
      (try
        (let [res @(d/transact conn (conj tx [:db/add (d/tempid :db.part/tx) :tx-source/file-name file-name]))]
          (log "[powerpack.ingest] Ingested" file-name)
          res)
        (catch Exception e
          (throw (ex-info "Unable to assert" {:tx tx
                                              :file-name file-name} e)))))))

(defn call-ingest-callback [{:keys [config conn on-ingested ch-ch-ch-changes]}]
  (when (ifn? on-ingested)
    (on-ingested {:config config :conn conn}))
  (when-let [ch (:ch ch-ch-ch-changes)]
    (put! ch {:type :ingested-content})))

(defn get-files-pattern [config]
  (let [suffixes (or (:powerpack/content-file-suffixes config)
                     ["md" "edn"])]
    (re-pattern (str "(" (str/join "|" suffixes) ")$"))))

(defn ingest-all [{:keys [conn config] :as opt}]
  (doseq [file-name (files/find-file-names (:powerpack/content-dir config) (get-files-pattern config))]
    (ingest opt file-name))
  (call-ingest-callback opt))

(defn start-watching! [{:keys [conn config on-ingested] :as params}]
  (let [file (io/file (:powerpack/content-dir config))
        chop-length (inc (count (.getAbsolutePath file)))]
    (beholder/watch
     (fn [{:keys [type path]}]
       (let [file-path (subs (.getAbsolutePath (.toFile path)) chop-length)]
         (when (ingest params (files/normalize-path file-path))
           (call-ingest-callback params)
           (log (case type
                  :create "Ingested"
                  :modify "Updated"
                  :delete "Removed"
                  :overflow "Overflowed(?)")
                file-path))))
     (:powerpack/content-dir config))))

(defn stop-watching! [watcher]
  (beholder/stop watcher))
