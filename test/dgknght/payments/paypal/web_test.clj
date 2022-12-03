(ns dgknght.payments.paypal.web-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.spec.alpha :as s]
            [dgknght.app-lib.test-assertions]
            [dgknght.payments.core :refer [with-config]]
            [dgknght.payments.paypal.api :as api]
            [dgknght.payments.paypal.web :as pp]))

(s/def ::src string?)
(s/def ::script-attr (s/keys :req-un [::src]))
(s/def ::script-elem (s/tuple #{:script}
                              ::script-attr))

(deftest create-script-tags
  (with-redefs [api/generate-client-token
                (constantly {:client-token "fetched-client-token"})]
    (with-config {:paypal {:components #{:buttons :hosted-fields}
                           :client-id "paypal-client-id"
                           :include-locale? true}}
      (let [[_tag attr :as elem] (pp/script-tags)]
        (is (conformant? ::script-elem elem)
            "The script element is well-formed")
        (is (url-like? "https://www.paypal.com/sdk/js?components=buttons,hosted-fields&client-id=paypal-client-id&data-client-token=fetched-client-token&currency=USD&locale=en_US&buyer-country=US"
                       (:src attr))
            "The src points to the correct URL")))))
