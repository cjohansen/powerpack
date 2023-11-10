(ns powerpack.async
  (:require [clojure.core.async :refer [<! chan close! go poll! tap untap]]))

(defn untap!! [mult-ch ch]
  (while (poll! ch)
    ;; Drain the swamp! Drains any pending messages before untapping - OTHERWISE
    ;; THE WORLD STOPS :'(
    )
  (untap mult-ch ch))

(defmacro create-watcher
  {:clj-kondo/lint-as 'clojure.core/with-open}
  [bindings & body]
  `(let [watching?# (atom true)
         ch# (chan)]
     (tap ~(second bindings) ch#)
     (go
       (loop []
         (let [~(first bindings) (<! ch#)]
           ~@body)
         (when @watching?# (recur))))
     (fn []
       (untap!! ~(second bindings) ch#)
       (close! ch#)
       (reset! watching?# false))))
