(ns cljsfiddle.compiler
  (:require [cljs.closure :as closure]
            [cljs.js-deps :as deps]
            [cljs.env :as cljs-env]
            [cemerick.pomegranate.aether :as ae]
            [cemerick.pomegranate :as pomegranate]
            [dynapath.util :as dp]
            [clojure.java.classpath :as cp]
            [clojure.java.io :as io]
            [clojure.string :as s]
            [cljs.compiler :as compiler])
  (:import [java.net URLClassLoader URL]))

;; http://stackoverflow.com/questions/252893/how-do-you-change-the-classpath-within-java
;; http://blog.japila.pl/2011/01/dynamically-redefining-classpath-in-clojure-repl/
;; https://github.com/cemerick/pomegranate/blob/master/src/main/clojure/cemerick/pomegranate.clj

(defn get-compiler-env
  [classloader]
  (let [ups-deps (closure/get-upstream-deps classloader)
      opts {:ups-libs (:libs ups-deps)
            :ups-foreign-libs (:foreign-libs ups-deps)
            :ups-externs (:externs ups-deps)}

     env (cljs-env/default-compiler-env opts)]

    (swap! env assoc :js-dependency-index (deps/js-dependency-index opts))
    env))

(defn current-classloader
  []
  (-> (Thread/currentThread)
    (.getContextClassLoader)))

(defn set-current-classloader
  [cl]
  (-> (Thread/currentThread)
      (.setContextClassLoader cl)))


;; (def ll (URLClassLoader. (into-array URL [])))
;; (def cc (current-classloader))
;; (set-current-classloader ll)
;; (require '[clojure.java.io])

(defn make-classloader
  [dependencies]
  (let [deps (ae/resolve-dependencies
              ;:local-repo "/tmp/repo"
              :repositories {"clojars" "http://clojars.org/repo/"}
              ;:coordinates '[[gigaword "1.1.0"]]
              :coordinates dependencies
              )
        deps-files (ae/dependency-files deps)
        urls (for [file deps-files]
               (-> file io/file
                   (.toURI)
                   (.toURL)))
        classloader (URLClassLoader. (into-array URL urls))]
    classloader))




;(mapcat cljs-deps/jar-entry-names* nfiles)

;(spit "/tmp/deps" (format "%s\n" f) :append true))

;; (lcp/resolve-dependencies
;;  :dependencies
;;  {:repositories [ ["clojars" "http://clojars.org/repo/"] ]
;;   :dependencies
;;   [['ronda/schema "0.1.0-RC2"]]})


;(pomegranate/add-dependencies ''add to default cp too
;; (def t (ae/resolve-dependencies
;;  :local-repo "/tmp/repo"
;;  :repositories {"clojars" "http://clojars.org/repo/"}
;;  :coordinates '[[gigaword "1.1.0"]]
;;  ))

;; (for [file (ae/dependency-files t)]
;;   (-> file io/file
;;       (.toURI)
;;       (.toURL)
;;   ))

; (filter #(re-matches #".*?giga.*?" %) (map str (cp/classpath)))


;; (require 'gigaword.core)
