(ns powerpack.app
  (:require [clojure.core.async :refer [<! chan close! go mult tap untap]]
            [clojure.java.io :as io]
            [clojure.tools.namespace.repl :as repl]
            [imagine.core :as imagine]
            [integrant.core :as ig]
            [integrant.repl]
            [integrant.repl.state]
            [optimus.optimizations :as optimizations]
            [optimus.prime :as optimus]
            [optimus.strategies :as strategies]
            [org.httpkit.server :as server]
            [powerpack.db :as db]
            [powerpack.error-logger :as errors]
            [powerpack.hud :as hud]
            [powerpack.ingest :as ingest]
            [powerpack.live-reload :as live-reload]
            [powerpack.logger :as log]
            [powerpack.watcher :as watcher]
            [powerpack.web :as web]
            [prone.middleware :as prone]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.params :refer [wrap-params]]))

(defn optimizations [assets _options]
  (-> assets
      optimizations/add-cache-busted-expires-headers))

(defn create-handler [{:keys [conn config] :as opts}]
  (-> (web/serve-pages opts)
      (imagine/wrap-images (:imagine/config config))
      (optimus/wrap
       #(web/get-assets config)
       optimizations
       strategies/serve-live-assets-autorefresh
       {:assets-dir (first (:powerpack/resource-dirs config))})
      wrap-content-type
      web/wrap-utf-8
      (web/wrap-system {:config config :conn conn})
      (live-reload/wrap-live-reload opts)
      wrap-params))

