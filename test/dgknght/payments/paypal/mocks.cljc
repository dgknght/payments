(ns dgknght.payments.paypal.mocks
  (:require [cljs.core :as c]))

(defn register-fn-call
  [coll fn-key]
  (fn
    [& args]
    (swap! coll update-in [fn-key] (fnil conj []) args)))

(defmacro with-paypal-mocks
  [bindings & body]
  `(let [calls# (atom {})
         paypal# (c/js-obj "Buttons"
                           (fn [opts#]
                             (c/js-obj "render"
                                       (register-fn-call calls# :render))))
         f# (fn* [~(first bindings)]
                 ~@body)]
     (set! (.-paypal js/window) paypal#)
     (f# calls#)
     (c/js-delete js/window "paypal")))
