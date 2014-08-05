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

(comment
  (defn reply-or-manual-rt?
    [s]
    (reduce #(if-not (.startsWith %1 %2) (reduced false) s) s ["@" "\"@" "“@" "RT" "MT"]))
  )

(defn disallowed-words?
    [input]
      (let [lowercase (clojure.string/lower-case input)]
        ((some-fn #(.contains % "gay")
                  #(.contains % "fag")
                  #(.contains % "nig")
                  #(.contains % "cunt")
                  #(.contains % "rape")) lowercase)))

(def reply-or-manual-rt?
  (some-fn #(.startsWith % "@")
           #(.startsWith % "\"@")
           #(.startsWith % "“@")
           #(.contains % "RT ")
           #(.contains % "MT ")))


(defn candidate?
  [tweetMap]
      (not (or (empty? tweetMap)
               (contains? tweetMap :retweeted_status)
               (= (get-in tweetMap [:user :screen_name]) "NowImBot")
               (.contains (:text tweetMap) "NowImBot")
               (some #(.contains % "ask.fm") (map :expanded_url (get-in tweetMap [:entities :urls])))
               (reply-or-manual-rt? (:text tweetMap))
               (disallowed-words? (:text tweetMap)))))

(defn extract-tweet-info
  [tweetMap]
    {:tweet (:text tweetMap),
     :tweet_link (str "https://twitter.com/" (get-in tweetMap [:user :screen_name]) "/status/" (:id_str tweetMap)),
     :screen-name (get-in tweetMap [:user :screen_name])
     :id_str (:id_str tweetMap)})

(defn now [] (java.util.Date.))

(def whos ["who" "whoo" "whooo" "whoooo" "whooooo" "whooooo" "whoooooo" "whooooooo" "whoooooooo" "whooooooooo?" "whoooooooooo"])
(def numbers ["281 330 8004" "281-330-8004" "(281)330-8004" "(281) 330-8004"])

(comment (re-find #"(?is)^who+\?+[^a-zA-Z0-9\s]*" (clojure.string/trim (:tweet tweetMap))))

(defn ends-with-who?
  [tweetMap]
      (try (if-let [who-value (re-find #"(?is).*(?:[^a-zA-Z0-9'é]| then| and| but| or| of| lol| lmao| like)+\s+who+!*\?+[^a-zA-Z0-9\s]*" (clojure.string/trim (:tweet tweetMap)))]
             {:who (clojure.string/trim who-value), :url (:tweet_link tweetMap)}
             nil)
           (catch Exception e (do
                                (log/error e (str "regex issue: " tweetMap))
                                nil))))

(def who-stream (client/create-twitter-stream twitter.api.streaming/statuses-filter
                                          :oauth-creds my-creds :params {:track (clojure.string/join "," whos)}))

(def mentions-stream (client/create-twitter-stream twitter.api.streaming/user-stream
                                              :oauth-creds my-creds :params {:with "user"}))

(def number-stream (client/create-twitter-stream twitter.api.streaming/statuses-filter
                                              :oauth-creds my-creds :params {:track (clojure.string/join "," numbers)}))

(defn do-every
  [ms callback]
  (loop []
    (do
      (Thread/sleep ms)
      (try (callback)
           (catch Exception e (log/error e (str "caught exception: " (.getMessage e))))))
    (recur)))

(def mike-jones-interval 30)
(def empty-state {:candidates [] :minutes-since-update 0})

(defn mike-jones
  [state previous]

  (defn reset-state
    [who response]
    (if (== (quot (:code (ac/status response)) 100) 2)
      (do
          (reset! state empty-state)
          (swap! previous conj (:who who)))
      (log/error "error posting tweet, response code " (:code (ac/status response)) ": " (ac/string response))))

  (if-let [candidates (seq (:candidates @state))]
    (let [who (rand-nth candidates)
          tweet (str (:who who) "\nMIKE JONES\n" (:url who))]
      (log/debug (str "MIKE JONES-ING " who))
      (statuses-update :oauth-creds my-creds
                       :params {:status tweet
                                :lat 29.804295
                                :long -95.388141},
                       :callbacks (SyncSingleCallback. (partial reset-state who)
                                                       response-throw-error
                                                       exception-rethrow)))
    (log/debug (str "no mike jones candidates found after " (:minutes-since-update @state) " minutes"))))

(defn log-size
  [coll]
  (log/debug (str "filtering " (count coll) " tweets"))
   coll)

(defn check-for-who
  [state previous]
          (if-let [candidates (seq (->> (:tweet (client/retrieve-queues who-stream))
                                        (log-size)
                                        (filter candidate?)
                                        (map extract-tweet-info)
                                        (map ends-with-who?)
                                        (filter identity)
                                        (filter #(not (@previous (:who %))))
                                        (filter #(not (.contains (:who %) "@")))
                                        (filter #(not (.contains (:who %) "http")))
                                        (filter #(< (count (:who %)) 104))))]
            (do
              (log/debug (str "adding " (count candidates) " candidates to mike jones list:\n" (clojure.string/join "\n" (map :who candidates))))
              (swap! state update-in [:candidates] concat candidates))
            (log/debug "no candidates found on regular mike jones interval check"))
          (if (>= (:minutes-since-update @state) mike-jones-interval)
            (mike-jones state previous)))

(comment            (= (:in_reply_to_user_id_str tweetMap) "2678831582"))

(defn is-legit-mention?
  [tweetMap]
       (not (or (empty? tweetMap)
                (= (get-in tweetMap [:user :screen_name]) "NowImBot")
                (and (= (get-in tweetMap [:retweeted_status :user :screen_name]) "NowImBot")
                     (.startsWith (get-in tweetMap [:retweeted_status :text]) "@")))))

(def replies
  ["back then % didn't want me, now I'm hot % all on me"
   "281 330 8004 hit Mike Jones up on the low cause Mike Jones about to blow"
   "still tweetin on fo' fo's, wrapped in fo' vogues"])

(defn get-mention-reply
  [tweetMap]
  (if (= (get-in tweetMap [:retweeted_status :user :screen_name]) "NowImBot")
    (clojure.string/replace (rand-nth replies) #"%" name)
    (let [text (clojure.string/lower-case (clojure.string/replace (:text tweetMap) #"([\"|“].*[\"|”])" ""))]
       (cond (.contains text "mike") "WHO?"
             (.contains text "who") "MIKE JONES"
             :else (clojure.string/replace (rand-nth replies) #"%" name)))))

(defn reply-to-mention
  [mention]
  (let [name (str "@" (get-in mention [:user :screen_name]))
        tweet-text (get-mention-reply mention)
        tweet (str name " " tweet-text) ]
    (log/debug (str "replying to mention from " name))
    (try
      (statuses-update :oauth-creds my-creds
                       :params {:status tweet
                                :in_reply_to_status_id (:id_str mention)
                                :lat 29.804295
                                :long -95.388141},
                       :callbacks (SyncSingleCallback. response-return-body
                                                       response-throw-error
                                                       exception-rethrow))
      (catch Exception e (log/error e (str "error replying to mention from " (get-in mention [:user :screen_name])))))))

(defn reply-to-mentions
  []
  (when-let [mentions (seq (->> (:tweet (client/retrieve-queues mentions-stream))
                                 (filter is-legit-mention?)))]
    (doseq [m mentions] (reply-to-mention m))))

(defn reply-to-number
  [number-tweet]
  (log/debug (str "replying to number from " (:screen-name number-tweet)))
  (try
      (statuses-update :oauth-creds my-creds
                       :params {:status (str "@" (:screen-name number-tweet) " hit Mike Jones up on the low cause Mike Jones about to blow")
                                :in_reply_to_status_id (:id_str number-tweet)
                                :lat 29.804295
                                :long -95.388141},
                       :callbacks (SyncSingleCallback. response-return-body
                                                       response-throw-error
                                                       exception-rethrow))
      (catch Exception e (log/error e (str "error replying to number from " (:screen-name number-tweet))))))


(defn reply-to-numbers
  []
  (when-let [number (seq (->> (:tweet (client/retrieve-queues number-stream))
                                (filter candidate?)
                                (map extract-tweet-info)
                                (filter #(not (.contains (clojure.string/lower-case (:tweet %)) "mike")))
                                (filter #(not (.contains (clojure.string/lower-case (:tweet %)) "jones")))
                                ))]
    (doseq [n number] (reply-to-number n))))

(defn reply-to-follow
  [follow]
  (let [name (str "@" (get-in follow [:source :screen_name]))
        tweet-text (clojure.string/replace (rand-nth replies) #"%" name)
        tweet (str name " " tweet-text) ]
    (log/debug (str "replying to follow from " name))
    (try
      (statuses-update :oauth-creds my-creds
                       :params {:status tweet
                                :lat 29.804295
                                :long -95.388141},
                       :callbacks (SyncSingleCallback. response-return-body
                                                       response-throw-error
                                                       exception-rethrow))
      (catch Exception e (log/error e (str "error replying to follow from " (get-in follow [:source :screen_name])))))))


(defn reply-to-follows
  []
  (when-let [follows (seq (->> (:unknown (client/retrieve-queues mentions-stream))
                               (filter #(= (:event %) "follow"))))]
    (doseq [f follows] (reply-to-follow f))))


(defn -main
  []
  (let [mike-jones-state (atom (assoc empty-state :minutes-since-update mike-jones-interval))
        previous (atom #{})]

    (defn respond-who
      []
      (do
        (swap! mike-jones-state #(update-in % [:minutes-since-update] inc))
        (check-for-who mike-jones-state previous)
        ))

      (log/debug "STARTING MIKE JONES BOT")

      (client/start-twitter-stream mentions-stream)
      (client/start-twitter-stream number-stream)
      (client/start-twitter-stream who-stream)

        (future (log/debug "STARTING MENTIONS STREAM")
                       (do-every 60500 reply-to-mentions))

        (future (log/debug "STARTING NUMBERS STREAM")
                       (do-every 60250 reply-to-numbers))

        (future (log/debug "STARTING WHO STREAM")
                       (do-every 60000 respond-who))

        (future (log/debug "STARTING FOLLOW STREAM")
              (do-every 60750 reply-to-follows))


      ))