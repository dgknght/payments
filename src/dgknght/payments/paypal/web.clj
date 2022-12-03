(ns dgknght.payments.paypal.web
  (:require [clojure.string :as string]
            [lambdaisland.uri :refer [map->query-string
                                      uri]]
            [dgknght.payments.paypal :refer [config]]
            [dgknght.payments.paypal.api :as pp]))

(def ^:private base-uri "https://www.paypal.com/sdk/js")

(defn- query-string []
  (map->query-string
    (cond->
      {:components (->> (config :components)
                        (map name)
                        (string/join ","))
       :client-id (config :client-id)
       :currency "USD"}

      (config :include-locale?)
      (assoc :locale "en_US"
             :buyer-country "US")

      (config :components :hosted-fields)
      (assoc :data-client-token  (:client-token (pp/generate-client-token))))))

(defn- script-url []
  (-> base-uri
      uri
      (assoc :query (query-string))
      str))

(defn script-tags []
  [:script {:src (script-url)}])
