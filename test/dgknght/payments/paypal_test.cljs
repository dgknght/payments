(ns dgknght.payments.paypal-test
  (:require [cljs.test :refer [deftest is]]
            [dgknght.payments.paypal.mocks :refer-macros [with-paypal-mocks]]
            [dgknght.payments.paypal :as pp]))

(deftest initialize-the-buttons
  (with-paypal-mocks [calls]
    (is (not
          (nil?
            (pp/buttons {:element-id "#button-container"
                         :create-order :add-channel-or-callback-fn
                         :on-approve :add-channel-or-callback-fn})))
        "The PayPal Buttons instance is returned")
    (let [[c :as cs] (get-in @calls [:render] [])]
      (is (= 1 (count cs))
          "The render method is called once")
      (is (= ["#button-container"]
             c)
          "The render method is called with the ID of the element where the buttons are to be rendered"))))
