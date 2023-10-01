(ns powerpack.watcher
  (:require [clojure.core.async :refer [put!]]
            [nextjournal.beholder :as beholder]
            [powerpack.files :as files]
            [powerpack.logger :as log]))

(defn get-watch-paths [config]
  (->> (concat
        (:powerpack/source-dirs config)
        [(:powerpack/content-dir config)]
        [(files/get-dir (:datomic/schema-file config))])
       set
       (filter files/exists?)))

(defn get-app-event [config {:keys [type path]}]
  (let [file (.toFile path)]
    (cond
      (files/same-file? file (:datomic/schema-file config))
      {:kind :powerpack/edited-schema}

      (files/parent? (:powerpack/content-dir config) file)
      {:kind :powerpack/edited-content
       :type type
       :path (-> (files/get-relative-path (:powerpack/content-dir config) file)
                 files/normalize-path)}

      (->> (:powerpack/source-dirs config)
           (some #(files/parent? % file)))
      {:kind :powerpack/edited-source})))

(defn start-watching! [{:keys [config app-events fs-events]}]
  (->> (get-watch-paths config)
       (apply beholder/watch
              (fn [e]
                (log/debug "File event" e)
                (when-let [event (get-app-event config e)]
                  (log/debug "Publish event" event)
                  (put! (:ch fs-events) event))))))

(defn stop-watching! [watcher]
  (beholder/stop watcher))
