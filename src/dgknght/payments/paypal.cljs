(ns dgknght.payments.paypal
  (:refer-clojure :exclude [js->clj])
  (:require [cljs.core :as c]
            [cljs.core.async :as a]
            [cljs.spec.alpha :as s]
            [camel-snake-kebab.core :refer [->kebab-case-keyword]]
            [camel-snake-kebab.extras :refer [transform-keys]]))

(def ^:private js->clj
  (comp (partial transform-keys ->kebab-case-keyword)
        c/js->clj))

(defn- ->promise
  [xf args]
  (js/Promise.
    (fn [res rej]
      (let [c (a/promise-chan xf rej)
            yielded? (atom false)]
        ; In the unit tests, the channel yields the value
        ; immediately. In the browser, the channel doesn't
        ; yield the value until the channel is closed.
        (a/go (a/<! (a/timeout 5000)) ; TODO: make this configurable
              (when-not @yielded?
                (a/close! c)))
        (a/go
          (let [r (a/<! c)]
            (reset! yielded? true)
            (res r)))
        (a/go
          (a/>! c args))))))

(defn- buttons-config
  [{:keys [create-order
           create-subscription
           on-approve
           on-cancel
           style]}]
  (clj->js
    (cond-> {"onApprove" (fn [data actions]
                           (->promise on-approve
                                      {:data (js->clj data)
                                       :actions (js->clj actions)}))}
      create-order (assoc "createOrder"
                          (fn [data actions]
                            (->promise create-order
                                       {:data (js->clj data)
                                        :actions (js->clj actions)})))
      create-subscription (assoc
                            "createSubscription"
                            (fn [data actions]
                              (->promise create-subscription
                                         {:data (js->clj data)
                                          :actions (js->clj actions)})))
      on-cancel (assoc
                  "onCancel" (fn [data]
                               (->promise on-cancel
                                          {:data (js->clj data)})))
      style (assoc "style" style))))

(s/def ::element-id string?)
(s/def ::on-approve fn?)
(s/def ::on-cancel fn?)
(s/def ::on-init fn?)
(s/def ::on-click fn?)
(s/def ::on-shipping-change fn?)
(s/def ::create-order fn?)
(s/def ::create-subscription fn?)
(s/def ::layout #{"vertical" "horizontal"})
(s/def ::color #{"gold" "blue" "silver" "white" "black"})
(s/def ::shape #{"rect" "pill"})
(s/def ::height (s/and integer?
                       #(<= 25 % 55)))
(s/def ::label #{"paypal"
                 "checkout"
                 "buynow"
                 "pay"
                 "installment"})
(s/def ::tagline boolean?)
(s/def ::style (s/keys :opt-un [::layout
                                ::color
                                ::shape
                                ::height
                                ::label]))
(s/def ::on-error fn?)
(s/def ::buttons-options
  (s/and
    (s/keys :req-un [::element-id
                     ::on-approve]
            :opt-un [::create-order
                     ::create-subscription
                     ::on-cancel
                     ::on-init
                     ::on-click
                     ::on-shipping-change])
    #(or (:create-order %)
         (:create-subscription %))
    #(not
       (and (:create-order %)
            (:create-subscription %)))))

(defn- valid-buttons-args?
  [args]
  (if-let [exp (s/explain-data ::buttons-options args)]
    (.error js/console exp)
    true))

(defn buttons
  "Create and return an instance that manages
  the PayPal buttons.

  (pp/buttons elem-id
    {:create-order fn-that-initializes-payment
     :on-approve fn-that-finalizes-payment})"
  [{:keys [element-id] :as args}]
  {:pre [(valid-buttons-args? args)]}

  (if-let [paypal (.-paypal js/window)]
    (let [btns (.Buttons paypal (buttons-config args))]
      (.render btns element-id)
      btns)
    (.error js/console "Unable to load the PayPal library.")))
