(ns powerpack.live-reload
  (:require [clojure.core.async :refer [<! chan close! go put! tap untap]]
            [clojure.string :as str]
            [nextjournal.beholder :as beholder]
            [org.httpkit.server :as http-kit]
            [powerpack.logger :refer [log]]
            [powerpack.web :as web]))

(defn stream-msg [payload]
  (str "data:" (pr-str payload) "\n\n"))

(defn create-watcher [{:keys [ch-ch-ch-changes]} handler uri body-hash]
  (let [client-ch (chan)
        msg-ch (chan)]
    (tap (:mult ch-ch-ch-changes) msg-ch)
    (go
      (loop []
        (when-let [msg (<! msg-ch)]
          (let [updated-page (handler {:uri uri})
                updated-hash (str (hash (:body updated-page)))]
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
  (let [channel (create-watcher opt handler
                                (get-in req [:params "url"])
                                (get-in req [:params "hash"]))]
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

(defn script [config body]
  (str "\n<script type=\"text/javascript\">new EventSource(\""
       (get-route config)
       "?hash=" (hash body)
       "&url=\" + location.pathname).onmessage = function () { location.reload(true); };</script>"))

(defn inject-script [body config]
  (if (re-find #"</body>" body)
    (str/replace body "</body>" (str (script config body) "</body>"))
    (str body (script config body))))

(defn handle-request [handler opt req]
  (if (= (get-route (:config opt)) (:uri req))
    (live-reload-handler opt handler req)
    (let [response (handler req)]
      (if (and (some->> (web/get-content-type response)
                        (re-find #"html"))
               (string? (:body response)))
        (update response :body inject-script (:config opt))
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
       (put! (:ch ch-ch-ch-changes) {:type type :path path-str})))
   (:powerpack/source-dirs config)))

(defn stop-watching! [watcher]
  (beholder/stop watcher))
