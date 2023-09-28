(ns powerpack.logger
  (:require [clojure.core.async :refer [<! chan dropping-buffer go put!]]))

(defonce logger-ch nil)

(defn start-logger []
  (let [running? (atom true)
        ch (chan (dropping-buffer 10))]
    (go
      (loop []
        (when @running?
          (when-let [msg (<! ch)]
            (apply println msg)
            (recur)))))
    (def logger-ch ch)
    {:ch ch
     :stop #(reset! running? false)}))

(defn stop-logger [logger]
  ((:stop logger)))

(defmacro log [& msg]
  `(when logger-ch
     (put! logger-ch [~(str "[" *ns* "]") ~@msg])))
