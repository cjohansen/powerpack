(ns powerpack.error-logger
  (:require [clojure.core.async :refer [<! chan close! go tap untap]]
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

(defn get-indent [s]
  (re-find #"^ +" s))

(defn get-readable-lines [line]
  (if (string? line)
    (loop [lines []
           words (->> (str/split line #"\n")
                      (interpose "\n")
                      (mapcat (fn [line]
                                (->> (concat [(get-indent line)]
                                             (str/split line #" +"))
                                     (remove empty?)))))
           line ""
           indent (get-indent (first words))]
      (let [word (first words)]
        (cond
          (empty? words)
          (conj lines (str/trimr line))

          (= "\n" word)
          (let [words (next words)
                indent (get-indent (first words))]
            (recur (conj lines (str/trimr line)) (if (= indent (first words))
                                                   (next words)
                                                   words) "" indent))

          (< 80 (+ (count line) (count word)))
          (recur (conj lines (str/trimr line)) words "" indent)

          :else
          (recur lines (next words) (str (or (not-empty line) indent) word " ") indent))))
    (str/split (:pprint line) #"\n")))

(defn format-error-message [sections]
  (str (java.time.LocalDateTime/now) " 💥💥💥🫠\n"
       (->> (for [lines sections]
              (->> lines
                   (mapcat get-readable-lines)
                   (str/join "\n")))
            (str/join "\n\n"))))

(defn format-exception [{:keys [exception]}]
  (when exception
    (concat
     [[(str "Exception: " (.getMessage exception))]]
     (when-let [data (ex-data exception)]
       [["Exception data:" (pp-str data)]]))))

(defn format-transaction-error [event]
  (for [{:keys [message k v]} (:errors event)]
    [message (str k) {:pprint (pr-str v)}]))

(defn get-stack-trace [exception]
  (with-err-str (.printStackTrace exception)))

(defn format-error [event]
  (try
    (case (:kind event)
      :powerpack.ingest/transact
      (->> (concat
            [[(:message event)]]
            (format-transaction-error event)
            (format-exception event)
            [["Transaction data:"
              (pp-str (:tx event))]])
           format-error-message)

      :powerpack.ingest/callback
      (->> (concat
            [[(:message event)]]
            (format-exception event)
            [(get-stack-trace (:exception event))])
           format-error-message)

      (->> (concat
            (when (:message event)
              [[(:message event)]])
            (when (:description event)
              [[(:description event)]])
            (when (:tx event)
              [["Transaction data:"
                (pp-str (:tx event))]])
            (format-exception event))
           format-error-message))
    (catch Exception e
      (->> (concat
            [["Powerpack produced an exception while formatting another error."]
             ["THIS IS A BUG, please report it. Here's the unsuspecting event causing trouble:"]
             [(pp-str event)]]
            (format-exception {:exception e}))
           format-error-message))))

(defn start-watching! [{:keys [error-events]}]
  (let [watching? (atom true)
        err-ch (chan)]
    (tap (:mult error-events) err-ch)
    (go
      (loop []
        (when-let [event (<! err-ch)]
          (when-not (:resolved? event)
            (log/error (format-error event)))
          (when @watching? (recur)))))
    (fn []
      (untap (:mult error-events) err-ch)
      (close! err-ch)
      (reset! watching? false))))

(defn stop-watching! [stop]
  (stop))
