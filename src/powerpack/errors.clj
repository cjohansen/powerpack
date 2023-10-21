(ns powerpack.errors
  (:require [clojure.core.async :refer [put!]]))

(defn report-error [{:keys [error-events]} error]
  (assert (:id error))
  (assert (:kind error))
  (when-let [ch (:ch error-events)]
    (put! ch error)))

(defn resolve-error [{:keys [error-events]} id]
  (when-let [ch (:ch error-events)]
    (put! ch {:id id, :resolved? true})))
