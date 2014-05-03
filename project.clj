(defproject clojure-twitter-bot "0.1.0-SNAPSHOT"
  :description "A simple twitter bot"
  :license {:name "Eclipse Public License"
            :url "http://www.eclipse.org/legal/epl-v10.html"}
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [twitter-api "0.7.5"]
                 [twitter-streaming-client "0.3.1"]]
  :resource-paths ["resources"]
  :main clojure-twitter-bot.core)
