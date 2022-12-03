(ns dgknght.payments.paypal
  (:require [dgknght.payments.core :refer [*config*]]))

(defn config
  [& ks]
  (get-in *config* (cons :paypal ks)))