(defmacro with-timing-info [level name exp]
  `(let [start# (System/currentTimeMillis)
         res# ~exp]
     (log/log ~level [~name "in" (- (System/currentTimeMillis) start#) "ms"])
     res#))

(defmethod ig/init-key :app/config [_ {:keys [config]}]
  config)

(defmethod ig/init-key :app/logger [_ {:keys [config]}]
  (with-timing-info :debug "Started logger"
    (log/start-logger (:powerpack/log-level config))))

(defmethod ig/halt-key! :app/logger [_ logger]
  (with-timing-info :debug "Stopped logger"
    (log/stop-logger logger)))

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

(defmethod ig/init-key :dev/error-events [_ _opt]
  (let [ch (chan)]
    {:ch ch
     :mult (mult ch)}))

(defmethod ig/halt-key! :dev/error-events [_ {:keys [ch]}]
  (close! ch))

(defmethod ig/init-key :app/handler [_ opts]
  (with-timing-info :info "Created web app"
    (-> (create-handler opts)
        prone/wrap-exceptions)))

(defmethod ig/init-key :app/server [_ {:keys [handler config]}]
  (let [port (or (:powerpack.server/port config) 5051)]
    (with-timing-info :info (str "Started server on port " port)
      (server/run-server handler {:port port}))))

(defmethod ig/halt-key! :app/server [_ stop-server]
  (with-timing-info :info "Stopped server"
    (stop-server)))

(defmethod ig/init-key :datomic/conn [_ {:keys [config]}]
  (with-timing-info :info "Created database"
    (->> (or (:datomic/schema config)
             (read-string (slurp (io/file (:datomic/schema-file config)))))
         (db/create-database (:powerpack/db config)))))

(defmethod ig/init-key :dev/ingestion-watcher [_ opt]
  (with-timing-info :info "Ingested all data"
    (ingest/ingest-all opt))
  (with-timing-info :debug "Started content watcher"
   (ingest/start-watching! opt)))

(defmethod ig/halt-key! :dev/ingestion-watcher [_ watcher]
  (with-timing-info :debug "Stopped content watcher"
    (ingest/stop-watching! watcher)))

(defmethod ig/init-key :dev/source-watcher [_ opt]
  (with-timing-info :debug "Started source code watcher"
    (live-reload/start-watching! opt)))

(defmethod ig/halt-key! :dev/source-watcher [_ watcher]
  (with-timing-info :debug "Stopped source code watcher"
    (live-reload/stop-watching! watcher)))

(defmethod ig/init-key :dev/file-watcher [_ opt]
  (with-timing-info :info "Started file system watcher"
    (watcher/start-watching! opt)))

(defmethod ig/halt-key! :dev/file-watcher [_ watcher]
  (with-timing-info :info "Stopped file system watcher"
    (watcher/stop-watching! watcher)))

(defmethod ig/init-key :dev/error-logger [_ opt]
  (with-timing-info :debug "Started error logger"
    (errors/start-watching! opt)))

(defmethod ig/halt-key! :dev/error-logger [_ watcher]
  (with-timing-info :debug "Stopped error logger"
    (errors/stop-watching! watcher)))

(defmethod ig/init-key :dev/hud [_ opt]
  (with-timing-info :debug "Started HUD"
    (hud/start-watching! opt)))

(defmethod ig/halt-key! :dev/hud [_ watcher]
  (with-timing-info :debug "Stopped HUD"
    (hud/stop-watching! watcher)))

(defmethod ig/init-key :dev/schema-watcher [_ {:keys [fs-events]}]
  (with-timing-info :debug "Started schema watcher"
    (let [watching? (atom true)
          fs-ch (chan)]
      (tap (:mult fs-events) fs-ch)
      (go
        (loop []
          (when (= :powerpack/edited-schema (:kind (<! fs-ch)))
            (log/info "Rebooting after schema change")
            (integrant.repl/halt)
            (integrant.repl/go))
          (when @watching? (recur))))
      (fn []
        (untap (:mult fs-events) fs-ch)
        (reset! watching? false)))))

(defmethod ig/halt-key! :dev/schema-watcher [_ stop-watcher]
  (with-timing-info :debug "Stopped schema watcher"
    (stop-watcher)))

(def config-defaults
  {:powerpack/source-dirs ["src"]
   :powerpack/resource-dirs ["resources"]
   :powerpack/live-reload-route "/powerpack/live-reload"
   :powerpack/db "datomic:mem://powerpack"
   :powerpack/content-file-suffixes ["md" "edn"]
   :datomic/schema-file "resources/schema.edn"})

(defn with-defaults [x defaults]
  (merge x (into {} (for [k (keys defaults)]
                      [k (or (k x) (k defaults))]))))

(defmethod ig/init-key :powerpack/config [_ {:keys [config]}]
  (with-defaults config config-defaults))

(defmethod ig/init-key :powerpack/create-ingest-tx [_ {:keys [create-ingest-tx]}]
  create-ingest-tx)

(defmethod ig/init-key :powerpack/render-page [_ {:keys [render-page]}]
  render-page)

(defmethod ig/init-key :powerpack/page-post-process-fns [_ {:keys [page-post-process-fns]}]
  page-post-process-fns)

(defmethod ig/init-key :powerpack/on-ingested [_ {:keys [on-ingested]}]
  on-ingested)

(defmethod ig/init-key :powerpack/get-page [_ {:keys [get-page]}]
  get-page)

(defmethod ig/init-key :powerpack/context [_ {:keys [context]}]
  context)

(defn get-system-map []
  {:powerpack/app {}
   :powerpack/config (ig/ref :powerpack/app)
   :powerpack/create-ingest-tx (ig/ref :powerpack/app)
   :powerpack/render-page (ig/ref :powerpack/app)
   :powerpack/page-post-process-fns (ig/ref :powerpack/app)
   :powerpack/on-ingested (ig/ref :powerpack/app)
   :powerpack/get-page (ig/ref :powerpack/app)
   :powerpack/context (ig/ref :powerpack/app)

   :datomic/conn {:config (ig/ref :powerpack/config)}
   :app/logger {:config (ig/ref :powerpack/config)}
   :app/config {:config (ig/ref :powerpack/config)}
   :app/handler {:conn (ig/ref :datomic/conn)
                 :config (ig/ref :powerpack/config)
                 :render-page (ig/ref :powerpack/render-page)
                 :page-post-process-fns (ig/ref :powerpack/page-post-process-fns)
                 :get-page (ig/ref :powerpack/get-page)
                 :context (ig/ref :powerpack/context)
                 :ch-ch-ch-changes (ig/ref :dev/app-events)
                 :logger (ig/ref :app/logger)
                 :hud (ig/ref :dev/hud)}
   :app/server {:config (ig/ref :powerpack/config)
                :handler (ig/ref :app/handler)}

   :dev/fs-events {}
   :dev/app-events {}
   :dev/error-events {}

   :dev/file-watcher {:config (ig/ref :powerpack/config)
                      :fs-events (ig/ref :dev/fs-events)}

   :dev/source-watcher {:fs-events (ig/ref :dev/fs-events)
                        :app-events (ig/ref :dev/app-events)}

   :dev/schema-watcher {:fs-events (ig/ref :dev/fs-events)}

   :dev/ingestion-watcher {:ch-ch-ch-changes (ig/ref :dev/app-events)
                           :config (ig/ref :powerpack/config)
                           :conn (ig/ref :datomic/conn)
                           :create-ingest-tx (ig/ref :powerpack/create-ingest-tx)
                           :error-events (ig/ref :dev/error-events)
                           :fs-events (ig/ref :dev/fs-events)
                           :on-ingested (ig/ref :powerpack/on-ingested)}

   :dev/error-logger {:error-events (ig/ref :dev/error-events)}
   :dev/hud {:error-events (ig/ref :dev/error-events)}})

(integrant.repl/set-prep! get-system-map)

(defn start []
  (integrant.repl/go)
  (apply repl/set-refresh-dirs
         (-> integrant.repl.state/system
             :app/config
             :powerpack/source-dirs)))

(defn stop []
  (integrant.repl/halt))

(defn reset []
  (stop)
  (repl/refresh :after 'integrant.repl/go))
