(ns cljsfiddle.handler
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as s]
            [clojure.instant :as inst]
            [clojure.core.match :refer (match)]
            [cljsfiddle.views :as views]
            [cljsfiddle.closure :as closure]
            [cljsfiddle.db :as db]
            [cljsfiddle.db.util :as util]
            [datomic.api :as d]
            [aleph.http :as http]
            [byte-streams :as bs]
            [ring.util.response :as res]
            [ring.middleware.session.cookie :as cookie]
            [ring.middleware.format :refer [wrap-restful-format]]
            [compojure.core :refer :all]
            [ring.middleware.defaults :refer :all]
            [compojure.route :as route]
            [environ.core :refer (env)]
            [cheshire.core :as json]
            [hiccup.page :refer [html5]]
            [taoensso.timbre :as log]))

(assert (env :datomic-uri) "DATOMIC_URI environment variable not set")
(assert (env :session-secret) "SESSION_SECRET environment variable not set")
(assert (env :cljsfiddle-version) "CLJSFIDDLE_VERSION environment variable not set")
(assert (env :github-client-id) "GITHUB_CLIENT_ID environment variable not set")
(assert (env :github-client-secret) "GITHUB_CLIENT_SECRET environment variable not set")


(defn edn-response [edn-data]
  {:status 200
   :headers {"Content-Type" "application/edn"}
   :body (pr-str edn-data)})

;; From technomancy/syme
(defn get-token [code]
  (try
    (-> @(http/post "https://github.com/login/oauth/access_token"
                 {:form-params {:client_id (env :github-client-id)
                                :client_secret (env :github-client-secret)
                                :code code}
                  :headers {"User-Agent" "aleph"}
                  :accept :json})
      :body
      bs/to-string
      (json/decode true)
      :access_token)
  (catch Exception e
    (log/info (-> (ex-data e)
                  :body bs/to-string))
    (throw e))))

(defn get-username [token]
  (try
    (-> @(http/get "https://api.github.com/user"
                  {:query-params {:access_token token}
                   :headers {"User-Agent" "aleph"}
                   :accept :json})
        :body
        bs/to-string
        (json/decode true)
        :login)
  (catch Exception e
    (log/info (-> (ex-data e)
                  :body bs/to-string))
    (throw e))))

(defn parse-ns-form [src]
  (try
    (let [form (edn/read-string src)]
      (if (and (seq? form)
               (= 'ns (first form))
               (symbol? (second form)))
        [:ok (name (second form))]
        [:fail (str "Not a ns form: " (pr-str form))]))
    (catch Exception e
      [:exception (.getMessage e)])))

(defn as-of*
  "Get the db as of date or latest db if date is nil"
  [conn date]
  (let [db (d/db conn)]
    (if date
      (d/as-of db date)
      db)))

(defn app-routes [conn]
  (routes
   (GET "/"
     {{:keys [username] :as session} :session}
     (assoc {:headers {"Content-Type" "text/html"}
             :status 200
             :body (html5 (views/main-view (d/entity (d/db conn)
                                                     :cljsfiddle/default-fiddle)
                                           username))}
       :session (dissoc session :fiddle)))

   (GET "/fiddle/:ns"
     {{:keys [username] :as session} :session
      {:keys [ns as-of]} :params}
     (let [date (try (inst/read-instant-date as-of)
                     (catch Exception e))
           db (as-of* conn date)]
       (when-let [fiddle (db/find-fiddle-by-ns db ns)]
         (assoc {:headers {"Content-Type" "text/html"}
                 :status 200
                 :body (html5 (views/main-view fiddle username))}
           :session (merge session
                           {:fiddle ns})))))

   (GET "/view/:ns"
     {{:keys [ns as-of]} :params}
     (let [date (try (inst/read-instant-date as-of)
                  (catch Exception e))
           db (as-of* conn date)]
      (when-let [fiddle (db/find-fiddle-by-ns db ns)]
        (let [deps (db/dependency-files db ns)]
         {:headers {"Content-Type" "text/html"}
          :status 200
          :body (html5 (views/html-view ns fiddle deps))}))))

   (GET "/about"
     {{:keys [username]} :session}
     {:headers {"Content-Type" "text/html"}
      :status 200
      :body (html5 (views/about-view username))})

   (GET "/user/:user"
     {{:keys [user]} :params
      {:keys [username]} :session}
     (when true #_(= username user)
       (let [fiddles (db/fiddles-by-user (d/db conn) user)]
        {:headers {"Content-Type" "text/html"}
         :status 200
         :body (html5 (views/user-view username user fiddles))})))

   (POST "/save"
     {fiddle :params
      {:keys [username]} :session}
     (edn-response
      (match [username (parse-ns-form (:cljs fiddle))]
        [nil _] {:status :save-fail :msg "Login to save your work."}
        [_ [:fail msg]] {:status :save-fail :msg msg}
        [_ [:exception msg]] {:status :exception :msg msg}
        [_ [:ok ns]] (if (or (= username (first (s/split ns #"\.")))
                             (and (= ns "cljsfiddle")
                                  (= username "jonase")))
                       (try
                         (let [db (db/save-fiddle conn
                                                  (util/fiddle (:cljs fiddle)
                                                               (:html fiddle)
                                                               (:css  fiddle)))]
                           {:status :save-success
                            :date (subs (->> db d/basis-t d/t->tx (d/entity db) :db/txInstant pr-str)
                                        7 36)
                            :ns ns})
                         (catch Exception e
                           {:status :exception
                             :msg (.getMessage e)}))
                       {:status :save-fail
                        :ns ns
                        :user username}))))

   ;; TODO: this can be done with nginx try_files
   (GET "/jscache/:version/:file"
     [version file]
     (let [fp (str "/jscache/" version "/" file)
           fr (res/file-response fp {:root "resources"})]
       (if fr
         (-> fr
             (res/header "Cache-Control" (str "max-age=" (* 60 60 24 365)))
             (res/header "Content-Type" "application/javascript"))
         (do (log/info "Cache miss:" fp)
             (res/redirect (str "/deps/" version "/" file))))))

   (GET "/oauth_login"
     {{:keys [code]} :params session :session}
     (if code
       (let [token (get-token code)
             username (get-username token)]
         (assoc (res/redirect (if-let [fiddle (:fiddle session)]
                                (str "/fiddle/" fiddle)
                                "/"))
           :session (merge session {:token token
                                    :username username})))))


   (GET "/logout"
     []
     (assoc (res/redirect "/") :session nil))

   (context "/compiler" [] (closure/compile-routes conn))
   (context "/deps" [] (closure/deps-routes conn))

   (route/resources "/")
   (route/not-found "Not Found")))

(defn get-handler []
  (let [store (cookie/cookie-store {:key (env :session-secret)})
        db-uri (env :datomic-uri)
        conn (d/connect db-uri)]
    (-> (app-routes conn)
        (wrap-restful-format :formats [:transit-json :edn])
        (wrap-defaults
         (-> site-defaults
             (assoc-in [:session :store] store)
             (assoc-in [:security :anti-forgery] false))) ; temp
        )))

;; (.stop server)
;; (def server (-main))


