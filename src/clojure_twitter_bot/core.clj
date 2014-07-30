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
   [clojure.tools.logging :as log]
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
;"(defn reply-or-manual-rt? [s] (reduce #(if (.startsWith %1 %2) (reduced false) s) s ["@" "\"@" "“@" "RT" "MT"]))"
(def reply-or-manual-rt?
  (some-fn #(.startsWith % "@")
           #(.startsWith % "\"@")
           #(.startsWith % "“@")
           #(.contains % "RT ")
           #(.contains % "MT ")))

(def bad-words?
  (some-fn #(.contains % "gay")
           #(.contains % "fag")
           #(.contains % "nig")
           #(.contains % "cunt")
           #(.contains % "ask.fm/")))

(defn candidate?
  [tweetMap]
  (not (or (empty? tweetMap)
           (contains? tweetMap :retweeted_status)
           (= (get-in tweetMap [:user :screen_name]) "NowImBot")
           (.contains (:text tweetMap) "NowImBot")
           (reply-or-manual-rt? (:text tweetMap))
           (bad-words? (:text tweetMap)))))

(defn extract-tweet-info
  [tweetMap]
    {:tweet (:text tweetMap),
     :tweet_link (str "http://twitter.com/" (get-in tweetMap [:user :screen_name]) "/status/" (:id_str tweetMap)),
     :screen-name (get-in tweetMap [:user :screen_name])
     :id_str (:id_str tweetMap)})

(defn now [] (java.util.Date.))

(def whos ["who" "whoo" "whooo" "whoooo" "whooooo" "whooooo" "whoooooo" "whooooooo"])
(def manualRTs ["@" "\"@" "“@" "RT" "MT"])

(defn ends-with-who?
  [tweetMap]
  (and (seq tweetMap)
       (try (if-let [who-value (or
                    (re-find #"(?is).*(?:[^a-zA-Z0-9']| then| and| but| or| of| lol| lmao | with)+\s+who+[\\?|\n]+[^a-zA-Z0-9\s]*" (clojure.string/trim (:tweet tweetMap)))
                    (re-find #"(?is)^who+[\\?|\n]+[^a-zA-Z0-9\s]*" (clojure.string/trim (:tweet tweetMap))))]
                  {:who (clojure.string/trim who-value), :url (:tweet_link tweetMap)}
                 nil
                )
            (catch Exception e (do
                                 (log/error e (str "regex issue: " tweetMap))
                                 nil)))))

(def who-stream (client/create-twitter-stream twitter.api.streaming/statuses-filter
                                          :oauth-creds my-creds :params {:track (clojure.string/join "," whos)}))

(defn do-every
  [ms callback]
  (loop []
    (do
      (Thread/sleep ms)
      (try (callback)
           (catch Exception e (log/error e (str "caught exception: " (.getMessage e))))))
    (recur)))

(def interval 30)
(def empty-state {:candidates [] :deletes [] :minutes-since-update 0})

(defn mike-jones
  [state]

  (defn reset-state
    [response]
    (if (== (quot (:code (ac/status response)) 100) 2)
      (reset! state empty-state)
      (log/error "error posting tweet, response code " (:code (ac/status response)) ": " (ac/string response))))

  (comment "TODO: handle deletes")
  (if-let [candidates (seq (:candidates @state))]
    (let [who (rand-nth candidates)
          tweet (str (:who who) "\nMIKE JONES\n\n" (:url who))]
      (log/debug (str "MIKE JONES-ING " who))
      (log/debug (str "full tweet: " tweet))
      (log/debug (str "deletes: " (seq (:deletes @state))))
      (statuses-update :oauth-creds my-creds
                       :params {:status tweet},
                       :callbacks (SyncSingleCallback. reset-state
                                                       response-throw-error
                                                       exception-rethrow)))
    (log/debug (str "no candidates found after " (:minutes-since-update @state) " minutes"))))

(defn check-for-who
  [state]
  (let
    [ stream (client/retrieve-queues who-stream)
      tweets (:tweet stream)
      deletes (:delete stream)]
        (do
          (swap! state update-in [:deletes] concat (seq deletes))
          (if-let [candidates (seq (->> tweets
                                        (filter candidate?)
                                        (map extract-tweet-info)
                                        (map ends-with-who?)
                                        (filter identity)
                                        (filter #(< (count (:who %)) 104))))]
            (do
              (log/debug (str "adding " (count candidates) " candidates to list:\n" (clojure.string/join "\n" (map :who candidates))))
              (swap! state update-in [:candidates] concat candidates))
            (log/debug "no candidates found on regular interval check"))
          (if (>= (:minutes-since-update @state) interval)
            (mike-jones state)))))

(defn -main
  []
  (let [state (atom (assoc empty-state :minutes-since-update interval))]

    (defn run-bot
      []
      (do
        (swap! state #(update-in % [:minutes-since-update] inc))
        (check-for-who state)
        ))

    (do
      (log/debug "STARTING MIKE JONES BOT")
      (client/start-twitter-stream who-stream)
      (doto
          (Thread. (do-every 60000 run-bot))
        (.setDaemon true)
        (.start)))))