(ns cljsfiddle.compiler
  (:require [cljs.closure :as closure]
            [cljs.js-deps :as deps]
            [cljs.env :as cljs-env]
            [cljs.compiler :as compiler]))


(let [ups-deps (closure/get-upstream-deps)
      opts {:ups-libs (:libs ups-deps)
            :ups-foreign-libs (:foreign-libs ups-deps)
            :ups-externs (:externs ups-deps)}

     env (cljs-env/default-compiler-env opts)]

    (swap! env assoc :js-dependency-index (deps/js-dependency-index opts))

   (def compiler-env env))
