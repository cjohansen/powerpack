(ns powerpack.logger
  (:require [clojure.core.async :refer [<! chan close! dropping-buffer go put!]]))

(defonce ^:dynamic logger-ch nil)
(defonce log-level :info)
(def levels [:error :info :debug])

(defn log? [log-level msg-level]
  (<= (.indexOf levels msg-level) (.indexOf levels log-level)))

(defn start-logger [level]
  (let [ch (chan (dropping-buffer 10))]
    (go
      (loop []
        (when-let [msg (<! ch)]
          (apply println msg)
          (recur))))
    (def logger-ch ch)
    (when level
      (def log-level level))
    {:ch ch
     :stop #(close! ch)}))

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

(defn format-ms [ms]
  (if (< ms 10000)
    (str ms "ms")
    (str (format "%02d:%02d.%03d (%dms)"
                 (int (/ ms 60000))
                 (int (/ (mod ms 60000) 1000))
                 (int (mod ms 1000))
                 ms))))

(defmacro with-timing [level name exp]
  `(let [start# (System/currentTimeMillis)
         res# ~exp]
     (log ~level [~name "in" (format-ms (- (System/currentTimeMillis) start#))])
     res#))

(defmacro with-monitor [level message exp]
  `(let [start# (System/currentTimeMillis)]
     (log ~level [~message])
     (let [res# ~exp]
       (log ~level [" ... complete in" (format-ms (- (System/currentTimeMillis) start#))])
       res#)))
