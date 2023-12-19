(ns powerpack.dev
  (:require [clojure.core.async :refer [chan close! mult]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.tools.namespace.repl :as repl]
            [imagine.core :as imagine]
            [integrant.core :as ig]
            [integrant.repl.state]
            [integrant.repl]
            [optimus.prime :as optimus]
            [optimus.strategies :as strategies]
            [org.httpkit.server :as server]
            [powerpack.app :as app]
            [powerpack.assets :as assets]
            [powerpack.async :refer [create-watcher]]
            [powerpack.error-logger :as errors]
            [powerpack.hud :as hud]
            [powerpack.ingest :as ingest]
            [powerpack.live-reload :as live-reload]
            [powerpack.logger :as log]
            [powerpack.watcher :as watcher]
            [powerpack.web :as web]
            [prone.middleware :as prone]
            [ring.middleware.content-type :refer [wrap-content-type]]
            [ring.middleware.params :refer [wrap-params]]
            [ring.middleware.resource :refer [wrap-resource]]))

(defn wrap-dev-assets [handler powerpack]
  (if-let [dir (:powerpack/dev-assets-root-path powerpack)]
    (wrap-resource handler dir)
    handler))

(defn get-app-namespaces [powerpack]
  (->> (:powerpack/source-dirs powerpack)
       (mapcat (fn [dir]
                 (->> (.listFiles (io/file dir))
                      (filter #(.isDirectory %))
                      (map #(str/replace (.getPath %) (re-pattern (str "^" dir "/")) "")))))
       set))

(defn create-handler [powerpack opt]
  (-> (web/serve-pages powerpack opt)
      (wrap-dev-assets powerpack)
      (imagine/wrap-images (:imagine/config powerpack))
      (optimus/wrap
       #(assets/get-assets powerpack)
       assets/optimizations
       strategies/serve-live-assets
       {:assets-dirs (:powerpack/resource-dirs powerpack)})
      wrap-content-type
      web/wrap-utf-8
      (live-reload/wrap-live-reload powerpack opt)
      wrap-params
      (prone/wrap-exceptions
       {:app-namespaces (get-app-namespaces powerpack)})))

(defmethod ig/init-key :dev/logger [_ powerpack]
  (log/with-timing :debug "Started logger"
    (log/start-logger (:powerpack/log-level powerpack))))

(defmethod ig/halt-key! :dev/logger [_ logger]
  (log/with-timing :debug "Stopped logger"
    (log/stop-logger logger)))

(defmethod ig/init-key :dev/fs-events [_ _]
  (let [ch (chan)]
    {:ch ch
     :mult (mult ch)}))

(defmethod ig/halt-key! :dev/fs-events [_ {:keys [ch]}]
  (close! ch))

(defmethod ig/init-key :dev/app-events [_ _]
  (let [ch (chan)]
    {:ch ch
     :mult (mult ch)}))

(defmethod ig/halt-key! :dev/app-events [_ {:keys [ch]}]
  (close! ch))

(defmethod ig/init-key :dev/error-events [_ _]
  (let [ch (chan)]
    {:ch ch
     :mult (mult ch)}))

(defmethod ig/halt-key! :dev/error-events [_ {:keys [ch]}]
  (close! ch))

(defmethod ig/init-key :app/handler [_ {:powerpack/keys [app dev]}]
  (log/with-timing :info "Created web app"
    (create-handler app dev)))

(defmethod ig/init-key :app/server [_ opt]
  (let [port (-> opt :powerpack/app :powerpack/port)]
    (log/with-timing :info (str "Started server on port " port)
      (server/run-server (:handler opt) {:port port}))))

(defmethod ig/halt-key! :app/server [_ stop-server]
  (log/with-timing :info "Stopped server"
    (stop-server)))

(defmethod ig/init-key :dev/ingestion-watcher [_ {:powerpack/keys [app dev]}]
  (log/with-timing :debug "Started content watcher"
    (ingest/start-watching! app dev)))

(defmethod ig/halt-key! :dev/ingestion-watcher [_ watcher]
  (log/with-timing :debug "Stopped content watcher"
    (ingest/stop-watching! watcher)))

(defmethod ig/init-key :dev/source-watcher [_ {:powerpack/keys [app dev]}]
  (log/with-timing :debug "Started source code watcher"
    (live-reload/start-watching! app dev)))

(defmethod ig/halt-key! :dev/source-watcher [_ watcher]
  (log/with-timing :debug "Stopped source code watcher"
    (live-reload/stop-watching! watcher)))

(defmethod ig/init-key :dev/file-watcher [_ {:powerpack/keys [app dev]}]
  (log/with-timing :info "Started file system watcher"
    (watcher/start-watching! app dev)))

(defmethod ig/halt-key! :dev/file-watcher [_ watcher]
  (log/with-timing :info "Stopped file system watcher"
    (watcher/stop-watching! watcher)))

(defmethod ig/init-key :dev/error-logger [_ opt]
  (log/with-timing :debug "Started error logger"
    (errors/start-watching! opt)))

(defmethod ig/halt-key! :dev/error-logger [_ watcher]
  (log/with-timing :debug "Stopped error logger"
    (errors/stop-watching! watcher)))

(defmethod ig/init-key :dev/hud [_ opt]
  (log/with-timing :debug "Started HUD"
    (hud/start-watching! opt)))

(defmethod ig/halt-key! :dev/hud [_ watcher]
  (log/with-timing :debug "Stopped HUD"
    (hud/stop-watching! watcher)))

(defmethod ig/init-key :dev/schema-watcher [_ {:keys [fs-events]}]
  (log/with-timing :debug "Started schema watcher"
    (create-watcher [message (:mult fs-events)]
      (when (= :powerpack/edited-schema (:kind message))
        (log/info "Rebooting after schema change")
        (integrant.repl/halt)
        (integrant.repl/go)))))

(defmethod ig/halt-key! :dev/schema-watcher [_ stop-watcher]
  (log/with-timing :debug "Stopped schema watcher"
    (stop-watcher)))

(defmethod ig/init-key :powerpack/app [_ opt]
  (app/create-app (:powerpack/powerpack opt) (:dev/opts opt)))

(defmethod ig/init-key :dev/opts [_ opts]
  opts)

(defmulti configure! (fn [] nil))

(defmethod ig/init-key :powerpack/powerpack [_ _]
  (configure!))

(defn get-app []
  (:powerpack/app integrant.repl.state/system))

(defn get-system-map []
  {;; Dev-time resources
   :dev/app-events {}
   :dev/error-events {}
   :dev/fs-events {}
   :dev/hud {:error-events (ig/ref :dev/error-events)}

   ;; Dev tooling dependency map
   :dev/opts {:app-events (ig/ref :dev/app-events)
              :error-events (ig/ref :dev/error-events)
              :fs-events (ig/ref :dev/fs-events)
              :hud (ig/ref :dev/hud)}

   ;; Wire up user app spec
   :powerpack/powerpack {}
   :powerpack/app {:powerpack/powerpack (ig/ref :powerpack/powerpack)
                   :dev/opts (ig/ref :dev/opts)}

   ;; Watcher processes
   :dev/file-watcher {:powerpack/app (ig/ref :powerpack/app)
                      :powerpack/dev (ig/ref :dev/opts)}

   :dev/source-watcher {:powerpack/app (ig/ref :powerpack/app)
                        :powerpack/dev (ig/ref :dev/opts)}

   :dev/schema-watcher (ig/ref :dev/opts)

   :dev/ingestion-watcher {:powerpack/app (ig/ref :powerpack/app)
                           :powerpack/dev (ig/ref :dev/opts)}

   ;; Console logging
   :dev/logger (ig/ref :powerpack/app)
   :dev/error-logger (ig/ref :dev/opts)   

   ;; Web app
   :app/handler {:powerpack/app (ig/ref :powerpack/app)
                 :powerpack/dev (ig/ref :dev/opts)}
   :app/server {:powerpack/app (ig/ref :powerpack/app)
                :handler (ig/ref :app/handler)}})

(integrant.repl/set-prep! get-system-map)

(defn start []
  (integrant.repl/go)
  (app/start (:powerpack/app integrant.repl.state/system))
  (let [msg (str "Powerpack started on port "
                 (:powerpack/port (:powerpack/powerpack integrant.repl.state/system)))]
    (log/info msg)
    msg))

(defn stop []
  (integrant.repl/halt)
  :powerpack/stopped)

(defn reset []
  (stop)
  (repl/refresh :after 'powerpack.dev/start)
  (let [msg (str "Powerpack restarted on port "
                 (:powerpack/port (:powerpack/powerpack integrant.repl.state/system)))]
    (log/info msg)
    msg))
