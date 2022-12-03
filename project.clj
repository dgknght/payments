(defproject com.github.dgknght/payments "0.1.0-SNAPSHOT"
  :description "Library for interacting with various payment providers"
  :url "http://github.com/dgknght/payments"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.10.1"]
                 [lambdaisland/uri "1.13.95"]
                 [ch.qos.logback/logback-classic "1.2.3"]
                 [cheshire "5.11.0"]
                 [clj-http "3.12.3"]
                 [com.github.dgknght/app-lib "0.2.7"]])
