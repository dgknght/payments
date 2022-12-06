(ns dgknght.payments.braintree
  (:require [dgknght.payments.core :refer [*config*]]))

(defn config
  [& ks]
  (get-in *config* (cons :braintree ks)))
