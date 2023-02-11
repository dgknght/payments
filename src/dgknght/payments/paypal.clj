(ns dgknght.payments.paypal
  (:require [dgknght.payments.core :refer [*config*]]))

(defn config
  [& ks]
  (if (seq ks)
    (get-in *config* (cons :paypal ks))
    (:paypal *config*)))
