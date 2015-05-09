(ns yxt.handler
  (:require [compojure.core :refer :all]
            [compojure.route :as route]
            [ring.middleware.defaults :refer [wrap-defaults api-defaults]]
            [ring.middleware.anti-forgery :as anti]
            [clojure.pprint :as pp]
            [noir.session :as ns]
            [clojure.data.json :as json]
            [clojure.tools.logging :as log]

            [yxt.util :as yu]
            [yxt.face :as yf]
            [yxt.db :as yd]))

(defmacro mylog
  [s]
  `(log/info (str "\n" (with-out-str (clojure.pprint/pprint ~s)))))

(defn wrap-json
  [handler]
  (fn [req]
    (let [resp (handler req)
          body (:body resp)]
      (-> resp
          (assoc :body (json/write-str body))
          (assoc-in [:headers "Content-Type"] "application/json;charset=UTF-8")))))

(defn wrap-req
  [handler]
  (fn [req]
    (mylog req)
    (handler req)))

(def json-routes
  (-> (routes
       (GET "/" [] (str anti/*anti-forgery-token*))
       (POST "/yxt" [] yf/yxt)
       (GET "/me" [] yf/person-get)
       (POST "/y/:foo" [] yd/tester))
      (wrap-routes wrap-req)
      (wrap-routes yu/wrap-json-body :key-fn keyword)
      (wrap-routes wrap-defaults api-defaults)
      (wrap-routes wrap-json)))

(def not-json-routes
  (-> (routes
       (route/resources "/"))))

(def app
  (-> (routes json-routes
              not-json-routes
              (route/not-found "Not Found"))))
