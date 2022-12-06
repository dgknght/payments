(ns dgknght.payments.paypal-test
  (:require [cljs.test :refer [deftest is]]
            [dgknght.payments.paypal :as pp]))

(deftest initialize-the-buttons
  (is (= :something
         (pp/buttons))))
