(ns dgknght.payments.paypal)

(defn buttons
  "Create and return an instance that manages
  the PayPal buttons.

  (pp/buttons elem-id
    {:create-order fn-that-initializes-payment
     :on-approve fn-that-finalizes-payment})"
  [{:keys [element-id _create-order _on-approve]}]
  (let [paypal (.-paypal js/window)
        result (.Buttons paypal (js-obj "one" 1))]
    (.render result element-id)))
