(ns dgknght.payments.braintree.web
  (:require [hiccup.page :refer [include-js]]))

(defn script-tags []
  (include-js "https://js.braintreegateway.com/web/3.82.0/js/client.min.js"
              "https://js.braintreegateway.com/web/3.82.0/js/hosted-fields.min.js"))
