(ns powerpack.async
  (:require [clojure.core.async :refer [<! chan close! go tap untap]]))

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
       (untap ~(second bindings) ch#)
       (close! ch#)
       (reset! watching?# false))))
