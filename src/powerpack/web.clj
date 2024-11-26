(ns powerpack.web
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datomic-type-extensions.api :as d]
            [dev.onionpancakes.chassis.core :as chassis]
            [html5-walker.walker :as html5-walker]
            [powerpack.assets :as assets]
            [powerpack.errors :as errors]
            [powerpack.hiccup :as hiccup]
            [ring.middleware.content-type :as content-type]
            [ring.util.mime-type :as mime-type]))

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

(def content-types
  {:edn "application/edn"
   :json "application/json"
   :html "text/html"})

(defn ensure-content-type [req res]
  (-> (let [content-type (get-content-type res)]
        (or
         (when (and (:content-type res) (empty? content-type))
           (-> res
               (assoc-in [:headers "Content-Type"] (content-types (:content-type res)))))
         (when (and (string? (:body res)) (empty? content-type))
           (assoc-in res [:headers "Content-Type"] (or (mime-type/ext-mime-type (:uri req)) "text/html")))
         (content-type/content-type-response res req)))
      (dissoc :content-type)))

(defn embellish-hiccup [context page hiccup]
  (cond->> hiccup
    (= "html" (hiccup/get-tag-name hiccup))
    (hiccup/embellish-document context page)))

(defn get-redirect-location [response]
  (when-let [header (->> (keys (:headers response))
                         (filter #(= "location" (str/lower-case %)))
                         first)]
    (get-in response [:headers header])))

(defn add-redirect-body [response]
  (let [location (get-redirect-location response)]
    (-> response
        (assoc-in [:headers "Content-Type"] "text/html")
        (assoc :body (format "<!DOCTYPE html><html><head><meta http-equiv=\"refresh\" content=\"0; url=%s\"></head><body><a href=\"%s\">Redirect</a></body></html>"
                             location location)))))

(defn prepare-response [context page response]
  (let [content-type (get-content-type response)]
    (cond
      (string? (:body response))
      response

      (instance? dev.onionpancakes.chassis.core.RawString (:body response))
      (update response :body str)

      (and (nil? content-type)
           (get-redirect-location response))
      (add-redirect-body response)

      (or (and (nil? content-type)
               (hiccup/hiccup? (:body response)))
          (and (string? content-type)
               (re-find #"text/html" content-type)))
      (update response :body (comp hiccup/render-html #(embellish-hiccup context page %)))

      (or (= :json (:content-type response))
          (and (string? content-type)
               (re-find #"application/json" content-type)))
      (-> response
          (update :body json/write-str)
          (assoc :content-type :json))

      (or (and (contains? #{nil :edn} (:content-type response))
               (not (string? content-type)))
          (and (string? content-type)
               (re-find #"application/edn" content-type)))
      (-> response
          (update :body pr-str)
          (assoc :content-type :edn))

      :else
      response)))

(defn validate-headers! [opt ctx res]
  (if-let [invalid-ks (->> res :headers keys (remove string?) seq)]
    (->> {:uri (:uri ctx)
          :message (str "Page has "
                        (if (every? keyword? invalid-ks) "keyword" "non-string")
                        " keys in :headers that will be ignored by ring")
          :data (with-meta
                  {:uri (:uri ctx)
                   :invalid-headers invalid-ks}
                  {:powerpack.hud/details-expanded? true})
          :kind ::headers
          :id [::headers (:uri ctx)]}
         (errors/report-error opt))
    (errors/resolve-error opt [::headers (:uri ctx)])))

(defn finalize-response [opt ctx res]
  (validate-headers! opt ctx res)
  (update res :headers #(into {} (filter (comp string? key) %))))

(defn get-response-map [req page rendered]
  (->> (if (and (map? rendered)
                (or (:status rendered)
                    (:headers rendered)
                    (:body rendered)))
         rendered
         {:body rendered})
       (prepare-response req page)
       (ensure-content-type req)
       (merge {:status 200})))

(defn render-error [req status message]
  (if (re-find #"\.js$" (:uri req))
    {:status status
     :body (if-let [file (io/resource (str status ".js"))]
             (slurp file)
             (str "alert('" (:uri req) " " status  ": " message "');"))
     :headers {"Content-Type" "application/javascript"}}
    {:status status
     :body (if-let [file (io/resource (str status ".html"))]
             (slurp file)
             message)
     :headers {"Content-Type" "text/html"}}))

(defn render-page [powerpack context page & [opt]]
  (try
    (let [render-page* (:powerpack/render-page powerpack)
          res (->> (render-page* context page)
                   (get-response-map context page)
                   (finalize-response opt context))]
      (errors/resolve-error opt [::render-page (:uri context)])
      res)
    (catch Throwable e
      (->> {:exception e
            :uri (:uri context)
            :message (str "Failed to render page " (:uri context))
            :kind ::render-page
            :id [::render-page (:uri context)]}
           (errors/report-error opt))
      (throw (ex-info "Failed to render page" {:uri (:uri context)} e)))))

(defn handle-request [req powerpack opt]
  (let [context (-> (when (ifn? (:powerpack/get-context powerpack))
                      ((:powerpack/get-context powerpack)))
                    (assoc :uri (:uri req))
                    (assoc :powerpack/app powerpack)
                    (assoc :optimus-assets (:optimus-assets req))
                    (merge (select-keys req [:app/db :powerpack/live-reload?])))
        context (cond-> context
                  (:i18n/dictionaries powerpack)
                  (assoc :i18n/dictionaries @(:i18n/dictionaries powerpack)))]
    (-> (if-let [page (d/entity (:app/db req) [:page/uri (:uri req)])]
          (render-page powerpack context page opt)
          (render-error req 404 "Page not found"))
        (post-process-page
         context
         (concat (:powerpack/page-post-process-fns powerpack)
                 [assets/get-markup-url-optimizers])))))

(defn serve-pages [powerpack opt]
  (fn [req]
    (-> req
        (assoc :app/db (d/db (:datomic/conn powerpack)))
        (handle-request powerpack opt))))
