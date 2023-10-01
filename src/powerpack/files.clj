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
  (let [dir (io/file dir)
        path-len (inc (count (get-path dir)))
        path-from-dir #(subs (get-path %) path-len)]
    (sequence
     (comp
      (filter #(.isFile %))
      (remove emacs-file?)
      (map path-from-dir)
      (filter #(re-find regex %)))
     (file-seq dir))))

(defn as-file [x]
  (cond
    (instance? File x) x
    (string? x) (io/file x)
    :else (.toFile x)))

(defn same-file? [f1 f2]
  (= (.getAbsolutePath (as-file f1))
     (.getAbsolutePath (as-file f2))))

(defn parent? [dir f]
  (str/starts-with?
   (.getAbsolutePath (as-file f))
   (.getAbsolutePath (as-file dir))))

(defn get-relative-path [dir f]
  (let [file-path (.getAbsolutePath (as-file f))
        dir-path (.getAbsolutePath (as-file dir))]
    (subs file-path (inc (count dir-path)))))

(defn get-dir [f]
  (or (.getParent (as-file f)) "."))

(defn exists? [f]
  (.exists (as-file f)))

(comment
  (find-file-names "content" #"(md|edn)$")

  )
