(defproject cljsfiddle "0.1.0-SNAPSHOT"
  :description "CLJSFiddle"
  :url "http://cljsfiddle.net"
  :dependencies [[org.clojure/clojure "1.7.0-alpha5"]
                 [org.clojure/clojurescript "0.0-2913"]
                 [org.clojure/tools.reader "0.8.15"]
                 [org.clojure/core.match "0.2.2"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [org.clojure/core.logic "0.8.9"]
                 [org.clojure/tools.macro "0.1.5"]
                 [com.cemerick/pomegranate "0.3.0"]
                 [org.tcrawley/dynapath "0.2.3"]
                 [com.datomic/datomic-free "0.9.5130"]
                 [javax.servlet/servlet-api "2.5"]
                 [ring/ring-devel "1.3.2"]
                 [ring/ring-defaults "0.1.4"]
                 [aleph "0.4.0-beta3"]
                 [commons-codec "1.10"]
                 [me.raynes/fs "1.4.6"]
                 [compojure "1.3.2"]
                 [cheshire "5.4.0"]
                 [hiccup "1.0.5"]
                 [environ "1.0.0"]
                 [com.taoensso/timbre "3.4.0"]
                 [hylla "0.2.0"]
                 [domina "1.0.3"]
                 [prismatic/dommy "1.0.0"]
                 [org.omcljs/om "0.8.8"]
                 [reagent "0.5.0-alpha3"]
                 [quiescent "0.1.4"]
                 [hiccups "0.3.0"]
                 [cljs-http "0.1.26"]
                 [ring-middleware-format "0.4.0"]
                 [rum "0.2.5"]
                 [datascript "0.9.0"]
]
  :source-paths ["src/clj" "src/cljs"]
  :plugins [[lein-cljsbuild "1.0.5"]]
  :aot [cljsfiddle.main]
  :main cljsfiddle.main
;  :uberjar-name "cljsfiddle-standalone.jar"
  :min-lein-version "2.0.0"
  :profiles {:dev {:dependencies [[ring-mock "0.1.5"]]}}
  :jvm-opts ["-XX:+UseG1GC"]
  :cljsbuild {:builds {:dev {
                        :source-paths ["src/cljs"]
                        :compiler {:output-to "resources/public/js/app.js"
                                   :output-dir "resources/public/js/out-dev"
                                   :source-map "resources/public/js/app.js.map"
                                   :optimizations :simple
                                   :pretty-print true}}
                       :prod {
                        :source-paths ["src/cljs"]
                        :compiler {:output-to "resources/public/js/app.js"
                                   :output-dir "resources/public/js/out"
                                   :optimizations :advanced
                                   :source-map "resources/public/js/app.js.map"
                                   :pretty-print false
                                   :elide-asserts true
                                   :static-fns true
                                   :externs ["externs.js"]}}}}
  :aliases {"db-create" ["trampoline" "run" "-m" "cljsfiddle.import/create-db"
                          "datomic:free://localhost:4334/cljsfiddle"]
            "db-assets" ["trampoline" "run" "-m" "cljsfiddle.import"
                          "datomic:free://localhost:4334/cljsfiddle"]})
