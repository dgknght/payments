(ns dgknght.payments.test-runner
  (:require [doo.runner :refer-macros [doo-tests]]
            [dgknght.payments.paypal-test]))

(doo-tests 'dgknght.payments.paypal-test)
