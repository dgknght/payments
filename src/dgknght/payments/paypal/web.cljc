(ns dgknght.payments.paypal.web
  (:require #?(:clj [clojure.spec.alpha :as s]
               :cljs [cljs.spec.alpha :as s])
            [clojure.walk :refer [postwalk]]
            [clojure.string :as string]
            [lambdaisland.uri :refer [map->query-string
                                      uri]]
            #?(:clj [dgknght.payments.paypal :refer [config]])
            #?(:clj [dgknght.payments.paypal.api :as pp])))

(s/def ::client-id       string?)
(s/def ::buyer-country   #{"US"
                           "CA"
                           "GB"
                           "DE"
                           "FR"})
(s/def ::commit          boolean?)
(s/def ::component       #{:buttons
                           :marks
                           :hosted-fields
                           :funding-eligibility
                           :messages})
(s/def ::components      (s/coll-of ::component))
(s/def ::currency        #{"USD"
                           "CAD"
                           "EUR"})
(s/def ::debug           boolean?)
(def funding-types
  #{"card"
    "credit"
    "paylater"
    "bancontact"
    "blik"
    "eps"
    "giropay"
    "ideal"
    "mercadopago"
    "mybank"
    "p"24
    "sepa"
    "sofort"
    "venmo"})
(s/def ::disable-funding funding-types)
(s/def ::enable-funding  funding-types)
(s/def ::integration-date (s/and string?
                                 (partial re-matches #"\A\d{4}-\d{2}-\d{2}\z")))
(s/def ::intent           #{:capture
                            :authorize
                            :subscription
                            :tokenize})
(s/def ::locale           (s/and string?
                                 (partial re-matches #"\A[a-z]{2}_[A-Z]{2}\z")))
(s/def ::merchant-id      string?)
(s/def ::vault            boolean?)

(s/def ::options (s/keys :req-un [::client-id]
                         :opt-un [::buyer-country
                                  ::commit
                                  ::components
                                  ::currency
                                  ::debug
                                  ::disable-funding
                                  ::enable-funding
                                  ::integration-date
                                  ::intent
                                  ::locale
                                  ::merchant-id
                                  ::vault]))

(def ^:private base-uri "https://www.paypal.com/sdk/js")

(def default-opts
  {:components #{:buttons}})

(defn- set-vault
  [{:keys [intent] :as m}]
  (if (= :subscription intent)
    (assoc m :vault true)
    m))

(defn- format-components
  [{:keys [components] :as m}]
  (if components
    (assoc m :components (->> components
                              (map name)
                              (string/join ",")))
    m))

(defn- query-string
  [opts]
  {:pre [(s/valid? ::options opts)]}
  (-> opts
      set-vault
      format-components
      map->query-string))

(defn- script-url
  [opts]
  (-> base-uri
      uri
      (assoc :query
             #_{:clj-kondo/ignore [:invalid-arity]}
             (query-string opts))
      str))

#?(:clj
   (defn- config-opts []
     (let [{:keys [components] :as cfg} (config)]
       (cond-> cfg
         (components :hosted-fields)
         (update-in [:data] (fnil merge {}) (pp/generate-client-token))))))

(defn script-tags
  #?(:clj ([]
           #_{:clj-kondo/ignore [:invalid-arity]}
           (script-tags (config-opts))))
  ([opts]
   [:script (merge {:src #_{:clj-kondo/ignore [:invalid-arity]} (script-url (dissoc opts :data))}
                   (postwalk
                     (fn [v]
                       (if (keyword? v)
                         (keyword (str "data-" (name v)))
                         v))
                     (:data opts)))]))
