(ns powerpack.mockfs
  (:require [clojure.string :as str]))

(defn read-file [file-system path]
  (get file-system path))

(defn get-entries [file-system path]
  (filter #(or (= % path) (str/starts-with? % (str path "/"))) (keys file-system)))

(defn file-exists? [file-system path]
  (boolean (seq (get-entries file-system path))))

(defn delete-file [file-system path]
  (apply dissoc file-system (get-entries file-system path)))

(defn write-file [file-system path content]
  (assoc file-system path content))

(defn move [file-system source dest]
  (->> (get-entries file-system source)
       (reduce
        (fn [fs file-name]
          (-> fs
              (dissoc file-name)
              (assoc (str/replace file-name (re-pattern (str "^" source)) dest)
                     (get file-system file-name))))
        file-system)))

(defn copy [file-system source dest]
  (->> (get-entries file-system source)
       (reduce
        (fn [fs file-name]
          (assoc fs (str/replace file-name (re-pattern (str "^" source)) dest)
                 (get file-system file-name)))
        file-system)))
