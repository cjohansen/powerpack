(ns powerpack.web
  (:require [clojure.core.async :refer [put!]]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datomic-type-extensions.api :as d]
            [html5-walker.core :as html5-walker]
            [imagine.core :as imagine]
            [optimus.link :as link]))

(defn get-content-type-k [response]
  (or (->> (keys (:headers response))
           (filter #(= "content-type" (str/lower-case %)))
           first)
      "Content-Type"))

(defn get-content-type [response]
  (get-in response [:headers (get-content-type-k response)]))

(defn make-utf-8 [res]
  (when res
    (let [k (get-content-type-k res)
          content-type (get-in res [:headers k])]
      (if (or (empty? content-type)
              (.contains content-type ";")
              (not (string? (:body res))))
        res
        (update-in res [:headers k] #(str % "; charset=utf-8"))))))

(defn wrap-utf-8
  "This function works around the fact that Ring simply chooses the default JVM
  encoding for the response encoding. This is not desirable, we always want to
  send UTF-8."
  [handler]
  (fn [req] (-> req handler make-utf-8)))

(defn optimize-asset-url [req src]
  (try
    (let [[url hash] (str/split src #"#")]
      (str
       (or (not-empty (link/file-path req url))
           (imagine/realize-url (-> req :powerpack/config :imagine/config) url)
           (throw (Exception. (str "Asset not loaded: " url))))
       (some->> hash (str "#"))))
    (catch Exception e
      (throw (ex-info "Failed to optimize path" {:src src} e)))))

(defn optimize-path-fn [req]
  (fn [src]
    (->> (str/split src #",")
         (map #(optimize-asset-url req %))
         (str/join ","))))

(defn try-optimize-path [req path]
  (or (not-empty (link/file-path req path))
      path))

(defn fix-links [req path]
  (when-let [path (try-optimize-path req path)]
    (if (and (-> req :powerpack/config :site/base-url)
             (str/starts-with? path "/")
             (not (str/starts-with? path "//")))
      (str (-> req :powerpack/config :site/base-url) path)
      path)))

(defn update-attr [node attr f]
  (.setAttribute node attr (f (.getAttribute node attr))))

(defn replace-attr [node attr-before attr-after f]
  (.setAttribute node attr-after (f (.getAttribute node attr-before)))
  (.removeAttribute node attr-before))

(defn replace-urls [f style]
  (when style
    (str/replace style #"url\((.+?)\)"
                 (fn [[_ url]]
                   (str "url(" (f url) ")")))))

(defn replace-path [f path]
  (str/replace path #"(\S+)(\s+\S+)?"
               (fn [[_ path suffix]]
                 (str (f path) suffix))))

(defn replace-paths [f paths]
  (when paths
    (->> (str/split paths #",\s*")
         (map #(replace-path f %))
         (str/join ", "))))

(defn update-img-attrs [node f]
  (update-attr node "src" f)
  (when (.getAttribute node "srcset")
    (update-attr node "srcset" #(replace-paths f %))))

(defn optimize-open-graph-image [req url]
  (let [f (optimize-path-fn req)]
    (str (-> req :powerpack/config :site/base-url) (f url))))

(defn get-markup-url-optimizers [context]
  (let [optimize-path (optimize-path-fn context)]
    {;; use optimized images
     [:img] #(update-img-attrs % (optimize-path-fn context))
     [:head :meta] #(when (= (.getAttribute % "property") "og:image")
                      (update-attr % "content" (partial optimize-open-graph-image context)))
     [:.w-style-img] #(update-attr % "style" (partial replace-urls optimize-path))
     [:.section] #(update-attr % "style" (partial replace-urls optimize-path))
     [:video :source] #(update-attr % "src" optimize-path)
     [:picture :source] #(update-attr % "srcset" optimize-path)

     ;; use optimized svgs
     [:svg :use] #(replace-attr % "href" "xlink:href" optimize-path)

     ;; use optimized links, if possible
     [:a] #(update-attr % "href" (partial fix-links context))}))

(defn combine-post-processors [context post-processors]
  (->> (for [[selector fns] (->> post-processors
                                 (mapcat (fn [f] (f context)))
                                 (group-by first))]
         [selector (fn [& args]
                     (doseq [fn (map second fns)]
                       (apply fn args)))])
       (into {})))

(defn tweak-page-markup [html context post-processors]
  (try
    (->> (combine-post-processors context post-processors)
         (html5-walker/replace-in-document html))
    (catch Exception e
      (throw (ex-info "Error while optimizing URLs in page markup"
                      {:request (dissoc context :optimus-assets)}
                      e)))))

(defn post-process-page [response context post-processors]
  (cond-> response
    (some-> (get-content-type response)
            (str/starts-with? "text/html"))
    (update :body tweak-page-markup context post-processors)))

(defn prepare-response [response]
  (if (string? (:body response))
    response
    (let [content-type (get-content-type response)]
      (cond
        (:content-type response)
        (case (:content-type response)
          :json (update response :body json/write-str)
          :edn (update response :body pr-str)
          (throw (ex-info (str "Unknown :content-type " (:content-type response))
                          {:response response})))

        (and (string? content-type)
             (re-find #"application/json" content-type))
        (update response :body json/write-str)

        (or (nil? content-type)
            (and (string? content-type)
                 (re-find #"application/edn" content-type)))
        (update response :body pr-str)

        :else
        response))))

(defn get-response-map [rendered]
  (cond
    (and (map? rendered)
         (or (:status rendered)
             (:headers rendered)
             (:body rendered)))
    (prepare-response rendered)

    (string? rendered)
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body rendered}

    :else
    {:status 200
     :headers {"Content-Type" "application/edn"}
     :body (pr-str rendered)}))

(defn render-error [status message]
  {:status status
   :body (if-let [file (io/resource (str status ".html"))]
           (slurp file)
           message)
   :headers {"Content-Type" "text/html"}})

(defn render-page [{:keys [fns error-events]} context page]
  (try
    (let [res (get-response-map ((:render-page fns) context page))]
      (put! (:ch error-events)
            {:id [::render-page (:uri context)]
             :resolved? true})
      res)
    (catch Exception e
      (put! (:ch error-events)
            {:exception e
             :uri (:uri context)
             :message (str "Failed to render page " (:uri context))
             :kind ::render-page
             :id [::render-page (:uri context)]})
      (throw e))))

(defn handle-request [req {:keys [fns] :as opt}]
  (let [context (-> (when (ifn? (:get-context fns))
                      ((:get-context fns)))
                    (assoc :uri (:uri req))
                    (assoc :powerpack/config (:config req))
                    (assoc :app/db (:db req))
                    (assoc :optimus-assets (:optimus-assets req))
                    (merge (select-keys req [:powerpack/live-reload?])))]
    (-> (if-let [page (d/entity (:db req) [:page/uri (:uri req)])]
          (render-page opt context page)
          (render-error 404 "Page not found"))
        (post-process-page
         context
         (concat (:page-post-process-fns fns)
                 [get-markup-url-optimizers])))))

(defn serve-pages [opt]
  (fn [req]
    (handle-request req opt)))

(defn get-pages [db req opt]
  (into {}
        (for [uri (d/q '[:find [?uri ...] :where [_ :page/uri ?uri]] db)]
          (try
            [uri (:body (handle-request (assoc req :uri uri) opt))]
            (catch Exception e
              (throw (ex-info (str "Unable to render page " uri)
                              {:uri uri}
                              e)))))))

(defn wrap-system [handler {:keys [conn config]}]
  (fn [req]
    (-> req
        (assoc :config config)
        (assoc :db (d/db conn))
        handler)))
