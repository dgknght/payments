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

(s/def ::soft-descriptor string?)

(s/def ::purchase-unit
  (s/keys :req-un [::amount]
          :opt-un [::items
                   ::soft-descriptor]))

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

(defn- http-patch
  [url opts]
  (http/with-middleware (concat http/*current-middleware* middleware)
    (http/patch url (merge default-opts opts))))

; Refresh the cached token this many ms before it actually expires, so a
; token that's about to expire is never handed to an in-flight request.
(def ^:private token-expiry-buffer-ms 60000)

(defn- generate-access-token-url []
  (-> (base-uri)
      uri
      (assoc :path "/v1/oauth2/token")
      str))

(defn- cache-key [cfg]
  (select-keys cfg [:client-id :environment]))

(defn- fetch-access-token []
  (let [{:keys [clj-body] :as res}
        (http-post
          (generate-access-token-url)
          {:basic-auth (basic-creds (config))
           :content-type "application/x-www-form-urlencoded"
           :form-params {"grant_type" "client_credentials"}})]
    (if (http/success? res)
      clj-body
      (throw (ex-info "Unable to acquire an access token"
                      {:response clj-body})))))

(defonce ^:private token-cache (atom {}))

(defn- cached-token
  [k fetch]
  (let [cached (get-in @token-cache [k])]
    (if (and cached (< (System/currentTimeMillis)
                       (:expires-at cached)))
      (:access-token cached)
      (let [{:keys [access-token expires-in]} (fetch)]
         (swap! token-cache assoc k
                {:access-token access-token
                 :expires-at (+ (System/currentTimeMillis)
                                (- (* 1000 expires-in)
                                   token-expiry-buffer-ms))})
         access-token))))

(defn- access-token
  ([] (access-token (cache-key (config))))
  ([k]
   (cached-token k fetch-access-token)))

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
           :oauth-token (access-token)})]
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
                   {:oauth-token (access-token)})]
    (if (http/success? res)
      clj-body
      (throw (ex-info "Unable to capture the payment with PayPal"
                      {:order-id order-id
                       :response clj-body})))))

(defn- gen-client-token-url []
  (build-url "v1" "identity" "generate-token"))

(defn generate-client-token []
  (let [{:keys [body status] :as res}
        (http-post (gen-client-token-url)
                   {:oauth-token (access-token)})]
    (if (http/success? res)
      (transform-keys ->kebab-case-keyword body)
      (throw (ex-info "Unable to generate the client token with PayPal"
                      {:response body
                       :status status})))))

(defn- web-profiles-url
  ([] (web-profiles-url nil))
  ([id]
   (apply build-url (cond-> ["v1" "payment-experience" "web-profiles"]
                      id (conj id)))))

(defn web-profiles
  ([]
   (:clj-body
     (http-get (web-profiles-url)
               {:oauth-token (access-token)})))
  ([{:keys [add delete]}]
   (when delete
     (http-delete (web-profiles-url (or (:id delete)
                                        delete))
                  {:oauth-token (access-token)}))
   (when add
     (:clj-body
                 (http-post (web-profiles-url)
                            {:form-params (jsonify add)
                             :oauth-token (access-token)})))))

(defn- create-subscription-url []
  (build-url "v1" "billing" "subscriptions"))

(defn create-subscription
  [sub]
  (:clj-body (http-post (create-subscription-url)
                        {:form-params (jsonify sub)
                         :oauth-token (access-token) })))

(defn- verify-webhook-signature-url []
  (build-url "v1" "notifications" "verify-webhook-signature"))

(defn verify-webhook-signature
  "Verifies a PayPal webhook notification's signature.

  req - a map with :transmission-id, :transmission-time, :cert-url,
  :auth-algo, and :transmission-sig (from the webhook request's
  paypal-transmission-* headers), :webhook-id (configured for this
  app in the PayPal developer dashboard), and :webhook-event (the
  webhook request body, parsed but otherwise passed through
  unmodified, since PayPal recomputes the signature against it)"
  [{:keys [transmission-id transmission-time cert-url auth-algo
           transmission-sig webhook-id webhook-event]}]
  (let [{:keys [clj-body] :as res}
        (http-post
          (verify-webhook-signature-url)
          {:form-params {:transmission_id transmission-id
                         :transmission_time transmission-time
                         :cert_url cert-url
                         :auth_algo auth-algo
                         :transmission_sig transmission-sig
                         :webhook_id webhook-id
                         :webhook_event webhook-event}
           :oauth-token (access-token)})]
    (if (http/success? res)
      clj-body
      (throw (ex-info "Unable to verify the webhook signature with PayPal"
                      {:response clj-body})))))

; Products and billing plans (PayPal's subscription catalog).
;
; :category is deliberately not spec'd here. PayPal maintains ~200 valid
; values for it and validates it server-side; duplicating that enum isn't
; worth it. It's still accepted and passed through unvalidated since
; s/keys does not reject unlisted keys.

(s/def ::type #{:physical :digital :service})
(s/def ::image-url string?)
(s/def ::home-url string?)

(s/def ::product
  (s/keys :req-un [::name ::type]
          :opt-un [::description ::image-url ::home-url]))

(s/def ::product-id string?)
(s/def ::interval-unit #{:day :week :month :year})
(s/def ::interval-count pos-int?)
(s/def ::frequency (s/keys :req-un [::interval-unit ::interval-count]))
(s/def ::tenure-type #{:regular :trial})
(s/def ::sequence pos-int?)
(s/def ::total-cycles nat-int?)
(s/def ::fixed-price currency-value)
(s/def ::pricing-scheme (s/keys :req-un [::fixed-price]))
(s/def ::billing-cycle
  (s/keys :req-un [::frequency ::tenure-type ::sequence ::pricing-scheme]
          :opt-un [::total-cycles]))
(s/def ::billing-cycles (s/coll-of ::billing-cycle :min-count 1))

(s/def ::plan
  (s/keys :req-un [::product-id ::name ::billing-cycles]
          :opt-un [::description]))

; PayPal's PATCH endpoints use JSON Patch (RFC 6902): [{op, path, value}].
; `op` and `path` must stay as plain lowercase strings; only `value` may need
; the same key/keyword-case conversion `jsonify` applies elsewhere, and only
; when it's a nested map (e.g. payment_preferences). A bare string/number
; value is left as-is; clj-http's JSON encoding serializes it correctly.
(defn- ->patch-op
  [{:keys [op path value]}]
  (cond-> {:op (name op) :path path}
    (some? value) (assoc :value (if (map? value) (jsonify value) value))))

(defn- products-url
  ([] (build-url "v1" "catalogs" "products"))
  ([id] (build-url "v1" "catalogs" "products" id)))

(defn create-product
  "Create a PayPal catalog product"
  [product]
  {:pre [(s/valid? ::product product)]}
  (let [{:keys [clj-body] :as res}
        (http-post (products-url)
                   {:form-params (jsonify product)
                    :oauth-token (access-token)})]
    (if (http/success? res)
      clj-body
      (throw (ex-info "Unable to create the product with PayPal"
                      {:product product
                       :response clj-body})))))

(defn list-products
  ([] (list-products {}))
  ([{:keys [page page-size]}]
   (let [{:keys [clj-body] :as res}
         (http-get (products-url)
                   {:query-params (cond-> {}
                                    page (assoc "page" page)
                                    page-size (assoc "page_size" page-size))
                    :oauth-token (access-token)})]
     (if (http/success? res)
       (:products clj-body)
       (throw (ex-info "Unable to list products with PayPal"
                       {:response clj-body}))))))

(defn get-product
  [id]
  (let [{:keys [clj-body] :as res}
        (http-get (products-url id)
                  {:oauth-token (access-token)})]
    (if (http/success? res)
      clj-body
      (throw (ex-info "Unable to fetch the product from PayPal"
                      {:id id
                       :response clj-body})))))

(defn update-product
  "Applies JSON Patch ops to a PayPal catalog product. PayPal returns 204 on
  success, so this returns nil."
  [id ops]
  (let [{:keys [clj-body] :as res}
        (http-patch (products-url id)
                    {:form-params (mapv ->patch-op ops)
                     :oauth-token (access-token)})]
    (when-not (http/success? res)
      (throw (ex-info "Unable to update the product with PayPal"
                      {:id id
                       :ops ops
                       :response clj-body})))
    nil))

(defn- plans-url
  ([] (build-url "v1" "billing" "plans"))
  ([id] (build-url "v1" "billing" "plans" id)))

(defn- plan-activate-url [id] (build-url "v1" "billing" "plans" id "activate"))
(defn- plan-deactivate-url [id] (build-url "v1" "billing" "plans" id "deactivate"))

(defn create-plan
  "Create a PayPal billing plan. Bare decimal :fixed-price values are
  expanded into {:value \"...\" :currency-code \"USD\"} the same way
  order line items are, via the shared default-currency logic."
  [plan]
  {:pre [(s/valid? ::plan plan)]}
  (let [pln (postwalk default-currency plan)
        {:keys [clj-body] :as res}
        (http-post (plans-url)
                   {:form-params (jsonify pln)
                    :oauth-token (access-token)})]
    (if (http/success? res)
      clj-body
      (throw (ex-info "Unable to create the plan with PayPal"
                      {:plan pln
                       :response clj-body})))))

(defn list-plans
  ([] (list-plans {}))
  ([{:keys [product-id page page-size]}]
   (let [{:keys [clj-body] :as res}
         (http-get (plans-url)
                   {:query-params (cond-> {}
                                    product-id (assoc "product_id" product-id)
                                    page (assoc "page" page)
                                    page-size (assoc "page_size" page-size))
                    :oauth-token (access-token)})]
     (if (http/success? res)
       (:plans clj-body)
       (throw (ex-info "Unable to list plans with PayPal"
                       {:response clj-body}))))))

(defn get-plan
  [id]
  (let [{:keys [clj-body] :as res}
        (http-get (plans-url id)
                  {:oauth-token (access-token)})]
    (if (http/success? res)
      clj-body
      (throw (ex-info "Unable to fetch the plan from PayPal"
                      {:id id
                       :response clj-body})))))

(defn update-plan
  "Applies JSON Patch ops to a PayPal billing plan. PayPal returns 204 on
  success, so this returns nil."
  [id ops]
  (let [{:keys [clj-body] :as res}
        (http-patch (plans-url id)
                    {:form-params (mapv ->patch-op ops)
                     :oauth-token (access-token)})]
    (when-not (http/success? res)
      (throw (ex-info "Unable to update the plan with PayPal"
                      {:id id
                       :ops ops
                       :response clj-body})))
    nil))

(defn activate-plan
  "PayPal returns 204 on success, so this returns nil."
  [id]
  (let [{:keys [clj-body] :as res}
        (http-post (plan-activate-url id)
                   {:oauth-token (access-token)})]
    (when-not (http/success? res)
      (throw (ex-info "Unable to activate the plan with PayPal"
                      {:id id
                       :response clj-body})))
    nil))

(defn deactivate-plan
  "PayPal returns 204 on success, so this returns nil."
  [id]
  (let [{:keys [clj-body] :as res}
        (http-post (plan-deactivate-url id)
                   {:oauth-token (access-token)})]
    (when-not (http/success? res)
      (throw (ex-info "Unable to deactivate the plan with PayPal"
                      {:id id
                       :response clj-body})))
    nil))
