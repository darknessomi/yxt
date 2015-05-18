(ns yxt.util
  (:require [clojure.data.json :as json]

            [yxt.redis :as r]
            [yxt.db :as d]))

(defn rand-string [characters n]
  (->> (fn [] (rand-nth characters))
       repeatedly
       (take n)
       (apply str)))

(defn- json-request? [request]
  (if-let [type (:content-type request)]
    (not (empty? (re-find #"^application/(.+\+)?json" type)))))

(defn- read-json [request & json-opt]
  (if (json-request? request)
    (if-let [body (:body request)]
      (let [body-string (slurp body)]
        (try
          [true (apply json/read-str body-string json-opt)]
          (catch Exception ex
            [false nil]))))))

(def not-json
  {:status 400
   :handlers {"Content-Type" "application/json"}
   :body {:error "Malformed JSON in request body or Headers is no json"}})

(defn wrap-json-body
  [handler & json-opt]
  (fn [request]
    (if-let [[valid? json] (apply read-json request json-opt)]
      (if valid?
        (handler (assoc request :body json))
        not-json)
      (handler request))))

(defn wrap-session-token
  [handler & opts]
  (let [{:keys [hello]} opts]
    (fn [request]
      (if-let [session-token (get (-> request :headers) "Session-Token")]
        (if-let [data (or (r/get-session-token session-token)
                          (let [db-data (d/query-person-for-cache session-token)]))]
          (handler (assoc request :user data))
          {:status 401
           :handlers {"Content-Type" "application/json"}
           :body "SessionToken is wrong"})
        (handler request)))))

(defmacro defhandler [name args & body]
  (let [verify (:verify (first body))
        [code verify] (if verify
                        [(rest body) verify]
                        [body (list :defaul nil)])]
    `(defn ~name [req#]
       (let [{:keys ~args} (:params req#)
             ~'req req#]
         (if-let [error# (cond ~@verify)]
           {:body {:error error#}})
         (do
           ~@code)))))

(defmacro deflogin [name args & body]
  (let [verify (:verify (first body))
        [code verify] (if verify
                        [(rest body) verify]
                        [body (list :defaul nil)])]
    `(defn ~name [req#]
       (if (:user req#)
           (let [{:keys ~args} (:params req#)
                 ~'req req#]
             (if-let [error# (cond ~@verify)]
               {:body {:error error#}}
               (do
                 ~@code)))
           {:status 401
            :body {:error "You don't login."}}))))

(defhandler tester
  []
  {:verify [(when-let [hello (-> req
                                 :body
                                 :hello)]
              (not (string? hello))) "hello world"]}
  (let [tmp (d/query
             ["select * from yxt_user"])]
    {:body tmp}))
