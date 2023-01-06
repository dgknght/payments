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
(s/def ::buy-country     #{"US"
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
(s/def ::components (s/coll-of ::component))
(s/def ::currency        #{"USD"
                           "CAD"
                           "EUR"})
(s/def ::debug           boolean?)
(s/def ::disable-funding #{"card"
                           "credit"
                           "bancontact"})
(s/def ::enable-funding  #{"venmo"
                           "paylater"})
(s/def ::integration-date (s/and string?
                                 #(re-matches #"\A\d{4}-\d{2}-\d{2}\z " %)))
(s/def ::intent           #{:capture
                            :authorize
                            :subscription
                            :tokenize})
(s/def ::locale           #{"en_US"
                            "fr_FR"
                            "de_DE"})
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
  {:components #{:buttons}
   :currency "USD"
   :local "en_US"
   :buyer-country "US"})

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
                              (string/join ",")
                              ))
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
