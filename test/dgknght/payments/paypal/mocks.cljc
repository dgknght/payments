(ns dgknght.payments.paypal.mocks
  (:require [cljs.core :as c]))

(defn register-fn-call
  [coll fn-key]
  (fn
    [& args]
    (swap! coll update-in [fn-key] (fnil conj []) args)))

(defprotocol ButtonsController
  (click [this]))

(defmacro with-paypal-mocks
  [bindings & body]
  `(let [calls# (atom {})
         opts# (atom nil)
         controller# (reify ButtonsController
                       (click [_#]
                         (let [create-order# (aget @opts# "createOrder")
                               on-approve# (aget @opts# "onApprove")]
                           (.then (create-order# (c/js-obj)
                                                 (c/js-obj))
                                  (fn [order-id#]
                                    (on-approve# (c/js-obj "orderID"
                                                           order-id#)
                                                 (c/js-obj)))))))
         paypal# (c/js-obj "Buttons"
                           (fn [o#]
                             (reset! opts# o#)
                             (c/js-obj "render"
                                       (register-fn-call calls# :render))))
         f# (fn* [~(first bindings) ~(second bindings)]
                 ~@body)]
     (set! (.-paypal js/window) paypal#)
     (f# calls# controller#)
     (c/js-delete js/window "paypal")))
