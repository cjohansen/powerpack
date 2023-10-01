(ns powerpack.logger
  (:require [clojure.core.async :refer [<! chan dropping-buffer go put!]]))

(defonce logger-ch nil)
(defonce log-level :info)
(def levels [:error :info :debug])

(defn log? [log-level msg-level]
  (<= (.indexOf levels msg-level) (.indexOf levels log-level)))

(defn start-logger [level]
  (let [running? (atom true)
        ch (chan (dropping-buffer 10))]
    (go
      (loop []
        (when @running?
          (when-let [msg (<! ch)]
            (apply println msg)
            (recur)))))
    (def logger-ch ch)
    (when level
      (def log-level level))
    {:ch ch
     :stop #(reset! running? false)}))

(defn stop-logger [logger]
  ((:stop logger)))

(defmacro log [level msg]
  `(when (and logger-ch (log? log-level ~level))
     (put! logger-ch [~(str "[" *ns* "]") ~@msg])))

(defmacro error [& msg]
  `(log :error ~msg))

(defmacro info [& msg]
  `(log :info ~msg))

(defmacro debug [& msg]
  `(log :debug ~msg))
