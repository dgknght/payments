(ns dgknght.payments.paypal-test
  (:require [cljs.test :refer [deftest testing is]]
            [dgknght.payments.paypal.mocks :refer-macros [with-paypal-mocks]]
            [dgknght.payments.paypal :as pp]))

(deftest initialize-the-buttons
  (testing "with valid arguments"
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
  (testing "with invalid arguments"
    (is (thrown-with-msg? js/Error #"Assert failed: \(:element-id args\)"
                 (pp/buttons {:create-order :add-channel
                              :on-approve :add-channel}))
        "element-id is required")
    (is (thrown-with-msg? js/Error #"Assert failed: \(:create-order args\)"
                 (pp/buttons {:element-id "#button-container"
                              :on-approve :add-channel}))
        "create-order is required")
    (is (thrown-with-msg? js/Error #"Assert failed: \(:on-approve args\)"
                 (pp/buttons {:element-id "#button-container"
                              :create-order :add-channel}))
        "on-approve is required")))
