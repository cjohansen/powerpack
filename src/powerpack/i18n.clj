(ns powerpack.i18n
  (:require [clojure.java.io :as io]
            [m1p.core :as m1p]))

(defn load-dictionary-file [path]
  (let [dictionary (read-string (slurp (io/file path)))]
    (cond->> dictionary
      (not (map? dictionary)) (apply merge))))

(defn load-dictionaries [config]
  (->> (for [[locale resources] (:m1p/dictionaries config)]
         [locale
          (m1p/prepare-dictionary
           (map load-dictionary-file resources)
           {:dictionary-fns (:m1p/dictionary-fns config)})])
       (into {})))
