(ns dgknght.payments.paypal
  (:refer-clojure :exclude [js->clj])
  (:require [cljs.core :as c]
            [cljs.core.async :as a]
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
  [{:keys [create-order on-approve on-cancel]}]
  (clj->js
    (cond-> {"createOrder" (fn [data actions]
                             (->promise create-order
                                        {:data (js->clj data)
                                         :actions (js->clj actions)}))
             "onApprove" (fn [data actions]
                           (->promise on-approve
                                      {:data (js->clj data)
                                       :actions (js->clj actions)}))}
      on-cancel (assoc
                  "onCancel" (fn [data]
                               (->promise on-cancel
                                          {:data (js->clj data)}))))))

(defn buttons
  "Create and return an instance that manages
  the PayPal buttons.

  (pp/buttons elem-id
    {:create-order fn-that-initializes-payment
     :on-approve fn-that-finalizes-payment})"
  [{:keys [element-id] :as args}]
  {:pre [(:element-id args)
         (:create-order args)
         (:on-approve args)]}

  (if-let [paypal (.-paypal js/window)]
    (let [btns (.Buttons paypal (buttons-config args))]
      (.render btns element-id)
      btns)
    (.error js/console "Unable to load the PayPal library.")))
