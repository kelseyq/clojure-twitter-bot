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


(defn extractTweetInfo
  [tweetMap]
    (try (if-not (empty? tweetMap)
   {:tweet (clojure.string/lower-case (:text tweetMap)), :tweet_id (:id_str tweetMap),
    :user (get-in tweetMap [:user :id_str]), :screen-name (get-in tweetMap [:user :screen_name])}
   {})
  (catch Exception e (do
    (println (str "non-tweet received: " tweetMap))
                      {}))))


(defn replyToTweet
  [tweetMap]
    (statuses-update :oauth-creds my-creds
                   :params {:status (str "@" (:screen-name tweetMap) " yes you can"),
                            :in_reply_to_status_id (:tweet_id tweetMap)}
                            :callbacks (SyncSingleCallback. response-return-body
                                                            response-throw-error
                                                            exception-rethrow)))

(defn now [] (java.util.Date.))

(defn filterTweet
  [tweetMap]
    (when (and (not (empty? tweetMap)) (.contains (:tweet tweetMap) "can i kick it"))
      (println (str (:screen-name tweetMap) " can kick it at " (now)))
      (replyToTweet tweetMap)))

(def stream (client/create-twitter-stream twitter.api.streaming/user-stream
                                          :oauth-creds my-creds :params {:with "user"}))

(defn do-every
  [ms callback]
  (loop []
    (do
      (Thread/sleep ms)
      (try (callback)
           (catch Exception e (println (str "caught exception: " (.getMessage e))))))
    (recur)))

(defn check-if-can-kick-it
  []
  (let
    [tweets (:tweet (client/retrieve-queues stream))]
    (dorun (->>
       (map extractTweetInfo tweets)
       (map filterTweet)))))

(defn -main
  []
  (do
    (client/start-twitter-stream stream)
    (do-every 5000 check-if-can-kick-it)))
