(ns powerpack.i18n
  (:require [clojure.java.io :as io]
            [m1p.core :as m1p]
            [powerpack.errors :as errors]))

(defn load-dictionary-file [path opt]
  (try
    (let [dictionary (read-string (slurp (io/file path)))
          dictionary (cond->> dictionary
                       (not (map? dictionary)) (apply merge))]
      (errors/resolve-error opt [::parse path])
      dictionary)
    (catch Exception e
      (when-not (->> {:exception e
                      :file-name path
                      :message (str "Failed to parse dictionary file " path)
                      :kind ::parse
                      :id [::parse path]}
                     (errors/report-error opt))
        (throw (ex-info "Failed to parse dictionary file" {:path path} e))))))

(defn load-dictionaries [config & [opt]]
  (->> (for [[locale resources] (:m1p/dictionaries config)]
         [locale
          (m1p/prepare-dictionary
           (map #(load-dictionary-file % opt) resources)
           {:dictionary-fns (:m1p/dictionary-fns config)})])
       (into {})))
