(ns dgknght.payments.core)

(def ^:dynamic *config* {})

(defmacro with-config
  "Establish confirmation for a call to a function 
  within the library.
  
  {:paypal {:enviornment \"sandbox || production\"
            :components #{:buttons :hosted-fields :marks :funding-eligibility :messages}
            :client-id \"get this from PayPal\"
            :secret \"get this from PayPal\"}
            :include-locale? true || false}"
  [config & body]
  `(binding [*config* ~config]
     ~@body))
