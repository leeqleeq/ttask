(defproject ttask "0.1.0-SNAPSHOT"
  :description "FIXME: write description"
  :url "http://example.com/FIXME"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.7.0"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [org.clojure/data.zip "0.1.1"]
                 [clj-http "2.0.0"]
                 [enlive "1.1.6"]
                 [com.taoensso/timbre "4.0.2"]
                 [org.clojure/tools.cli "0.3.1"]]
  :main ttask.core)
