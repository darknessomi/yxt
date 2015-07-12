(ns yxt.ws
  (:require [yxt.adapter :refer [send! close!
                                 remote-addr idle-timeout!
                                 idle-timeout connected?
                                 get-req get-resp]]
            [clojure.data.json :as json])
  (:import [yxt.adapter WebSocketProtocol]))

(def whole (atom {}))

(add-watch whole :whole (fn [_ _ _ n]
                          (println n)))

(defn notic
  ([ows msg]
   (notic ows msg false))
  ([ows msg admin?]
   (when-not (empty? @whole)
     (dorun (map (fn [[ws {:keys [user]}]]
                   (send! ws
                          (json/write-str
                           (if admin?
                             {:user "Admin"
                              :message msg
                              :time (System/currentTimeMillis)}
                             {:user (:user (get @whole ows))
                              :message msg
                              :time (System/currentTimeMillis)}))))
                 @whole)))))

(defn on-conn
  [^WebSocketProtocol ws]
  (let [req (get-req ws)]
    (if (empty? @whole)
      (swap! whole assoc ws {:user "yxt1"})
      (let [c (count @whole)
            user (str "yxt" (inc c))]
        (swap! whole assoc ws {:user user})
        (notic ws (str user " join the room") true)))
    (send! ws (json/write-str {:user "Admin"
                               :message (str
                                         "Current room users : "
                                         (clojure.string/join
                                          ", "
                                          (map (fn [[_ m]] (:user m)) @whole)))
                               :time (System/currentTimeMillis)}))))

(defn on-close
  [^WebSocketProtocol ws status reason]
  (println status reason)
  (let [user (:user (get @whole ws))]
    (when-not (empty? @whole)
      (swap! whole dissoc ws)
      (notic ws (str user  " leavl the room") true))))

(defn on-text
  [^WebSocketProtocol ws ^String text-message]
  (notic ws text-message))

(def ws-handler {:on-connect on-conn
                 :on-error (fn [^WebSocketProtocol ws e] (println "error"))
                 :on-close on-close
                 :on-text on-text
                 :on-bytes (fn [^WebSocketProtocol ws bytes offset len]
                             (println "hello"))})
