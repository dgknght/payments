(ns dgknght.payments.paypal.api-test
  (:require [clojure.test :refer [deftest is]]
            [clj-http.core :as http] ; web-mocks won't work without this
            [cheshire.core :as json]
            [dgknght.app-lib.test]
            [dgknght.app-lib.test-assertions]
            [dgknght.app-lib.web-mocks :refer [with-web-mocks]]
            [dgknght.payments.core :refer [with-config]]
            [dgknght.payments.paypal.api :as pp])
  (:import java.io.FileInputStream))

(defn- mock
  ([] (mock nil 204))
  ([k] (mock k 201))
  ([k status]
   (fn [& _]
     {:status status
      :body (when k
              (FileInputStream.
                (format "resources/fixtures/paypal/%s-response.json"
                        (name k))))})))

(def create-order-mocks
  {#"v1\/oauth2\/token"      (mock :generate-access-token)
   #"v2\/checkout\/orders$" (mock :create-order)})

(def expected-order-req-body
  {:intent "CAPTURE"
   :purchase_units [{:amount {:currency_code "USD"
                              :value "10.65"
                              :breakdown {:item_total {:value "9.99"
                                                       :currency_code "USD"}
                                          :tax_total {:value "0.66"
                                                      :currency_code "USD"}}}
                     :custom_id "101"
                     :description "Site license"
                     :items [{:name "Site license"
                              :quantity "1"
                              :unit_amount {:value "9.99"
                                            :currency_code "USD"}
                              :category "DIGITAL_GOODS"
                              :description "Site license from 1/1/2022 until 1/1/2023"
                              :tax {:value "0.66"
                                    :currency_code "USD"}}]}]})

(def order
  {:purchase-units [{:amount {:value 10.65M
                              :breakdown {:item-total 9.99M
                                          :tax-total 0.66M}}
                     :custom-id "101"
                     :description "Site license"
                     :items [{:name "Site license"
                              :quantity "1"
                              :unit-amount 9.99M
                              :category :digital-goods
                              :description "Site license from 1/1/2022 until 1/1/2023"
                              :tax 0.66M}]}]})

(def ^:private config
  {:paypal {:environment "sandbox"
            :client-id "paypal-client-id"
            :secret "paypal-secret" }})

(deftest create-an-order
  (with-web-mocks [calls] create-order-mocks
    (with-config config
      (let [res (pp/create-order order)]
        (is (comparable? {:id "5O190127TN364715T"
                          :status :payer-action-required}
                         res)
            "The returned map includes the status and order ID")
        (let [[c1 c2 :as cs] (map (fn [c]
                                    (update-in c
                                               [:body]
                                               #(slurp (.getContent %))))
                                  @calls)]
          (is (= 2 (count cs))
              "two http calls are made")

          ; generate access token
          (is (comparable? {"content-type" "application/x-www-form-urlencoded"
                            "authorization" "Basic cGF5cGFsLWNsaWVudC1pZDpwYXlwYWwtc2VjcmV0"}
                           (:headers c1))
              "The correct headers are sent for the access token call")
          (is (= "grant_type=client_credentials"
                 (:body c1))
              "The correct content is sent in the access token call")

          ; create order
          (is (comparable? {"content-type" "application/json"
                            "authorization" "Bearer A21AAFEpH4PsADK7qSS7pSRsgzfENtu-Q1ysgEDVDESseMHBYXVJYE8ovjj68elIDy8nF26AwPhfXTIeWAZHSLIsQkSYz9ifg"}
                           (:headers c2))
              "The correct headers are specified for the create-order call")
          (is (= expected-order-req-body
                 (json/parse-string (:body c2) true))
              "The correct data is sent for the create-order call"))))))

(deftest sandbox-mode
  (with-web-mocks [calls] create-order-mocks
    (with-config config 
      (pp/create-order order))
    (is (called? :twice calls #"^https://api-m.sandbox.paypal.com"))
    (is (not-called? calls #"^https://api-m.paypal.com"))))

(deftest production-mode
  (with-web-mocks [calls] create-order-mocks
    (with-config (assoc-in config [:paypal :environment] "production")
      (pp/create-order order))
    (is (not-called? calls #"^https://api-m.sandbox.paypal.com"))
    (is (called? :twice calls #"^https://api-m.paypal.com"))))

(def failed-create-order-mocks
  {#"v1\/oauth2/token"      (mock :generate-access-token)
   #"v2\/checkout\/orders$" (mock :non-identity-error 401)})

(deftest handle-failure-response
  (with-web-mocks [calls] failed-create-order-mocks
    (with-config config
      (let [ex (try (pp/create-order order)
                    (catch Exception e
                      e))]
        (is (= "Unable to create the order with PayPal"
               (ex-message ex))
            "The exception has a meaningful description")
        (is (= [:order :response]
               (keys (ex-data ex)))
            "The ex data contains the original order and the response")
        (is (comparable? {:name :risk-decline}
                         (:response (ex-data ex)))
            "The response has been clojurified")))))

(def ^:private capture-mocks
  {#"v2\/checkout\/orders" (mock :capture-payment-success)})

(deftest capture-a-payment
  (with-web-mocks [calls] capture-mocks
    (with-config config
      (let [order-id "5O190127TN364715T"
            res (pp/capture-payment order-id)]
        (is (comparable? {:status :completed}
                         res)
            "The response indicates the status of the order")
        (is (= [:completed]
               (->> (:purchase-units res)
                    (mapcat #(get-in % [:payments :captures]) )
                    (map :status)))
            "The response indicates the status of each payment unit")
        (let [[c :as cs] @calls]
          (is (= 1 (count cs))
              "One API call is made")
          (is (comparable? {:url "https://api-m.sandbox.paypal.com/v2/checkout/orders/5O190127TN364715T/capture"
                            :request-method :post}
                           c)
              "The request is POSTed to the correct url")
          (is (comparable? {"Content-Type" "application/json"
                            "Authorization" "Basic cGF5cGFsLWNsaWVudC1pZDpwYXlwYWwtc2VjcmV0"}
                           (:headers c))
              "The request is made with the correct headers"))))))

(def token-mocks
  {#"v1\/oauth2/token"             (mock :generate-access-token)
   #"v1\/identity\/generate-token" (mock :generate-client-token)})

(deftest generate-a-client-token
  (with-web-mocks [calls] token-mocks
    (let [res (pp/generate-client-token)
          [c1 c2 :as cs] @calls]

      (is (string? (:client-token res))
          "The response contains the client token")
      (is (= 2 (count cs))
          "Two http calls are made")

      ; generate access token
      (is (comparable? {:url "https://api-m.sandbox.paypal.com/v1/oauth2/token"
                        :request-method :post}
                       c1)
          "The first call fetches the access token")

      ; generate client token
      (is (comparable? {:url "https://api-m.sandbox.paypal.com/v1/identity/generate-token"
                        :request-method :post}
                       c2)
          "The second call fetches the client token")
      (is (comparable? {"Authorization" "Bearer A21AAFEpH4PsADK7qSS7pSRsgzfENtu-Q1ysgEDVDESseMHBYXVJYE8ovjj68elIDy8nF26AwPhfXTIeWAZHSLIsQkSYz9ifg"
                        "Content-Type" "application/json"}
                       (:headers c2))
          "The client token is fetched with correct headers"))))

(def profile-mocks
  {#"v1\/oauth2\/token"                    (mock :generate-access-token)
   #"v1\/payment-experience\/web-profiles" (mock :web-profiles)})

(deftest view-web-profiles
  (with-web-mocks [calls] profile-mocks
    (let [profiles (pp/web-profiles)
          [_ c2 :as cs] @calls]
      (is (= 2 (count cs))
          "The PayPal API is called one time")
      (is (comparable? {:url "https://api-m.sandbox.paypal.com/v1/payment-experience/web-profiles"
                        :request-method :get}
             c2)
          "The PayPal API is called with the correct options")
      (is (= [{:id "XP-GCUV-X35G-HNEY-5MJY"
               :name "exampleProfile"
               :flow-config {:landing-page-type "billing"
                             :bank-txn-pending-url "https://example.com/flow_config/"}
               :input-fields {:no-shipping 1
                              :address-override 1}
               :presentation {:logo-image "https://example.com/logo_image/"}}
              {:id "XP-A88A-LYLW-8Y3X-E5ER"
               :name "exampleProfile"
               :flow-config {:landing-page-type "billing"
                             :bank-txn-pending-url "https://example.com/flow_config/"}
               :input-fields {:no-shipping 1
                              :address-override 1}
               :presentation {:logo-image "https://example.com/logo_image/"}}]
             profiles)
          "The profiles are returned"))))

(def ^:private new-web-profile
  {:name "new-profile"
   :flow-config {:landing-page-type "billing"
                 :bank-txn-pending-url "https://myapp.com/pending"}
   :input-fields {:no-shipping 1}
   :presentation {:logo-image "https://myapp.com/logo.png"}})

(def add-profile-mocks
  {#"v1\/oauth2\/token"                    (mock :generate-access-token)
   #"v1\/payment-experience\/web-profiles" (mock :create-web-profile)})

(deftest add-a-web-profile
  (with-web-mocks [calls] add-profile-mocks
    (let [res (pp/web-profiles {:add new-web-profile})
          [_ c2 :as cs] @calls]
      (is (= 2 (count cs))
          "The PayPal API is called twice")
      (is (comparable? {:url "https://api-m.sandbox.paypal.com/v1/payment-experience/web-profiles"
                        :request-method :post}
                       c2)
          "The PayPal API is called with the correct options")
      (is (= (assoc new-web-profile
                    :id "XP-RFV4-PVD8-AGHJ-8E5J"
                    :temporary false)
             res)
          "The correct data is returned"))))

(def ^:private existing-web-profile
  {:id "XP-RFV4-PVD8-AGHJ-8E5J"
   :name "existing-profile"
   :flow-config {:landing-page-type "billing"
                 :bank-txn-pending-url "https://myapp.com/pending"}
   :input-fields {:no-shipping 1}
   :presentation {:logo-image "https://myapp.com/logo.png"}})

(def delete-profile-mocks
  {#"v1\/oauth2\/token"                        (mock :generate-access-token)
   #"v1\/payment-experience\/web-profiles\/.+" (mock)})

(deftest delete-a-web-profile
  (with-web-mocks [calls] delete-profile-mocks
    (let [res (pp/web-profiles {:delete existing-web-profile})
          [_ c2 :as cs] @calls]
      (is (= 2 (count cs))
          "The PayPal API is called twice")
      (is (comparable? {:url "https://api-m.sandbox.paypal.com/v1/payment-experience/web-profiles/XP-RFV4-PVD8-AGHJ-8E5J"
                        :request-method :delete}
                       c2)
          "The PayPal API is called with the correct options")
      (is (nil?  res)
          "Nothing is returned"))))
