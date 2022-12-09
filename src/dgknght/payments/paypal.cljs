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
        (a/go (a/<! (a/timeout 5000)) ; TODO: make this configurable
              (when-not @yielded?
                (a/close! c)))
        (a/go
          (let [r (a/<! c)]
            (reset! yielded? true)
            (res r)))
        (a/go
          (a/>! c args))))))

(defn buttons
  "Create and return an instance that manages
  the PayPal buttons.

  (pp/buttons elem-id
    {:create-order fn-that-initializes-payment
     :on-approve fn-that-finalizes-payment})"
  [{:keys [element-id create-order on-approve] :as args}]
  {:pre [(:element-id args)
         (:create-order args)
         (:on-approve args)]}

  (let [paypal (.-paypal js/window)
        btns (.Buttons paypal
                       (js-obj "createOrder"
                               (fn [data actions]
                                 (->promise create-order
                                            {:data (js->clj data)
                                             :actions (js->clj actions)}))
                               "onApprove"
                               (fn [data actions]
                                 (->promise on-approve
                                            {:data (js->clj data)
                                             :actions (js->clj actions)}))))]
    (.render btns element-id)
    btns))
