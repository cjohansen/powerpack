(ns powerpack.errors
  (:require [clojure.core.async :refer [put!]]))

(defn report-error [{:keys [ch]} error]
  (assert (:id error))
  (assert (:kind error))
  (when ch
    (put! ch error)))

(defn resolve-error [{:keys [ch]} id]
  (when ch
    (put! ch {:id id, :resolved? true})))
