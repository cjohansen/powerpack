(ns powerpack.watcher
  (:require [clojure.core.async :refer [put!]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [nextjournal.beholder :as beholder]
            [powerpack.assets :as assets]
            [powerpack.files :as files]
            [powerpack.logger :as log]))

(defn get-dictionary-dir [path]
  (if (re-find #"\.edn$" path)
    (files/get-dir path)
    path))

(defn get-dictionary-dirs [config]
  (->> (vals (:m1p/dictionaries config))
       (mapcat #(map get-dictionary-dir %))))

(defn get-watch-paths [config]
  (let [dirs (->> (concat
                   (:powerpack/source-dirs config)
                   (:powerpack/resource-dirs config)
                   [(:powerpack/content-dir config)]
                   [(files/get-dir (:datomic/schema-file config))]
                   (get-dictionary-dirs config))
                  set)]
    (->> dirs
         (remove (fn [dir] (some #(files/parent? % dir) dirs)))
         (filter files/exists?))))

(defn content-file? [config file-path]
  (and (files/parent? (:powerpack/content-dir config) file-path)
       (some #(str/ends-with? file-path %)
             (:powerpack/content-file-suffixes config))))

(defn source-file? [config file]
  (and (re-find #"\.cljc?$" (str file))
       (->> (:powerpack/source-dirs config)
            (some #(files/parent? % file)))))

(defn get-asset-dirs [config]
  (for [resources (:powerpack/resource-dirs config)
        public (->> (concat
                     (:optimus/assets config)
                     (vals (:optimus/bundles config)))
                    (map :public-dir))]
    (str resources "/" public)))

(defn get-asset-paths [config]
  (->> (concat
        (:optimus/assets config)
        (vals (:optimus/bundles config)))
       (mapcat :paths)))

(defn matching-asset? [asset-path path]
  (cond
    (string? asset-path)
    (str/ends-with? path asset-path)

    (instance? java.util.regex.Pattern asset-path)
    (re-find asset-path path)))

(defn asset-file? [config file]
  (when (->> (get-asset-dirs config)
             (filter #(files/parent? % file)))
    (let [path (files/get-absolute-path file)]
      (->> (get-asset-paths config)
           (some #(matching-asset? % path))))))

(defn get-asset-path [config path]
  (let [assets (->> (:optimus/assets config)
                    (concat (vals (:optimus/bundles config)))
                    (map #(update % :public-dir re-pattern)))
        {:keys [public-dir]}
        (->> assets
             (filter
              (fn [{:keys [public-dir paths]}]
                (some #(let [relative-path (second (str/split path public-dir))]
                         (matching-asset? % relative-path)) paths)))
             first)]
    (some->> public-dir
             re-pattern
             (str/split path)
             second)))

(defn dictionary? [config file]
  (->> (get-dictionary-dirs config)
       (some #(files/parent? % file))))

(defn get-app-event [config {:keys [type path]}]
  (let [file (.toFile path)]
    (cond
      (files/same-file? file (:datomic/schema-file config))
      {:kind :powerpack/edited-schema
       :action "reload"}

      (dictionary? config file)
      {:kind :powerpack/edited-dictionary
       :action "reload"
       :type :modify
       :path (str path)}

      (content-file? config (.getAbsolutePath file))
      {:kind :powerpack/edited-content
       :type type
       :path (-> (files/get-relative-path (:powerpack/content-dir config) file)
                 files/normalize-path)
       :action "reload"}

      (source-file? config file)
      {:kind :powerpack/edited-source
       :action "reload"
       :path (str file)}

      (asset-file? config file)
      (let [path (get-asset-path config (files/get-absolute-path file))]
        (cond-> {:kind :powerpack/edited-asset
                 :action "reload"
                 :type type
                 :path path}
          (re-find #"\.css$" path)
          (merge {:action "reload-css"
                  :updatedPath (assets/find-asset-path (assets/get-assets config) path)}))))))

(defn start-watching! [{:keys [config fs-events]}]
  (->> (get-watch-paths config)
       (apply beholder/watch
              (fn [e]
                (log/debug "File event" e)
                (when-let [event (get-app-event config e)]
                  (log/debug "Publish event" event)
                  (put! (:ch fs-events) event))))))

(defn stop-watching! [watcher]
  (beholder/stop watcher))
