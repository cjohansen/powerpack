(ns powerpack.app
  (:require [clojure.core.async :refer [chan close! mult]]
            [clojure.java.io :as io]
            [clojure.tools.namespace.repl :as repl]
            [imagine.core :as imagine]
            [integrant.core :as ig]
            [integrant.repl]
            [optimus.optimizations :as optimizations]
            [optimus.prime :as optimus]
            [optimus.strategies :as strategies]
            [org.httpkit.server :as server]
            [powerpack.db :as db]
            [powerpack.ingest :as ingest]
            [powerpack.live-reload :as live-reload]
            [powerpack.logger :as logger]
            [powerpack.watcher :as watcher]
            [powerpack.web :as web]
            [prone.middleware :as prone]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.params :refer [wrap-params]]))

(defn create-handler [{:keys [conn config render-page page-post-process-fns] :as opts}]
  (-> (web/serve-pages conn {:render-page render-page
                             :post-processors page-post-process-fns})
      (imagine/wrap-images (:imagine/config config))
      (optimus/wrap #(web/get-assets config) optimizations/none strategies/serve-live-assets-autorefresh)
      wrap-content-type
      web/wrap-utf-8
      (web/wrap-system {:config config :conn conn})
      (live-reload/wrap-live-reload opts)
      wrap-params))

(defmacro with-timing-info [name exp]
  `(let [start# (System/currentTimeMillis)
         res# ~exp]
     (println "[powerpack.app]" ~name "in" (- (System/currentTimeMillis) start#) "ms")
     res#))

(defmethod ig/init-key :app/config [_ {:keys [config]}]
  config)

(defmethod ig/init-key :app/logger [_ {:keys [config]}]
  (with-timing-info "Started logger"
    (logger/start-logger (:powerpack/log-level config))))

(defmethod ig/halt-key! :app/logger [_ logger]
  (with-timing-info "Stopped logger"
    (logger/stop-logger logger)))

(defmethod ig/init-key :dev/fs-events [_ _opt]
  (let [ch (chan)]
    {:ch ch
     :mult (mult ch)}))

(defmethod ig/halt-key! :dev/fs-events [_ {:keys [ch]}]
  (close! ch))

(defmethod ig/init-key :dev/app-events [_ _opt]
  (let [ch (chan)]
    {:ch ch
     :mult (mult ch)}))

(defmethod ig/halt-key! :dev/app-events [_ {:keys [ch]}]
  (close! ch))

(defmethod ig/init-key :app/handler [_ opts]
  (with-timing-info "Created web app"
    (-> (create-handler opts)
        prone/wrap-exceptions)))

(defmethod ig/init-key :app/server [_ {:keys [handler port]}]
  (with-timing-info (str "Started server on port " port)
    (server/run-server handler {:port port})))

(defmethod ig/halt-key! :app/server [_ stop-server]
  (with-timing-info "Stopped jetty"
    (stop-server)))

(defmethod ig/init-key :datomic/conn [_ {:keys [uri schema-file]}]
  (with-timing-info "Created database"
    (->> (read-string (slurp (io/file schema-file)))
         (db/create-database uri))))

(defmethod ig/init-key :dev/ingestion-watcher [_ opt]
  (with-timing-info "Ingested all data"
    (ingest/ingest-all opt))
  (with-timing-info "Started content watcher"
   (ingest/start-watching! opt)))

(defmethod ig/halt-key! :dev/ingestion-watcher [_ watcher]
  (with-timing-info "Stopped content watcher"
    (ingest/stop-watching! watcher)))

(defmethod ig/init-key :dev/source-watcher [_ opt]
  (with-timing-info "Started source code watcher"
    (live-reload/start-watching! opt)))

(defmethod ig/halt-key! :dev/source-watcher [_ watcher]
  (with-timing-info "Stopped source code watcher"
    (live-reload/stop-watching! watcher)))

(defmethod ig/init-key :dev/file-watcher [_ opt]
  (with-timing-info "Started file watcher"
    (watcher/start-watching! opt)))

(defmethod ig/halt-key! :dev/file-watcher [_ watcher]
  (with-timing-info "Stopped file watcher"
    (watcher/stop-watching! watcher)))

(def config-defaults
  {:powerpack/source-dirs ["src"]
   :powerpack/live-reload-route "/powerpack/live-reload"
   :powerpack/db "datomic:mem://powerpack"
   :datomic/schema-file "resources/schema.edn"})

(defn with-defaults [x defaults]
  (merge x (into {} (for [k (keys defaults)]
                      [k (or (k x) (k defaults))]))))

(defn create-app [params]
  (update params :config with-defaults config-defaults))

(defn get-system-map [{:keys [config
                              create-ingest-tx
                              on-ingested
                              render-page
                              page-post-process-fns]}]
  {:datomic/conn {:uri (:powerpack/db config)
                  :schema-file (:datomic/schema-file config)
                  :schema (:datomic/schema config)}
   :app/logger {:config config}
   :app/config {:config config}
   :app/handler {:conn (ig/ref :datomic/conn)
                 :config config
                 :render-page render-page
                 :page-post-process-fns page-post-process-fns
                 :ch-ch-ch-changes (ig/ref :dev/app-events)
                 :logger (ig/ref :app/logger)}
   :app/server {:port (or (:powerpack.server/port config) 5051)
                :handler (ig/ref :app/handler)}

   :dev/fs-events {}
   :dev/app-events {}

   :dev/file-watcher {:config config
                      :fs-events (ig/ref :dev/fs-events)
                      :app-events (ig/ref :dev/app-events)}

   :dev/source-watcher {:fs-events (ig/ref :dev/fs-events)
                        :app-events (ig/ref :dev/app-events)}

   :dev/ingestion-watcher {:config config
                           :conn (ig/ref :datomic/conn)
                           :create-ingest-tx create-ingest-tx
                           :on-ingested on-ingested
                           :fs-events (ig/ref :dev/fs-events)
                           :ch-ch-ch-changes (ig/ref :dev/app-events)}})

(defn start [app]
  (integrant.repl/set-prep! (partial get-system-map app))
  (apply repl/set-refresh-dirs (-> app :config :powerpack/source-dirs))
  (integrant.repl/go))

(defn stop []
  (integrant.repl/halt))

(defn reset []
  (stop)
  (repl/refresh :after 'integrant.repl/go))
