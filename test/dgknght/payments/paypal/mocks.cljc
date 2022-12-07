(ns dgknght.payments.paypal.mocks)

(defmacro with-paypal-mocks
  [bindings & body]
  `(let [calls# (atom {})
         paypal# (js-obj "Buttons" (fn [opts#]
                                     (println "Buttons")
                                     (.log js/console opts#)
                                     (js-obj "test" "this is")
                                     (js-obj "render" (fn [id#] (println "render " id)))))
         f# (fn* [~(first bindings)]
                 ~@body)]
     (set! (.-paypal js/window) paypal#)
     (f# calls#)
     (js-delete js/window "paypal")))
