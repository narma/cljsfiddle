(ns cljsfiddle.closure
  (:require [clojure.java.io :as io]
            [clojure.string :as s]
            [clojure.set :as set]
            [clojure.edn :as edn]
            [cljsfiddle.db :as db]
            [cljsfiddle.db.util :refer [cljs-object-from-src read-all]]
            [cljsfiddle.db.src :as src]
            [cljs.closure :as cljs]
            [cljs.js-deps :as cljs-deps]
            [cljsfiddle.compiler :as cl]
            [cljs.env :as cljs-env]
            [taoensso.timbre :as log]
            [clojure.pprint :refer [pprint]]
            [datomic.api :as d]
            [compojure.core :refer :all]
            [cljsfiddle.import :refer (import-libs)]
            [environ.core :refer (env)])
  (:import [clojure.lang LineNumberingPushbackReader]
           [java.util.logging Level]
           [java.io StringReader BufferedReader]
           [com.google.javascript.jscomp.Compiler]
           [com.google.javascript.jscomp SourceFile
                                         CompilerOptions
                                         CompilationLevel
                                         ClosureCodingConvention]))

;; 1 day(s)
(def max-age (str "max-age=" (* 60 60 24 1)))

(defn edn-response [edn-data]
  {:status 200
   :headers {"Content-Type" "application/edn"}
   :body (pr-str edn-data)})

(defn compile-cljs* [cljs-src-str]
  (:js-src (cljs-object-from-src cljs-src-str)))

(defn js-errors [error]
  {:description (.description error)
   :file (.sourceName error)
   :line (.lineNumber error)})

;; TODO opts & warnings
(defn make-compiler [opts]
  (fn mk-compiler
    ([src] (mk-compiler "__NO_SOURCE_FILE__" src))
    ([name src]
       (let [options (let [level CompilationLevel/WHITESPACE_ONLY
                                  compiler-options (CompilerOptions.)]
                              (.setCodingConvention compiler-options (ClosureCodingConvention.))
                              (.setOptionsForCompilationLevel level compiler-options)
                              compiler-options)
             compiler (com.google.javascript.jscomp.Compiler.)
             src (SourceFile/fromCode name src)
             externs (SourceFile/fromCode "externs" "")
             result (.compile compiler externs src options)]
         (if (.success result)
           (merge {:status :success
                   :file name
                   :js-src (.toSource compiler)}
                  (when-let [warnings (seq (.warnings result))]
                    {:warnings (mapv js-errors warnings)}))
           (merge {:status :error
                   :file name
                   :errors (mapv js-errors (.errors result))}
                  (when-let [warnings (seq (.warnings result))]
                    {:warnings (mapv js-errors warnings)})))))))

(def closure-compile (make-compiler {:optimizations :whitespace}))

(defn find-files [paths jars]
  (filter (fn [file]
            (and ;(some #(.contains file %) paths)
                 (some #(.endsWith file %) [".js" ".cljs"])))
          (mapcat cljs-deps/jar-entry-names* jars)))

(defn transact-deps
  [conn classloader deps]
  ;; todo, skip already transacted deps
  (let [deps-uniq (apply hash-set (map #(-> % first str) deps))
        jar-files (for [f (map #(.getFile %) (vec (.getURLs classloader)))
                        :when (some #(.contains f %) deps-uniq)]
                    f)
        files (find-files deps-uniq jar-files)]
    (import-libs conn files {:classloader classloader
                             :compile? true
                             :closure-compile closure-compile})))

(defn compile-routes [conn]
  (routes
   (POST "/compile"
     {{cljs-src-str :src lib-deps :deps} :params}
     (try
       (let [deps-list (read-all lib-deps)
             default-cl (cl/current-classloader)
             compiler-cl (cl/make-classloader deps-list)
             _ (cl/set-current-classloader compiler-cl)
             _ (transact-deps conn compiler-cl deps-list)
             db (d/db conn)
             cljs-obj (cljs-object-from-src cljs-src-str {:classloader compiler-cl})
             cljs-tx (src/cljs-tx db cljs-obj)
             tdb (:db-after (d/with db (:tx cljs-tx)))
             deps (db/dependency-files tdb (:ns cljs-obj))
             js-src-obj (closure-compile (:js-src cljs-obj))
             js-src-obj (assoc js-src-obj
                          :dependencies deps
                          :deps-src (:deps-src cljs-obj)
                          :status :ok)]
         (cl/set-current-classloader default-cl)
         (edn-response js-src-obj))
;;        (catch clojure.lang.ExceptionInfo e
;;          (edn-response
;;           {:status :exception
;;            :msg (.getMessage e)}))
;;        (catch Exception e
;;          (log/error (.getMessage e))
;;          (edn-response
;;           {:status :exception
;;            :msg "Something went terribly wrong."}))
       ))))

;(compile-cljs* " \n\n(defn adsd [x y] (+ x y))")

(defn deps-routes [conn]
  (routes
   (GET "/:version/:file"
    [version file]
    (let [sha (first (s/split file #"\."))
          [type src] (first
                      (d/q '[:find ?typename ?text
                             :in $ ?sha
                             :where
                             [?blob :cljsfiddle.blob/sha ?sha]
                             [?blob :cljsfiddle.blob/text ?text]
                             [?src  :cljsfiddle.src/blob ?blob]
                             [?src :cljsfiddle.src/type ?type]
                             [?type :db/ident ?typename]]
                           (d/db conn) sha))]
      (when (and type src)
        (let [csrc (condp = type
                     :cljsfiddle.src.type/cljs (:js-src (closure-compile (compile-cljs* src)))
                     :cljsfiddle.src.type/js (:js-src (closure-compile src)))]
          (spit (str "resources/jscache/" version "/" file) csrc)
          {:status 200
           :headers {"Content-Type" "application/javascript"}
           :body csrc}))))))

(comment
  (def conn (-> :datomic-uri
                env
                d/connect))

  (def db (-> :datomic-uri
              env
              d/connect
              d/db))

  (d/q '[:find (sample 2 ?sha)
         :where
         [?src :cljsfiddle.src/type :cljsfiddle.src.type/cljs]
         [?src :cljsfiddle.src/blob ?blob]
         [?blob :cljsfiddle.blob/sha ?sha]]
       db)

  (defn fffirst [coll]
    (first (ffirst coll)))


  (closure-compile
   (fffirst (d/q '[:find (sample 1 ?src-txt)
                  :where
                  [?src :cljsfiddle.src/type :cljsfiddle.src.type/js]
                  [?src :cljsfiddle.src/blob ?blob]
                  [?blob :cljsfiddle.blob/text ?src-txt]]
                db)))

  (use 'clojure.pprint)
  (pprint
   (src/cljs-tx
    db
    (cljs-object-from-src "(+ 1 2 3)")))

  (d/touch (d/entity db 17592186045492))

  (first (seq (d/datoms db :avet :cljsfiddle.src/ns nil)))


  (d/q '[:find ?typename ?text
         :in $ ?sha
         :where
         [?blob :cljsfiddle.blob/sha ?sha]
         [?blob :cljsfiddle.blob/text ?text]
         [?src  :cljsfiddle.src/blob ?blob]
         [?src :cljsfiddle.src/type ?type]
         [?type :db/ident ?typename]]
       db "5773e8ca4100bda76ebcb213f28e83bd7b4d14ee")

  )


