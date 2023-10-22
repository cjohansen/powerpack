(ns powerpack.hud
  (:require [clojure.core.async :refer [<! chan close! go put! tap untap]]
            [clojure.pprint :as pprint]
            [clojure.string :as str]
            [dumdom.string :as dumdom]
            [html5-walker.core :as html5-walker]
            [powerpack.error-logger :as error-logger]
            [powerpack.highlight :as highlight]))

(def stasis-logo
  [:svg {:xmlns "http://www.w3.org/2000/svg"
         :viewbox "0 1 230 230"
         :xml:space "preserve"}
   [:g [:rect {:y "1" :fill "#5E9985" :width "230" :height "230"}]]
   [:path {:fill "#91C8A3"
           :d "M70.2,99.3c0-2.2,0.1-4.3,0.2-6.3H0v12h70.4C70.3,103.2,70.2,101.3,70.2,99.3z M115,177.5
        c-0.7,0-1.5,0-2.3,0c-2.9,0-6.2-0.1-9.7-0.4V196H35v12h68v23h12v-23h115v-12H115V177.5z M115,35V0h-12v60.4
        c3.6-0.4,7.5-0.5,11.6-0.5c0.1,0,0.3,0,0.4,0V47h116V35H115z"}]
   [:g [:path {:fill "#FFFFFF"
               :d "M120.6,104.4c-12.9-2.5-13.2-2.8-13.2-8c0-4.5,1.4-5.5,8.9-5.5c8.8,0,18.9,1.5,26.5,3.2l3.2-24.3
                c-6.3-2.2-18.6-3.8-31.4-3.8c-27.9,0-38.3,7.7-38.3,33.4c0,24.3,6,28.6,30.8,32.5c13.1,2,13.9,2.8,13.9,8.3c0,5.4-2.3,6.6-10.3,6.6
                c-10.2,0-19.7-1.5-29.9-4.5L75.6,166c10.9,3.7,26.2,5.5,37.1,5.5c28.2,0,39.3-10,39.3-33.7C152,115,147.2,109.5,120.6,104.4z"}]]])

(defn pp [x]
  (with-out-str (pprint/pprint x)))

(defn accordion [title body & [opt]]
  [:details.accordion
   (when (::details-expanded? opt)
     {:open "true"})
   [:summary.h4 title]
   [:div body]])

(defn render-error-hud [{:keys [kind message description errors exception tx data]}]
  [:div.powerpack
   [:div.hud.warn
    [:div.logo stasis-logo]
    [:div.content
     [:h2.h2 message]
     (when description
       [:p description])
     (when errors
       [:ul
        (for [{:keys [message k v]} errors]
          [:li.compact
           [:p message]
           [:pre [:code.language-clojure (str k " " (pr-str v))]]])])
     (when exception
       [:div
        [:h4.h4.error "Exception: " (.getMessage exception)]
        (when (= :powerpack.web/render-page kind)
          [:p [:a {:href "" :onclick "location.reload()"} "Inspect error"]])
        (when-let [data (ex-data exception)]
          (accordion "Exception data" [:pre [:code.language-clojure (pp data)]] (meta data)))
        (when-let [stack (error-logger/get-stack-trace exception)]
          (accordion "Stack trace" [:pre [:code stack]]))])
     (when tx
       (accordion "Transaction data" [:pre [:code.language-clojure (pp tx)]]))
     (when data
       (accordion "Data" [:pre [:code.language-clojure (pp data)]] (meta data)))]]])

(defn render-hud-str [error]
  (-> (render-error-hud error)
      dumdom/render
      (html5-walker/replace-in-document (highlight/get-code-block-highlighters))
      (str/split #"</?body>")
      second))

(defn connect-client [hud]
  (let [client-ch (chan)
        k (random-uuid)
        emit-error (fn [errors]
                     (->> (if-let [error (first errors)]
                            {:kind :powerpack/error
                             :action "render-hud"
                             :markup (render-hud-str error)}
                            {:kind :powerpack/error
                             :action "render-hud"
                             :markup ""})
                          (put! client-ch)))]
    (when (seq @(:errors hud))
      (emit-error @(:errors hud)))
    (add-watch (:errors hud) k
               (fn [_ _ old-errors errors]
                 (when-not (= old-errors errors)
                   (emit-error errors))))
    {:stop (fn []
             (remove-watch (:errors hud) k)
             (close! client-ch))
     :ch client-ch}))

(defn resolve-error-event [errors event]
  (cond-> (remove (comp #{(:id event)} :id) errors)
    (not (:resolved? event))
    (conj event)))

(defn start-watching! [{:keys [error-events]}]
  (let [watching? (atom true)
        err-ch (chan)
        errors (atom [])]
    (tap (:mult error-events) err-ch)
    (go
      (loop []
        (when-let [event (<! err-ch)]
          (swap! errors resolve-error-event event)
          (when @watching? (recur)))))
    {:stop (fn []
             (untap (:mult error-events) err-ch)
             (close! err-ch)
             (reset! watching? false))
     :errors errors}))

(defn stop-watching! [{:keys [stop]}]
  (stop))
