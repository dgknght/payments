(ns dgknght.payments.paypal
  (:refer-clojure :exclude [js->clj])
  (:require [cljs.core :as c]
            [cljs.core.async :as a]
            [camel-snake-kebab.core :refer [->kebab-case-keyword]]
            [camel-snake-kebab.extras :refer [transform-keys]]))

(def ^:private js->clj
  (comp (partial transform-keys ->kebab-case-keyword)
        c/js->clj))

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
        result (.Buttons paypal
                         (js-obj "createOrder"
                                 (fn [data actions]
                                   (js/Promise.
                                     (fn [res rej]
                                       (let [c (a/chan 1 create-order rej)]
                                         (a/go (let [r (a/<! c)]
                                                 (res r)))
                                         (a/go (a/>! c {:data (js->clj data)
                                                        :actions (js->clj actions)}))))))
                                 "onApprove"
                                 (fn [data actions]
                                   (let [c (a/chan 1 on-approve (.-error js/console))]
                                         (a/go (a/<! c))
                                         (a/go (a/>! c {:data (js->clj data)
                                                        :actions (js->clj actions)}))))))]
    (.render result element-id)))
