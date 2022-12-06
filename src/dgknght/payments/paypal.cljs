(ns dgknght.payments.paypal)

(defn buttons
  "Create and return an instance that manages
  the PayPal buttons.

  (pp/buttons elem-id
              {:create-order fn-that-initializes-payment
               :on-approve fn-that-finalizes-payment})"
  [elem-id]
  )
