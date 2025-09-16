(ns powerpack.live-reload
  (:require [clojure.core.async :refer [<! chan close! go put! tap timeout]]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [integrant.core :as ig]
            [integrant.repl.state :as irs]
            [org.httpkit.server :as http-kit]
            [powerpack.async :as async]
            [powerpack.errors :as errors]
            [powerpack.hud :as hud]
            [powerpack.i18n :as i18n]
            [powerpack.logger :as log]
            [powerpack.web :as web]))

(defn stream-msg [payload]
  (str "data:" (json/write-str payload) "\n\n"))

(defn get-page-hash [{:keys [body]}]
  (str (hash body)))

(defn get-uri-hash [handler uri]
  (try
    (get-page-hash (handler {:uri uri :powerpack/live-reload? true}))
    (catch Exception _e
      nil)))

(defn create-watcher [{:keys [app-events hud]} handler uri body-hash]
  (let [client-ch (chan)
        app-ch (chan)
        hud-watcher (hud/connect-client hud)]
    (tap (:mult app-events) app-ch)
    (go
      (log/debug "Started client watcher hud loop")
      (loop []
        (when-let [msg (<! (:ch hud-watcher))]
          (put! client-ch (stream-msg msg))
          (recur)))
      (log/debug "Exited hud-watcher loop"))
    (go
      (log/debug "Started client watcher app loop")
      (loop []
        (when-let [msg (<! app-ch)]
          (log/debug "Client watcher received app event" msg)
          (if (= "reload" (:action msg))
            (if (= body-hash (or (get-uri-hash handler uri)
                                 ;; If it crashes, don't reload
                                 body-hash))
              (do
                (log/debug "No changes")
                (when (put! client-ch (stream-msg (dissoc msg :action)))
                  (recur))) ;; keep waiting if client is still around
              (do
                (log/info "Reload client")
                (put! client-ch (stream-msg msg))))
            (do
              (log/debug "Notify client")
              (when (put! client-ch (stream-msg msg))
                (recur))))))
      (log/debug "Exited client watcher loop")
      (async/untap!! (:mult app-events) app-ch)
      (close! app-ch)
      ((:stop hud-watcher))
      (close! client-ch)
      (log/debug "Client watcher properly cleaned up"))
    client-ch))

(defn live-reload-handler [opt handler req]
  (log/debug "Accepted connection to live-reload-handler")
  (let [{:strs [uri hash]} (:params req)
        channel (if (not= hash (or (get-uri-hash handler uri)
                                   ;; If it crashes, don't reload
                                   hash))
                  (go (stream-msg {:type :client-outdated
                                   :action "reload"}))
                  (create-watcher opt handler uri hash))]
    (http-kit/as-channel
     req
     {:on-close
      (fn [& _args]
        (close! channel))
      :on-open
      (fn [ch]
        (go
          (loop []
            (let [msg (<! channel)]
              (http-kit/send! ch {:status 200
                                  :headers {"Content-Type" "text/event-stream"}
                                  :body msg} (nil? msg))
              (when msg
                (recur))))
          (log/debug "Exited live-reload-handler loop")))})))

(defn get-route [config]
  (:powerpack/live-reload-route config))

(defn script [powerpack res]
  (str "\n<script type=\"text/javascript\">"
       (-> (slurp (io/resource "powerpack/live-reload.js"))
           (str/replace #"\{\{route\}\}" (get-route powerpack))
           (str/replace #"\{\{hash\}\}" (get-page-hash res)))
       (slurp (io/resource "powerpack/prism.js"))
       "</script>"))

(defn inject-script [res powerpack]
  (update res :body
          #(if (re-find #"</body>" %)
             (str/replace % "</body>" (str (script powerpack res) "</body>"))
             (str % (script powerpack %)))))

(defn inject-css [res]
  (let [styles (str "<style type=\"text/css\">"
                    (slurp (io/resource "powerpack/powerpack.css"))
                    "</style>")]
    (update res :body
            #(if (re-find #"</head>" %)
               (str/replace % "</head>" (str styles "</head>"))
               (str % styles)))))

(defn handle-request [handler powerpack opt req]
  (let [req (assoc req :powerpack/live-reload? true)]
    (if (= (get-route powerpack) (:uri req))
      (live-reload-handler opt handler req)
      (let [response (handler req)]
        (if (and (some->> (web/get-content-type response)
                          (re-find #"html"))
                 (string? (:body response)))
          (-> response
              (inject-script powerpack)
              inject-css)
          response)))))

(defn wrap-live-reload [handler powerpack opt]
  (fn [req]
    (handle-request handler powerpack opt req)))

(defn get-ns [clojure-code-s]
  (some->> (re-find #"(?s)ns[\s]+([^\s\)]+)" clojure-code-s) second symbol))

(defmulti process-event (fn [e _powerpack _opt] (:kind e)))

(defmethod process-event :default [e _powerpack _opt]
  (log/debug "No related side-effects to carry out" e))

(defn ->comparable [data]
  (walk/postwalk
   #(cond-> %
      (instance? java.util.regex.Pattern %)
      str)
   data))

(defn config-changed? [a b]
  (not= (->comparable a) (->comparable b)))

(defn powerpack-config-changed? []
  (config-changed? (ig/init-key :powerpack/powerpack nil) (:powerpack/powerpack irs/system)))

(defmethod process-event :powerpack/edited-source [{:keys [path]} _powerpack opt]
  (log/debug "Source edited, reloading namespace")
  (let [file (io/file path)]
    (when-let [source-namespace (and (.exists file) (get-ns (slurp file)))]
      (try
        (require source-namespace :reload)
        (errors/resolve-error opt [::reload-namespace source-namespace])
        (when (powerpack-config-changed?)
          (put! (:ch (:fs-events opt))
                {:kind :powerpack/edited-powerpack-config
                 :action "reload"}))
        (catch Exception e
          (log/debug "Failed to reload namespace"
                     {:path path
                      :ns source-namespace})
          (->> {:exception e
                :source-namespace source-namespace
                :file-name path
                :message (str "Failed to reload namespace " source-namespace)
                :kind ::reload-namespace
                :id [::reload-namespace source-namespace]}
               (errors/report-error opt)))))))

(defmethod process-event :powerpack/edited-dictionary [_e powerpack opt]
  (log/debug "Dictionary edited, reloading dictionaries")
  (reset! (:i18n/dictionaries powerpack) (i18n/load-dictionaries powerpack opt)))

(defn start-watching! [powerpack {:keys [fs-events app-events] :as opt}]
  (let [watching? (atom true)
        last-event (atom ::none)
        fs-ch (chan)]
    (tap (:mult fs-events) fs-ch)
    (go
      (loop []
        (when-let [event (<! fs-ch)]
          (reset! last-event event)
          (when-let [wait (:wait (process-event event powerpack opt))]
            (<! (timeout wait)))
          (when (:action event)
            (put! (:ch app-events) event))
          (when @watching? (recur))))
      (if @watching?
        (log/error "Unexpected exit from live-reload watcher"
                   (pr-str {:last-event @last-event}))
        (log/debug "Exited live-reload watcher loop")))
    (fn []
      (async/untap!! (:mult fs-events) fs-ch)
      (close! fs-ch)
      (reset! watching? false))))

(defn stop-watching! [stop]
  (stop))
