(ns dgknght.payments.braintree.web-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.spec.alpha :as s]
            [dgknght.app-lib.test-assertions]
            [dgknght.payments.braintree.web :as pp])
  (:import java.net.URI))

(s/def ::src (partial instance? URI))
(s/def ::script-attr (s/keys :req-un [::src]))
(s/def ::script-elem (s/tuple #{:script}
                              ::script-attr))
(s/def ::script-elems (s/coll-of ::script-elem))

(deftest create-script-tags
  (let [elems (pp/script-tags)]
    (is (conformant? ::script-elems elems)
        "The script element is well-formed")
    (is (= #{"https://js.braintreegateway.com/web/3.82.0/js/client.min.js"
             "https://js.braintreegateway.com/web/3.82.0/js/hosted-fields.min.js"}
           (->> elems
                (map #(get-in % [1 :src]))
                (map str)
                (into #{})))
        "The src attributes point to the correct URLs")))
