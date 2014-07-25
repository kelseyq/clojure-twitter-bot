(ns clojure-twitter-bot.core
  (:use
   [twitter.oauth]
   [twitter.callbacks]
   [twitter.callbacks.handlers]
   [twitter.api.restful]
   [twitter.api.streaming])
  (:require
   [clojure.data.json :as json]
   [http.async.client :as ac]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [twitter-streaming-client.core :as client])
  (:import
   (twitter.callbacks.protocols SyncSingleCallback)
   (twitter.callbacks.protocols SyncStreamingCallback))
  (:gen-class))

(defn getEnvVar
  [varName]
  (or (System/getenv (name varName))
      (get (-> "twitterkeys.txt"
               io/resource
               slurp
               clojure.edn/read-string) varName)))

(def my-creds (make-oauth-creds (getEnvVar :APP_KEY)
                                (getEnvVar :APP_SECRET)
                                (getEnvVar :USER_TOKEN)
                                (getEnvVar :USER_SECRET)))

(comment "TODO: these with map")
(def reply-or-manual-rt?
  (some-fn #(.startsWith % "@")
           #(.startsWith % "\"@")
           #(.startsWith % "“@")
           #(.startsWith % "RT")
           #(.startsWith % "MT")))

(def bad-words?
  (some-fn #(.contains % "gay")
           #(.contains % "fag")
           #(.contains % "nig")
           #(.contains % "cunt")))

(defn candidate?
  [tweetMap]
  (not (or (empty? tweetMap)
           (contains? tweetMap :retweeted_status)
           (reply-or-manual-rt? (:text tweetMap))
           (bad-words? (:text tweetMap)))))

(defn extract-tweet-info
  [tweetMap]
    {:tweet (:text tweetMap),
     :tweet_link (str "http://twitter.com/" (get-in tweetMap [:user :screen_name]) "/status/" (:id_str tweetMap)),
     :screen-name (get-in tweetMap [:user :screen_name])})

(defn now [] (java.util.Date.))

(defn mike-jones
  [who]
  (do (println (str now ": MIKE JONES-ING " who))
      (statuses-update :oauth-creds my-creds
                       :params {:status (str "\"" (:who who) "\"\n MIKE JONES\n\n" (:url who))},
                       :callbacks (SyncSingleCallback. response-return-body
                                                       response-throw-error
                                                       exception-rethrow)))
)

(def whos ["who" "whoo" "whooo" "whoooo" "whooooo" "whooooo" "whoooooo" "whooooooo"])
(def manualRTs ["@" "\"@" "“@" "RT" "MT"])

(defn ends-with-who?
  [tweetMap]
  (and (seq tweetMap)
       (try (if-let [who-value (or
                    (re-find #"(?is).*(?:[^a-zA-Z0-9']| then| and| but| or| of| lol| lmao)+\s+who+[\\?|!|\n]+[^a-zA-Z0-9\s]*" (clojure.string/trim (:tweet tweetMap)))
                    (re-find #"(?is)^who+[\\?|!|\n]+[^a-zA-Z0-9\s]*" (clojure.string/trim (:tweet tweetMap))))]
                  {:who (clojure.string/trim who-value), :url (:tweet_link tweetMap)}
                 nil
                )
            (catch Exception e (do
                                 (println (str "regex issue: " tweetMap))
                                 nil)))))

(def stream (client/create-twitter-stream twitter.api.streaming/statuses-filter
                                          :oauth-creds my-creds :params {:track (clojure.string/join "," whos)}))

(defn do-every
  [ms callback]
  (loop []
    (do
      (Thread/sleep ms)
      (try (callback)
           (catch Exception e (println (str "caught exception: " (.getMessage e))))))
    (recur)))

(defn check-for-who
  []
  (let
    [tweets (:tweet (client/retrieve-queues stream))]
    (if-let [candidates (->> tweets
       (filter candidate?)
       (map extract-tweet-info)
       (map ends-with-who?)
       (filter identity)
       (filter #(< (count (:who %)) 102)))]
      (mike-jones (rand-nth candidates))
      (println "no candidates found")
      )))

(defn -main
  []
  (do
    (println "STARTING MIKE JONES BOT")
    (client/start-twitter-stream stream)
    (check-for-who)
    (do-every 1800000 check-for-who)))
