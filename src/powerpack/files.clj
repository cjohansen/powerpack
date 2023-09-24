(ns powerpack.files
  (:require [clojure.java.io :as io]
            [clojure.string :as str])
  (:import (java.io File)
           (java.util.regex Pattern)))

(def fsep (File/separator))
(def fsep-regex-str (Pattern/quote fsep))

(defn normalize-path [^String path]
  (if (= fsep "/")
    path
    (.replaceAll path fsep-regex-str "/")))

(defn get-path [#^File path]
  (normalize-path (.getPath path)))

(defn- just-the-filename [^String path]
  (last (str/split path (re-pattern fsep-regex-str))))

(defn- emacs-file-artefact? [^String path]
  (let [filename (just-the-filename path)]
    (or (.startsWith filename ".#")
        (and (.startsWith filename "#")
             (.endsWith filename "#")))))

(defn- emacs-file? [^File file]
  (-> file get-path emacs-file-artefact?))

(defn find-file-names [dir regex]
  (let [dir (io/as-file (io/resource dir))
        path-len (inc (count (get-path dir)))
        path-from-dir #(subs (get-path %) path-len)]
    (sequence
     (comp
      (filter #(.isFile %))
      (remove emacs-file?)
      (map path-from-dir)
      (filter #(re-find regex %)))
     (file-seq dir))))

(comment
  (find-file-names "content" #"(md|edn)$")

  )
