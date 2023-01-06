(ns dgknght.payments.paypal.web-test
  (:require #?(:clj [clojure.test :refer [deftest is]]
               :cljs [cljs.test :refer [deftest is]])
            #?(:clj [clojure.spec.alpha :as s]
               :cljs [cljs.spec.alpha :as s])
            [dgknght.app-lib.test-assertions]
            #?(:clj [dgknght.payments.core :refer [with-config]])
            #?(:clj [dgknght.payments.paypal.api :as api])
            [dgknght.payments.paypal.web :as pp]))

(s/def ::src string?)
(s/def ::script-attr (s/keys :req-un [::src]))
(s/def ::script-elem (s/tuple #{:script}
                              ::script-attr))

#?(:clj
   (deftest create-script-tags-server-side
     (with-redefs [api/generate-client-token
                   (constantly {:client-token "fetched-client-token"})]
       (with-config {:paypal {:components #{:buttons :hosted-fields}
                              :client-id "paypal-client-id"
                              :locale "en_US"
                              :currency "USD"
                              :buyer-country "US"}}
         (let [[_tag attr :as elem] (pp/script-tags)]
           (is (= "fetched-client-token"
                  (:data-client-token attr))
               "The client token is fetched and included in the data attributes")
           (is (conformant? ::script-elem elem)
               "The script element is well-formed")
           (is (url-like? "https://www.paypal.com/sdk/js?components=buttons,hosted-fields&client-id=paypal-client-id&currency=USD&locale=en_US&buyer-country=US"
                          (:src attr))
               "The src points to the correct URL"))))))

(deftest create-script-tags
  (let [[_tag attr :as elem] #_{:clj-kondo/ignore [:invalid-arity]} (pp/script-tags {:components #{:buttons :hosted-fields}
                                                                                     :client-id "paypal-client-id"
                                                                                     :data {:client-token "specified-client-token"}
                                                                                     :locale "en_US"
                                                                                     :currency "USD"
                                                                                     :buyer-country "US"})]
    (is (= "specified-client-token"
           (:data-client-token attr))
        "The :data values are added as element attributes")
    (is (conformant? ::script-elem elem)
        "The script element is well-formed")
    (is (url-like? "https://www.paypal.com/sdk/js?components=buttons,hosted-fields&client-id=paypal-client-id&currency=USD&locale=en_US&buyer-country=US"
                   (:src attr))
        "The src points to the correct URL")))
