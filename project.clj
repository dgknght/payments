(defproject com.github.dgknght/payments "0.1.7"
  :description "Library for interacting with various payment providers"
  :url "http://github.com/dgknght/payments"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.12.5"]
                 [lambdaisland/uri "1.19.155"]
                 [ch.qos.logback/logback-classic "1.6.3"]
                 [cheshire "6.2.0"]
                 [clj-http "3.13.1"]
                 [camel-snake-kebab "0.4.3"]
                 [com.github.dgknght/app-lib "0.3.52"
                  :exclusions [camel-snake-kebab]]]
  :plugins [[lein-cljsbuild "1.1.8"]
            [lein-doo "0.1.11"]
            [lein-cloverage "1.2.4"]]
  :cljsbuild {:builds [{:source-paths ["src"]
                        :compiler {:output-to "target/main.js"
                                   :optimizations :whitespace
                                   :pretty-print true}
                        :jar true}
                       {:id "test"
                        :source-paths ["src" "test"]
                        :compiler {:output-to "out/testable.js"
                                   :main dgknght.payments.test-runner
                                   :optimizations :none}
                        :jar true}]}
  :doo {:build "test"
        :alias {:default [:firefox-headless]}}
  :cloverage {:ns-exclude-regex [#"dgknght.payments.braintree"
                                 #"dgknght.payments.braintree.api"]})
