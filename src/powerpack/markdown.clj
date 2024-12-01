(ns powerpack.markdown
  (:require [clojure.string :as str]
            [dev.onionpancakes.chassis.core :as chassis])
  (:import [com.vladsch.flexmark.ext.autolink AutolinkExtension]
           [com.vladsch.flexmark.ext.gfm.strikethrough StrikethroughExtension]
           [com.vladsch.flexmark.ext.tables TablesExtension]
           [com.vladsch.flexmark.ext.typographic TypographicExtension]
           [com.vladsch.flexmark.html HtmlRenderer]
           [com.vladsch.flexmark.parser Parser]
           [com.vladsch.flexmark.util.data MutableDataSet]))

(def flexmark-opts
  (-> (MutableDataSet.)
      (.set Parser/EXTENSIONS
            [(AutolinkExtension/create)
             (StrikethroughExtension/create)
             (TablesExtension/create)
             (TypographicExtension/create)])))

(defn make-flexmark-opts [{:keys [auto-link? strike-through? tables? typograhy?]}]
  (-> (MutableDataSet.)
      (.set Parser/EXTENSIONS
            (cond-> []
              (not= auto-link? false) (conj (AutolinkExtension/create))
              (not= strike-through? false) (conj (StrikethroughExtension/create))
              (not= tables? false) (conj (TablesExtension/create))
              (not= typograhy? false) (conj (TypographicExtension/create))))))

(defn md-to-html
  ([s] (md-to-html nil s))
  ([options s]
   (let [flexmark-opts (or (some-> options make-flexmark-opts) flexmark-opts)]
     (->> (.parse (.build (Parser/builder flexmark-opts)) s)
          (.render (.build (HtmlRenderer/builder flexmark-opts)))))))

(defn min*
  "Like min, but takes a list - and 0 elements is okay."
  [vals]
  (when (seq vals) (apply min vals)))

(defn subs*
  "Like subs, but safe - ie, doesn't barf on too short."
  [s len]
  (if (> (count s) len)
    (subs s len)
    s))

(defn find-common-indent-column
  "Find the lowest number of spaces that all lines have as a common
   prefix. Except, don't count empty lines."
  [lines]
  (->> lines
       (remove empty?)
       (map #(count (re-find #"^ +" %)))
       (min*)))

(defn unindent-all
  "Given a block of code, if all lines are indented, this removes the
  preceeding whitespace that is common to all lines."
  [lines]
  (let [superflous-spaces (find-common-indent-column lines)]
    (map #(subs* % superflous-spaces) lines)))

(defn unindent-but-first
  "Like `unindent-all`, but preserves indentation for the first line."
  [lines]
  (let [superflous-spaces (find-common-indent-column (drop 1 lines))]
    (concat (take 1 lines)
            (map #(subs* % superflous-spaces) (drop 1 lines)))))

(defn ^:export render-html
  "Render markdown to an HTML string. Optionally pass a map of what Flexmark
  extensions to enable: `{:auto-link? :strike-through? :tables? :typograhy?}`.
  Extensions not explicit disabled with `false` will be enabled."
  [s & [options]]
  (if (string? s)
    (->> s
         str/split-lines
         unindent-but-first
         (str/join "\n")
         (md-to-html options)
         chassis/raw)
    s))
