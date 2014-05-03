(ns clojure-twitter-bot.core
  (:gen-class)
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
   [clojure.java.io :as io])
  (:import
   (twitter.callbacks.protocols SyncSingleCallback)
   (twitter.callbacks.protocols SyncStreamingCallback)))


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

(defn readJson
  [theString]
  (try (if-not (clojure.string/blank? theString)
   (json/read-str theString :key-fn keyword)
   {})
  (catch Exception e (do
    (println (str "caught exception: " (.getMessage e) " for input: " theString))
                      {}))))

(defn intOrZero
  [theString]
  (try (Integer/parseInt (clojure.string/trim theString))
       (catch Exception e 0)))

(defn filterTweet
  [tweetMap]
  (when (and (not (empty? tweetMap)) (.contains (:tweet tweetMap) "can i kick it"))
        (replyToTweet tweetMap)))

(defn -main
  []
  (let [expected (atom 0)
        cnt (atom 0)
        tweet (atom "")]

    (defn updateWithChunk
      [newText]
      (do
        (swap! cnt #(+ (count (byte-array (map byte newText))) %)) ;update count of bytes
        (swap! tweet #(str % newText)) ;update current message
      ))

        (user-stream :params {:delimited "length", :with "user"}
         :oauth-creds my-creds
         :callbacks (SyncStreamingCallback. (fn [_resp payload]
                                                 (let [bodyString (.toString payload)]
                                                   (do
                                                     (if (== @expected 0)
                                                       (let [splitBody (clojure.string/split bodyString #"\n" 2)]
                                                         (do
                                                          (reset! expected (intOrZero (get splitBody 0)));update the expected # of bytes
                                                          (updateWithChunk (get splitBody (dec (count splitBody))))
                                                          ))
                                                       (do
                                                          (updateWithChunk bodyString)
                                                        ))
                                                      (if (>= @cnt @expected)
                                                        (do
                                                          (-> @tweet
                                                              readJson
                                                              extractTweetInfo
                                                              filterTweet)
                                                          (reset! expected 0)
                                                          (reset! cnt 0)
                                                          (reset! tweet "")
                                                         )
                                                    ))))
                                              (fn [_resp] (println _resp))
                                              (fn [_resp ex] (println _resp))))))

