(ns powerpack.highlight
  (:require [clojure.string :as str]
            [clygments.core :as pygments]
            [html5-walker.walker :as html5-walker]))

(defn- extract-code
  "Pulls out just the highlighted code, removing needless fluff and
  stuff from the Pygments treatment."
  [highlighted]
  (.getInnerHTML (first (html5-walker/find-nodes highlighted [:pre]))))

(defn highlight
  "Extracts code from the node contents, and highlights it according
  to the given language (extracted from the node's class name)."
  [node]
  (let [lang (some-> node (.getAttribute "class") not-empty (str/replace #"language-" "") keyword)
        code (-> (.getInnerHTML node)
                 (str/replace "&lt;" "<")
                 (str/replace "&gt;" ">")
                 (str/replace "&amp;" "&"))]
    ;; Certain code samples (like a 14Kb HTML string embedded in JSON) trips up
    ;; Pygments (too much recursion). When that happens, skip highlighting
    (try
      (.setInnerHTML node
                     (-> code
                         (pygments/highlight (or lang "text") :html)
                         (extract-code)))
      (catch Exception _))))

(def skip-pygments?
  (= (System/getProperty "powerpack.pygments.skip") "true"))

(defn maybe-highlight-node
  "Parsing and highlighting with Pygments is quite resource intensive. This way
   pygments can be disabled by setting
   JVM_OPTS=\"-Dpowerpack.pygments.skip=true\""
  [node]
  (when-not skip-pygments?
    (highlight node)))

(defn add-hilite-class [node]
  (->> (str (.getAttribute node "class") " codehilite")
       str/trim
       (.setAttribute node "class")))

(defn get-code-block-highlighters [& [_req]]
  {[:pre :code] maybe-highlight-node
   [:pre] add-hilite-class})

(defn install [app]
  (update app :powerpack/page-post-process-fns conj #'get-code-block-highlighters))
