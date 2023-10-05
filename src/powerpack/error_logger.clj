(ns powerpack.error-logger
  (:require [clojure.core.async :refer [<! chan go tap untap]]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [powerpack.logger :as log]))

(defmacro with-err-str [& body]
  `(let [writer# (java.io.StringWriter.)]
     (binding [*err* writer#]
       ~@body)
     (.toString writer#)))

(defn pp-str [data]
  {:pprint (with-out-str (pprint/pprint data))})

(defn get-readable-lines [line]
  (if (string? line)
    (loop [lines []
           words (-> line
                     (str/replace #"\n" " ")
                     (str/split #" +"))
           line ""]
      (let [word (first words)]
        (cond
          (empty? words)
          (conj lines (str/trim line))

          (< 80 (+ (count line) (count word)))
          (recur (conj lines (str/trim line)) words "")

          :else
          (recur lines (next words) (str line " " word)))))
    (str/split (:pprint line) #"\n")))

(defn format-sections [sections]
  (->> (for [lines sections]
         (->> lines
              (mapcat get-readable-lines)
              (map #(str "    " %))
              (str/join "\n")))
       (str/join "\n\n")))

(defn format-exception [{:keys [exception]}]
  (when exception
    (concat
     [[(str "Exception: " (.getMessage exception))]]
     (when-let [data (ex-data exception)]
       [["Exception data:" (pp-str data)]]))))

(defn format-transaction-error [event]
  (let [data (-> event :exception ex-data)]
    (cond
      (= (:db/error data) :db.error/not-an-entity)
      [[(str "Can't transact attribute " (:entity data) ", check spelling or make sure the schema is up to date.")]]

      :else
      [["This is most likely due to a schema violation."]])))

(defn format-error [event]
  (try
    (case (:kind event)
      :powerpack.ingest/parse-file
      (str "Failed to parse file " (:file-name event) "\n"
           (->> (format-exception event)
                format-sections))

      :powerpack.ingest/transact
      (str "Failed to transact content from " (:file-name event) " to Datomic.\n"
           (->> (concat
                 (format-transaction-error event)
                 (format-exception event)
                 [["Transaction data:"
                   (pp-str (:tx event))]
                  ["Using old file contents until the problem is resolved."]])
                format-sections))


      :powerpack.ingest/retract
      (str "Failed while clearing previous content from " (:file-name event) "\n"
           (->> (concat
                 [["This is most certainly a bug in powerpack."]
                  ["Please open an issue and paste the following transaction data:"
                   (pp-str (:tx event))]]
                 (format-exception event))
                format-sections))

      :powerpack.ingest/ingest-data
      (str "Failed to update the database from " (:file-name event) "\n"
           (->> (concat
                 [["This is likely a Powerpack bug, please report it."]
                  ["Data:" (pp-str (:data event))]]
                 (format-exception event))))

      :powerpack.ingest/callback
      (str "Encountered an exception while calling your `on-ingested` hook, please investigate.\n"
           (->> (conj (format-exception event)
                      (with-err-str (.printStackTrace (:exception event))))
                format-sections)))
    (catch Exception e
      (str "Powerpack produced an exception while formatting another error.\n"
           (->> (concat
                 [["THIS IS A BUG, please report it. Here's the unsuspecting event causing trouble:\n"
                   (pp-str event)]]
                 (format-exception {:exception e}))
                format-sections)))))

(defn start-watching! [{:keys [error-events]}]
  (let [watching? (atom true)
        err-ch (chan)]
    (tap (:mult error-events) err-ch)
    (go
      (loop []
        (when-let [event (<! err-ch)]
          (log/error (format-error event))
          (when @watching? (recur)))))
    (fn []
      (untap (:mult error-events) err-ch)
      (reset! watching? false))))

(defn stop-watching! [stop]
  (stop))
