(ns powerpack.live-reload
  (:require [clojure.core.async :refer [<! chan close! go put! tap untap]]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [nextjournal.beholder :as beholder]
            [org.httpkit.server :as http-kit]
            [powerpack.logger :refer [log]]
            [powerpack.web :as web]))

(defn stream-msg [payload]
  (str "data:" (json/write-str payload) "\n\n"))

(defn get-page-hash [{:keys [body]}]
  (str (hash body)))

(defn create-watcher [{:keys [ch-ch-ch-changes]} handler uri body-hash]
  (let [client-ch (chan)
        msg-ch (chan)]
    (tap (:mult ch-ch-ch-changes) msg-ch)
    (go
      (loop []
        (when-let [msg (<! msg-ch)]
          (let [updated-hash (get-page-hash (handler {:uri uri}))]
            (if (= body-hash updated-hash)
              (do
                (log "No difference" uri)
                (recur)) ;; keep waiting
              (do
                (log "Reload" uri)
                (put! client-ch (stream-msg msg)))))))
      (untap (:mult ch-ch-ch-changes) msg-ch)
      (close! msg-ch)
      (close! client-ch))
    client-ch))

(defn live-reload-handler [opt handler req]
  (let [{:strs [uri hash]} (:params req)
        channel (if (not= hash (get-page-hash (handler {:uri uri})))
                  (go (stream-msg {:type :client-outdated}))
                  (create-watcher opt handler uri hash))]
    (http-kit/as-channel
     req
     {:on-open
      (fn [ch]
        (go
          (http-kit/send! ch {:status 200
                              :headers {"Content-Type" "text/event-stream"}
                              :body (<! channel)})))})))

(defn get-route [config]
  (:powerpack/live-reload-route config))

(defn script [config res]
  (str "\n<script type=\"text/javascript\">"
       (-> (slurp (io/resource "powerpack/live-reload.js"))
           (str/replace #"\{\{route\}\}" (get-route config))
           (str/replace #"\{\{hash\}\}" (get-page-hash res)))
       "</script>"))

(defn inject-script [res config]
  (update res :body
          #(if (re-find #"</body>" %)
             (str/replace % "</body>" (str (script config res) "</body>"))
             (str % (script config %)))))

(defn handle-request [handler opt req]
  (if (= (get-route (:config opt)) (:uri req))
    (live-reload-handler opt handler req)
    (let [response (handler req)]
      (if (and (some->> (web/get-content-type response)
                        (re-find #"html"))
               (string? (:body response)))
        (inject-script response (:config opt))
        response))))

(defn wrap-live-reload [handler opt]
  (fn [req]
    (handle-request handler opt req)))

(defn start-watching! [{:keys [config ch-ch-ch-changes]}]
  (apply
   beholder/watch
   (fn [{:keys [type path]}]
     (let [path-str (.getAbsolutePath (.toFile path))]
       (log "File changed" type path-str)
       (put! (:ch ch-ch-ch-changes)
             {:type :code-changed
              :path path-str})))
   (:powerpack/source-dirs config)))

(defn stop-watching! [watcher]
  (beholder/stop watcher))
