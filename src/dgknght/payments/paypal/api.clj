(ns dgknght.payments.paypal.api
  (:require [clojure.spec.alpha :as s]
            [clojure.walk :refer [postwalk]]
            [clojure.tools.logging :as log]
            [clojure.string :as string]
            [clj-http.client :as http]
            [camel-snake-kebab.core :refer [->kebab-case-keyword
                                            ->snake_case_string
                                            ->SCREAMING_SNAKE_CASE_STRING]]
            [camel-snake-kebab.extras :refer [transform-keys]]
            [lambdaisland.uri :refer [uri]]
            [dgknght.payments.paypal :refer [config]]))

(defn- wrap-request-logging
  [client]
  (fn [req]
    (log/infof "PayPal request %s" (:url req))
    (let [res (client req)]
      (log/infof "PayPal response %s %s"
                 (:url req)
                 (:status res))
      res)))

(defn- jsonify-entry
  [[k v]]
  [(->snake_case_string k)
   (if (keyword? v) (->SCREAMING_SNAKE_CASE_STRING v)
     v)])

(defn- jsonify
  [m]
  (postwalk (fn [x]
              (if (map? x)
                (->> x
                     (map jsonify-entry)
                     (into {}))
                x))
            m))

(defn- cljify
  [m]
  (postwalk (fn [x]
              (cond
                (and (string? x)
                     (re-find #"^[A-Z_]+$" x))
                (->kebab-case-keyword x)

                (map? x)
                (->> x
                     (map #(update-in % [0] ->kebab-case-keyword))
                     (into {}))

                :else
                x))
            m))

(defn- cljified-response
  [{:keys [body] :as res}]
  (assoc res :clj-body (cljify body)))

(defn- wrap-cljification
  [client]
  (fn [req]
    (cljified-response (client req))))

(def middleware
  [#'wrap-request-logging
   #'wrap-cljification])

(defn- production? []
  (= "production"
     (config :environment)))

(defn- base-uri []
  (if (production?)
    "https://api-m.paypal.com"
    "https://api-m.sandbox.paypal.com"))

(s/def ::intent #{:capture :authorize})

(s/def ::value
  (s/or :decimal decimal?
        :string (s/and string?
                       (partial re-matches
                                #"^((-?[0-9]+)|(-?([0-9]+)?[.][0-9]+))$"))))

(s/def ::currency-code #{:usd})

(def currency-value
  (s/or :map (s/keys :req-un [::value
                              ::currency-code])
        :decimal decimal?))

(s/def ::item-total currency-value)
(s/def ::tax-total currency-value)

(s/def ::breakdown
  (s/keys :opt-un [::item-total
                   ::tax-total]))

(s/def ::amount
  (s/keys :req-un [::value]
          :opt-un [::currency-code
                   ::breakdown]))

(s/def ::name string?)
(s/def ::description string?)
(s/def ::quantity (s/and string?
                         (partial re-matches
                                  #"^[1-9][0-9]{0,9}$")))

(s/def ::unit-amount currency-value)
(s/def ::tax currency-value)

(s/def ::category #{:digital-goods
                    :physical-goods
                    :donation})

(s/def ::item
  (s/keys :req-un [::name
                   ::quantity
                   ::unit-amount]
          :opt-un [::category
                   ::description
                   ::sku
                   ::tax]))
(s/def ::items (s/coll-of ::item))

(s/def ::purchase-unit
  (s/keys :req-un [::amount]
          :opt-un [::items]))

(s/def ::purchase-units (s/coll-of ::purchase-unit))

(s/def ::order
  (s/keys :req-un [::purchase-units]
          :opt-un [::intent]))

(def order-defaults
  {:intent :capture})

(def simple-currency?
  (every-pred vector?
              (comp decimal?
                    second)))

; if a :value key points to a BigDecimal
; we end up with {:value {:value "#.00}}
; So, if we see a :value inside a :value,
; we want to merge it up
(def double-value?
  (every-pred map?
              (comp :value :value)))

(defmulti default-currency
  (fn [x]
    (cond
      (simple-currency? x) :expand
      (double-value? x)    :merge-up)))

(defmethod default-currency :default
  [x]
  x)

(defmethod default-currency :expand
  [[k v]]
  [k {:value (format "%.2f" v)
      :currency-code "USD"}])

(defmethod default-currency :merge-up
  [{:keys [value] :as m}]
  (merge m value))

(defn- apply-order-defaults
  [order]
  (merge order-defaults
         (postwalk
           default-currency
           order)))

(def basic-creds
  (juxt :client-id
        :secret))

(def ^:private default-opts
  {:throw-exceptions false
   :coerce :always
   :content-type :json
   :as :json})

(defn- http-get
  [url opts]
  (http/with-middleware (concat http/*current-middleware* middleware)
    (http/get url (merge default-opts opts))))

(defn- http-post
  [url opts]
  (http/with-middleware (concat http/*current-middleware* middleware)
    (http/post url (merge default-opts opts))))

(defn- http-delete
  [url opts]
  (http/with-middleware (concat http/*current-middleware* middleware)
    (http/delete url (merge default-opts opts))))

(defn- generate-access-token-url []
  (-> (base-uri)
      uri
      (assoc :path "/v1/oauth2/token")
      str))

(defn- generate-access-token []
  (let [{:keys [clj-body] :as res}
        (http-post
          (generate-access-token-url)
          {:basic-auth (basic-creds (config))
           :content-type "application/x-www-form-urlencoded"
           :form-params {"grant_type" "client_credentials"}})]
    (if (http/success? res)
      (:access-token clj-body)
      (throw (ex-info "Unable to acquire an access token"
                      {:response clj-body})))))

(defn- build-url
  [& segments]
  (-> (base-uri)
      uri
      (assoc :path (string/join "/" (cons "" segments)))
      str))

(defn- create-order-url []
  (build-url "v2" "checkout" "orders"))

(defn create-order
  "Create a PayPal order
  order - contains the PayPal order"
  [order]
  {:pre [(s/valid? ::order order)]}

  (let [ord (apply-order-defaults order)
        {:keys [clj-body] :as res}
        (http-post
          (create-order-url)
          {:form-params (jsonify ord)
           :oauth-token (generate-access-token)})]
    (if (http/success? res)
      clj-body
      (throw (ex-info "Unable to create the order with PayPal"
                      {:order ord
                       :response clj-body})))))

(defn- capture-payment-url
  [order-id]
  (build-url "v2" "checkout" "orders" order-id "capture"))

(defn capture-payment
  [order-id]
  (let [{:keys [clj-body] :as res}
        (http-post (capture-payment-url order-id)
                   {:basic-auth (basic-creds (config))})]
    (if (http/success? res)
      clj-body
      (throw (ex-info "Unable to capture the payment with PayPal"
                      {:order-id order-id
                       :response clj-body})))))

(defn- gen-client-token-url []
  (build-url "v1" "identity" "generate-token"))

(defn generate-client-token []
  (let [{:keys [body] :as res}
        (http-post (gen-client-token-url)
                   {:oauth-token (generate-access-token)})]
    (if (http/success? res)
      (transform-keys ->kebab-case-keyword body)
      (throw (ex-info "Unable to generate the client token with PayPal"
                      {:response body})))))

(defn- web-profiles-url
  ([] (web-profiles-url nil))
  ([id]
   (apply build-url (cond-> ["v1" "payment-experience" "web-profiles"]
                      id (conj id)))))

(defn web-profiles
  ([]
   (:clj-body
     (http-get (web-profiles-url)
               {:oauth-token (generate-access-token)})))
  ([{:keys [add delete]}]
   (when delete
     (http-delete (web-profiles-url (or (:id delete)
                                        delete))
                  {:oauth-token (generate-access-token)}))
   (when add
     (:clj-body
                 (http-post (web-profiles-url)
                            {:form-params (jsonify add)
                             :oauth-token (generate-access-token)})))))

(defn- create-subscription-url []
  (build-url "v1" "billing" "subscriptions"))

(defn create-subscription
  [sub]
  (:clj-body (http-post (create-subscription-url)
                        {:form-params (jsonify sub)
                         :oauth-token (generate-access-token) })))
