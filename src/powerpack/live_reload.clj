(ns powerpack.live-reload
  (:require [clojure.core.async :refer [<! chan close! go put! tap timeout untap]]
            [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [org.httpkit.server :as http-kit]
            [powerpack.logger :as log]
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
          (if (= body-hash (get-page-hash (handler {:uri uri})))
            (do
              (log/debug "No changes")
              (recur)) ;; keep waiting
            (do
              (log/info "Reload client")
              (put! client-ch (stream-msg msg))))))
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

(defn start-watching! [{:keys [fs-events app-events]}]
  (let [watching? (atom true)
        fs-ch (chan)]
    (tap (:mult fs-events) fs-ch)
    (go
      (loop []
        (when-let [event (<! fs-ch)]
          (when (= :powerpack/edited-source (:kind event))
            (log/debug "Source edited")
            ;; We're currently relying on the fact that Emacs saves files after
            ;; evaluating the entire buffer. This is imprecise and prone to
            ;; races. A small timeout greatly improves the accuracy of this
            ;; hook. Eventually this will be replaced with an nrepl middleware
            ;; that instead reacts to code evaulation.
            (<! (timeout 500))
            (put! (:ch app-events) event))
          (when @watching? (recur)))))
    (fn []
      (untap (:mult fs-events) fs-ch)
      (reset! watching? false))))

(defn stop-watching! [stop]
  (stop))
