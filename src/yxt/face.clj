(ns yxt.face
  (:require [clj-http.client :as http]
            [noir.session :as ns]
            [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [clojure.java.io :as io]

            [yxt.util :refer :all]
            [yxt.db :refer [insert! query update!] :as d]
            [yxt.redis :as r]
            [yxt.log :as yl])
  (:use [yxt.key :only [api-key api-secret api-map]])
  (:import [javax.xml.bind DatatypeConverter]))

(defn to-map [^String s]
  (json/read-str s :key-fn keyword))

(defn own []
  (str (java.util.UUID/randomUUID)))

(defn doreq [resp]
  (let [tmp (to-map (:body resp))]
    (log/info tmp)
    tmp))


(defn create-faceset [^String faceset-name ^String tag]
  (doreq (http/post "https://apicn.faceplusplus.com/v2/faceset/create"
                    {:form-params {:api_key api-key
                                   :api_secret api-secret
                                   :faceset_name faceset-name
                                   :tag tag}})))

(defn get-group-list []
  (doreq (http/get "https://apicn.faceplusplus.com/v2/info/get_group_list"
                   {:query-params {:api_key api-key
                                   :api_secret api-secret}})))

(defn train-identify
  [group-name]
  (doreq (http/get "https://apicn.faceplusplus.com/v2/train/identify"
                   {:query-params (merge api-map
                                         {:group_name group-name})})))

(defn face-identify
  ([img group-name]
   (face-identify img group-name "normal"))
  ([img group-name mode]
   (doreq (http/post "https://apicn.faceplusplus.com/v2/recognition/identify"
                     {:multipart [{:name "img" :content img}
                                  {:name "api_key" :content api-key}
                                  {:name "api_secret" :content api-secret}
                                  {:name "group_name" :content group-name}
                                  {:name "mode" :content mode}]}))))

(defn create-group
  ([group-name]
   (create-group group-name nil))
  ([group-name person-id]
   (doreq (http/post "https://apicn.faceplusplus.com/v2/group/create"
                     {:form-params (merge api-map
                                          (if person-id
                                            {:group_name group-name
                                             :person_id person-id}
                                            {:group_name group-name}))}))))

(defn create-person
  [persone-name face-id gourp-name]
  (doreq (http/post "https://apicn.faceplusplus.com/v2/person/create"
                    {:form-params (merge api-map
                                         {:person_name persone-name
                                          :face_id face-id
                                          :group_name gourp-name})})))

(defn get-person
  [person-id]
  (doreq (http/get "https://apicn.faceplusplus.com/v2/person/get_info"
                   {:query-params (merge api-map
                                         {:person_id person-id})})))

(defn detect
  [img]
  (doreq (http/post "http://apicn.faceplusplus.com/v2/detection/detect"
                    {:multipart [{:name "img" :content img}
                                 {:name "api_key" :content api-key}
                                 {:name "api_secret" :content api-secret}
                                 {:name  "attribute" :content "glass,pose,gender,age,race,smiling"}]})))

(deflogin person-get
  []
  (let [person-id (ns/get :user)
        _ (println ">>>>>>>>>>>>>>>>>>>>>>" person-id)
        body (get-person person-id)]
    (json/write-str body)))


(defn login [st & {:keys [age gender] :as msg}]
  (println ">>>>>>>>>>>>>>>>>>>>>>" msg)
  {:sessonToken st}
  {:age age :gender gender})

(defn create-user [path id st & {:keys [age gender] :or {age -1 gender "UNKNOW"}}]
  (insert! :yxt_user {:pic_path path
                      :person_id id
                      :session_token st
                      :age age
                      :gender gender}))

(defn- placeholder
  []
  (str "PENDING-" (System/currentTimeMillis) (rand-string)))

(defn up-pic-face [img pic-name]
  (let [body (detect img)]
    (cond (empty? (:face body)) (let [new-path (str "resources/public/no_face" pic-name)]
                                  (log/infof "no face pic %s" new-path)
                                  (.renameTo img (io/file new-path))
                                  {:error "There is no face in your photo"})
          (not (nil? (next (:face body)))) (let [new-path (str "resources/public/no_face" pic-name)]
                                             (log/infof "many face pic %s" new-path)
                                             (.renameTo img (io/file new-path))
                                             {:error "There is not only one face in your phot"})
          (= 1 (count (:face body))) (let [face (first (:face body))
                                           face-id (:face_id face)
                                           gender (-> face :attribute :gender :value)
                                           age (Long/valueOf (-> face :attribute :age :value))
                                           img-path (.getAbsolutePath img)
                                           update-person (fn [person-id session-token]
                                                           (update! :yxt_user {:person_id person-id} ["session_token = ?" session-token]))]
                                       (if (some (fn [x] (= x gender)) (map :group_name (:group (get-group-list)))) ;;按照性别分 Group
                                         (let [face-handle (face-identify img gender)
                                               candidate (-> face-handle
                                                             :face
                                                             first
                                                             :candidate)
                                               high (first candidate)]
                                           (if (and high (< 70 (:confidence high)))
                                             (do
                                               (yl/facelog pic-name (:person_name high) "老用户")
                                               (login (:person_name high)
                                                     :age age
                                                     :gender gender
                                                     :msg "老用户登录"))
                                             (let [session-token (rand-string 64)]
                                               (create-user img-path (placeholder) session-token :age age :gender gender)
                                               (future
                                                 (let [start (System/currentTimeMillis)
                                                       person-id (:person_id (create-person session-token face-id gender))]
                                                   (train-identify gender)
                                                   (update-person person-id session-token)  ;; person-name 用来存储 sessionToken
                                                   (log/infof "Create person %s cast time %s ms" person-id (- (System/currentTimeMillis) start))))
                                               (yl/facelog pic-name session-token "新用户" )
                                               (login session-token
                                                      :age age
                                                      :gender gender
                                                      :msg "创建新用户"))))
                                         (let [session-token (rand-string 64)]
                                           (create-user img-path (placeholder) session-token :age age :gender gender)
                                           (future (let [start (System/currentTimeMillis)
                                                         group-name (:group_name (create-group gender))
                                                         person-id (:person_id (create-person session-token face-id group-name))]
                                                     (train-identify group-name)
                                                     (update-person person-id session-token :age age :gender gender)
                                                     (log/infof "Create %s group and person %s cast time %s ms" group-name person-id (- (System/currentTimeMillis) start))))
                                           (yl/facelog pic-name session-token "新分组新用户")
                                           (login  session-token
                                                   :age age
                                                   :gender gender
                                                   :msg "创建新分组新用户"))))
          :default "if you get this page,you are beautiful.")))

(defhandler yxt [file]
  (if file
    (cond
      (map? file) (let [{:keys [size tempfile content-type filename]} file
                        new (own)
                        new-name (str new (case content-type
                                            "image/jpeg" ".jpg"
                                            "image/png" ".png"
                                            "image/bmp" ".bmp"
                                            ""))
                        full-name (str "resources/public/" new-name)]
                    (io/copy tempfile (io/file "resources" "public" new-name))
                    (if (.startsWith content-type "text")
                      (slurp full-name)
                      {:body (up-pic-face (io/file full-name) new-name)}))
      (string? file) (let [bimg (DatatypeConverter/parseBase64Binary file)
                           new (own)
                           new-name (str new ".png")
                           full-name (str "resources/public/" new-name)]
                       (with-open [o (io/output-stream full-name)]
                         (.write o bimg))
                       {:body (up-pic-face (io/file full-name) new-name)})
      :default {:body {:error "caonima"}})
    {:body {:error "file is required"}}))
