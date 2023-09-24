(ns powerpack.app
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [integrant.core :as ig]
            [integrant.repl]
            [powerpack.db :as db]
            [powerpack.ingest :as ingest]
            [powerpack.web :as web]
            [ring.adapter.jetty :as jetty]))

(defmacro with-timing-info [name exp]
  `(let [start# (System/currentTimeMillis)
         res# ~exp]
     (println "[powerpack.app]" ~name "in" (- (System/currentTimeMillis) start#) "ms")
     res#))

(defmethod ig/init-key :app/config [_ {:keys [config]}]
  config)

(defmethod ig/init-key :app/handler [_ opts]
  (with-timing-info "Created web app"
    (web/create-app opts)))

(defmethod ig/init-key :adapter/jetty [_ {:keys [handler port] :as opts}]
  (with-timing-info (str "Started jetty on port " port)
    (jetty/run-jetty handler (-> opts (dissoc :handler) (assoc :join? false)))))

(defmethod ig/halt-key! :adapter/jetty [_ server]
  (with-timing-info "Stopped jetty"
    (.stop server)))

(defmethod ig/init-key :datomic/conn [_ {:keys [uri schema]}]
  (with-timing-info "Created database"
    (db/create-database uri schema)))

(defmethod ig/init-key :dev/ingestion-watcher [_ opt]
  (with-timing-info "Ingested all data"
    (ingest/ingest-all opt))
  (with-timing-info "Started watcher"
   (ingest/start-watching! opt)))

(defmethod ig/halt-key! :dev/ingestion-watcher [_ watcher]
  (with-timing-info "Stopped watcher"
    (ingest/stop-watching! watcher)))

(defn init! [config {:keys [create-ingest-tx
                            on-ingested
                            render-page
                            page-post-process-fns]}]
  (let [system-config {:datomic/conn {:uri (or (:powerpack/db config)
                                               "datomic:mem://powerpack")
                                      :schema (:datomic/schema config)}
                       :app/config {:config config}
                       :app/handler {:conn (ig/ref :datomic/conn)
                                     :config config
                                     :render-page render-page
                                     :page-post-process-fns page-post-process-fns}
                       :adapter/jetty {:port (or (:ring/port config) 5051)
                                       :handler (ig/ref :app/handler)}
                       :dev/ingestion-watcher {:config config
                                               :conn (ig/ref :datomic/conn)
                                               :create-ingest-tx create-ingest-tx
                                               :on-ingested on-ingested}}]
    (integrant.repl/set-prep! (constantly system-config))))

(defn start []
  (integrant.repl/go))

(defn stop []
  (integrant.repl/halt))

(defn reset []
  (integrant.repl/reset))
