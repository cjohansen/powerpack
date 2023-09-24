(ns powerpack.web
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [datomic-type-extensions.api :as d]
            [html5-walker.core :as html5-walker]
            [imagine.core :as imagine]
            [optimus.assets :as assets]
            [optimus.link :as link]
            [optimus.optimizations :as optimizations]
            [optimus.prime :as optimus]
            [optimus.strategies :as strategies]
            [prone.middleware :as prone]
            [ring.middleware.content-type :refer [wrap-content-type]]))

(defn get-content-type-k [response]
  (or (->> (keys (:headers response))
           (filter #(= "content-type" (str/lower-case %)))
           first)
      "Content-Type"))

(defn get-content-type [response]
  (get-in response [:headers (get-content-type-k response)]))

(defn wrap-content-type-utf-8 [handler]
  (fn [request]
    (when-let [response (handler request)]
      (if (some-> (get-content-type response) (.contains ";"))
        response
        (if (string? (:body response))
          (update-in response [:headers (get-content-type-k response)]
                     #(str (or % "text/html") "; charset=utf-8"))
          response)))))

(defn get-assets [config]
  (concat
   (mapcat (fn [{:keys [public-dir paths]}]
             (assets/load-assets (or public-dir "public") paths))
           (:optimus/assets config))
   (mapcat (fn [[bundle {:keys [public-dir paths]}]]
             (assets/load-bundle (or public-dir "public") bundle paths))
           (:optimus/bundles config))))

(defn optimize-asset-url [req src]
  (try
    (let [[url hash] (str/split src #"#")]
      (str
       (or (not-empty (link/file-path req url))
           (imagine/realize-url (-> req :config :imagine/config) url)
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
    (if (and (-> req :config :site/base-url)
             (str/starts-with? path "/")
             (not (str/starts-with? path "//")))
      (str (-> req :config :site/base-url) path)
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
    (str (-> req :config :site/base-url) (f url))))

(defn get-markup-url-optimizers [req]
  (let [optimize-path (optimize-path-fn req)]
    {;; use optimized images
     [:img] #(update-img-attrs % (optimize-path-fn req))
     [:head :meta] #(when (= (.getAttribute % "property") "og:image")
                      (update-attr % "content" (partial optimize-open-graph-image req)))
     [:.w-style-img] #(update-attr % "style" (partial replace-urls optimize-path))
     [:.section] #(update-attr % "style" (partial replace-urls optimize-path))
     [:video :source] #(update-attr % "src" optimize-path)
     [:picture :source] #(update-attr % "srcset" optimize-path)

     ;; use optimized svgs
     [:svg :use] #(replace-attr % "href" "xlink:href" optimize-path)

     ;; use optimized links, if possible
     [:a] #(update-attr % "href" (partial fix-links req))}))

(defn combine-post-processors [req post-processors]
  (->> (for [[selector fns] (->> post-processors
                                 (mapcat (fn [f] (f req)))
                                 (group-by first))]
         [selector (fn [& args]
                     (doseq [fn (map second fns)]
                       (apply fn args)))])
       (into {})))

(defn tweak-page-markup [html req post-processors]
  (try
    (->> (combine-post-processors req post-processors)
         (html5-walker/replace-in-document html))
    (catch Exception e
      (throw (ex-info "Error while optimizing URLs in page markup"
                      {:request (dissoc req :optimus-assets)}
                      e)))))

(defn post-process-page [response request post-processors]
  (cond-> response
    (some-> (get-content-type response)
            (str/starts-with? "text/html"))
    (update :body tweak-page-markup request post-processors)))

(defn get-response-map [rendered]
  (cond
    (and (map? rendered)
         (or (:status rendered)
             (:headers rendered)
             (:body rendered)))
    rendered

    (string? rendered)
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body rendered}

    :else
    {:status 200
     :headers {"Content-Type" "application/edn"}
     :body (pr-str rendered)}))

(defn handle-request [req {:keys [render-page post-processors]}]
  (when-let [page (d/entity (:db req) [:page/uri (:uri req)])]
    (-> (render-page req page)
        get-response-map
        (post-process-page req (concat [get-markup-url-optimizers] post-processors)))))

(defn serve-pages [conn config opt]
  (fn [req]
    (or (-> req
            (assoc :db (d/db conn))
            (assoc :config config)
            (handle-request opt))
        {:status 404
         :body (if-let [file (io/resource "404.html")]
                 (slurp file)
                 "Page not found")
         :headers {"Content-Type" "text/html"}})))

(defn get-pages [db req opt]
  (into {}
        (for [uri (d/q '[:find [?uri ...] :where [_ :page/uri ?uri]] db)]
          (try
            [uri (:body (handle-request (assoc req :uri uri :db db) opt))]
            (catch Exception e
              (throw (ex-info (str "Unable to render page " uri)
                              {:uri uri}
                              e)))))))

(defn create-app [{:keys [conn config render-page page-post-process-fns]}]
  (-> (serve-pages conn config {:render-page render-page
                                :post-processors page-post-process-fns})
      (imagine/wrap-images (:imagine/config config))
      (optimus/wrap #(get-assets config) optimizations/none strategies/serve-live-assets-autorefresh)
      wrap-content-type
      wrap-content-type-utf-8
      prone/wrap-exceptions))
