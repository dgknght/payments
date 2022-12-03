(ns dgknght.payments.braintree.api
  (:require [config.core :refer [env]]
            [camel-snake-kebab.core :refer [->kebab-case-keyword]])
  (:import [com.braintreegateway
            Environment
            BraintreeGateway
            Transaction
            TransactionRequest]))

(def ^:private gateway
  (when (not-any? (comp empty?
                        env)
                  [:braintree-environment
                   :braintree-merchant-id
                   :braintree-public-key
                   :braintree-private-key])
    (BraintreeGateway.
      (Environment/parseEnvironment (env :braintree-environment))
      (env :braintree-merchant-id)
      (env :braintree-public-key)
      (env :braintree-private-key))))

(defn client-token []
  (.generate (.clientToken gateway)))

(defn success?
  [transaction]
  (:success? transaction))

(defn- apply-custom-fields
  [trans-req custom-fields]
  (doseq [[k v] custom-fields]
    (.customField trans-req k v))
  trans-req)

(defn- apply-customer
  [trans-req customer]
  (-> trans-req
      (.options)
      (.storeInVaultOnSuccess true)
      (.done)
      (.customer)
      (.id (:id customer))
      (.firstName (:first-name customer))
      (.lastName (:last-name customer))
      (.done)))

(defn- ->TransactionRequest
  [{:as req
    :keys [nonce
           customer-id
           customer
           amount
           custom-fields]}]

  {:pre [(or (:nonce req)
             (:customer-id req))]}

  (cond-> (-> (TransactionRequest.)
              (.amount amount)
              (apply-custom-fields custom-fields)
              (.options)
              (.submitForSettlement true)
              (.done))
    nonce       (.paymentMethodNonce nonce)
    customer-id (.customerId customer-id)
    customer    (apply-customer customer)))

(defmulti ^:private ->map
  #(cond
     (.isSuccess %)      :success
     (.getTransaction %) :failure
     :else               :invalid))

(defn- trans-status
  [trans]
  (->kebab-case-keyword (.toString (.getStatus trans))))

(defmethod ->map :success
  [result]
  (let [trans ^Transaction (.getTarget result)
        card (.getCreditCard trans)
        customer (.getCustomer trans)]
    (cond->
      {:success? true
       :status (trans-status trans)
       :id (.getId trans)
       :last-4 (.getLast4 card)
       :card-type (->kebab-case-keyword (.getCardType card))}

      (.getId customer)
      (assoc :customer {:id (.getId customer)
                        :first-name (.getFirstName customer)
                        :last-name (.getLastName customer)}))))

(defmethod ->map :failure
  [result]
  (let [trans ^Transaction (.getTarget result)
        card (.getCreditCard trans)]
    {:success? false
     :status (trans-status trans)
     :id (.getId trans)
     :last-4 (.getLast4 card)
     :card-type (->kebab-case-keyword (.getCardType card))
     :errors [{:code (.getProcessorResponseCode trans)
               :message (.getProcessorResponseText trans)}]}))

(defn- ->error
  [err]
  {:attribute (.getAttribute err)
   :code (.toString (.getCode err))
   :message (.getMessage err)})

(defmethod ->map :invalid
  [result]
  {:success? false
   :errors (mapv ->error (.getAllDeepValidationErrors (.getErrors result)))})

(defn sale
  [req]
  (->map
    (.sale (.transaction gateway)
           (->TransactionRequest req))))
