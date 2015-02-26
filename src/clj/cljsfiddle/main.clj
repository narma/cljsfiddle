(ns cljsfiddle.main
  (require [cljsfiddle.handler :refer (get-handler)]
           [ring.middleware.stacktrace :refer (wrap-stacktrace)]
           [ring.middleware.reload :refer (wrap-reload)]
           [environ.core :refer (env)]
           [aleph.http :as http]
           [clojure.java.classpath :as cp]
           [ring.adapter.jetty :refer (run-jetty)]
           )
  (:gen-class))

(defn ensure-jscache-dir []
  (let [p (str "resources/jscache/" (:cljsfiddle-version env) "/")
        f (java.io.File. p)]
    (when (.mkdirs f)
      (println "Created" p))))


(defn -main []
  (ensure-jscache-dir)
  (def app (get-handler))
  (let [port (Integer/parseInt (or (env "PORT") "8080"))
        handler (if (:reload env)
                  (-> (var app)
                      (wrap-reload
                       {:dirs (map str (cp/classpath-directories))}))
                  app)]
    (http/start-server handler {:port port})
    (println "Server ready at port " port)))
