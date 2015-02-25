(ns cljsfiddle.db.util
  (:require [clojure.string :as s]
            [clojure.java.io :as io]
            [clojure.tools.reader :as reader]
            [datomic.api :as d]
            [cljs.analyzer :as ana]
            [cljs.env :as cljs-env]
            [cljs.closure :as closure]
            [cljs.js-deps :as cljs-deps]
            [cljsfiddle.compiler :refer [compiler-env]]
            [cljs.tagged-literals :as tags]
            [taoensso.timbre :as log]
            [environ.core :refer (env)])
  (:import [clojure.lang LineNumberingPushbackReader]
           [java.io StringReader BufferedReader]
           [org.apache.commons.codec.digest DigestUtils]))

(def tempid? map?)

(defn- read-all* [^LineNumberingPushbackReader reader result eof]
  (let [form (reader/read reader false eof)]
    (if (= form eof)
      result
      (recur reader (conj result form) eof))))

(defn read-all [src]
  (binding [reader/*read-eval* false
            reader/*data-readers* tags/*cljs-data-readers*]
    (read-all* (LineNumberingPushbackReader. (StringReader. src))
               []
               (Object.))))

(defn sha [s]
  (DigestUtils/shaHex s))

(defn parse-js-ns [js-src]
  (-> js-src
      StringReader.
      BufferedReader.
      line-seq
      cljs-deps/parse-js-ns))

(comment
  (defn log-time [prev txt]
  (let [cur (System/nanoTime)
        diff (- cur prev)
        ms (-> diff
               (/ 1000000) int)]
    (log/trace (format "%dms | %s" ms txt))
    cur)))

(defn cljs-object-from-src [cljs-src-str]
  (let [parsed-ns (ana/parse-ns
                   (-> cljs-src-str
                       StringReader.
                       BufferedReader.))
        cljs-src (binding [*ns* (-> parsed-ns :ns)]
                   (read-all cljs-src-str))

        [deps-src js-src]
        (cljs-env/with-compiler-env compiler-env
          (let [opts {}

                compiled (closure/-compile cljs-src opts)
                js-sources (closure/add-dependencies opts compiled)
                fdeps-str  (closure/foreign-deps-str
                            opts
                            (filter closure/foreign-source? js-sources))]

            [fdeps-str compiled]))
        {:keys [provides requires]} (parse-js-ns js-src)]
    {:src cljs-src-str
     :js-src js-src
     :deps-src deps-src
     :sha (sha cljs-src-str)
     :ns (first provides)
     :requires (set requires)}))

(defn cljs-object-from-file [cljs-file]
  (let [cljs-src-str (slurp (io/resource cljs-file))
        cljs-object (cljs-object-from-src cljs-src-str)]
    (assoc cljs-object
      :file cljs-file)))

(defn js-object-from-file [js-file]
  (let [js-src-str (slurp (io/resource js-file))
        {:keys [provides requires]} (parse-js-ns js-src-str)]
    {:file js-file
     :src js-src-str
     :sha (sha js-src-str)
     :provides provides
     :requires requires}))

(defn css-object-from-src [css-src]
  {:src css-src
   :sha (sha css-src)})

(defn html-object-from-src [html-src]
  {:src html-src
   :sha (sha html-src)})

(defn fiddle [cljs html css]
  {:cljs (cljs-object-from-src cljs)
   :html (html-object-from-src html)
   :css  (css-object-from-src css)})
