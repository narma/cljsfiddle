(ns cljsfiddle.core
  (:require-macros [hiccups.core :as hiccups])
  (:require [clojure.string :as s]
            [cljs.reader :as reader]
            [domina :as dom]
            [domina.css :as css]
            [domina.events :as events]
            [hylla.remote :as remote]
            [ajax.core :as http]
            [hiccups.runtime :refer (render-html)]))

(defn ends-with? [string suffix]
  (not= -1 (.indexOf string suffix (- (.-length string) (.-length suffix)))))

(defn code-mirror [id opts]
  (.fromTextArea js/CodeMirror (dom/by-id id) (clj->js opts)))

(defn make-deps [deps version]
  (let [html [[:script "CLOSURE_NO_DEPS=true;"]
              [:script "COMPILED=true;"]]
        ds (for [dep deps]
             [:script {:src (str "/jscache/" version "/" (s/replace dep ".cljs" ".js"))}])]
    (apply str (map render-html (concat html ds)))))

(defn make-srcdoc [html css js deps-src deps version]
  (hiccups/html
   [:html
    [:head
     [:style css]]
    [:body
     [:script "window.onerror = function(msg, url, line) { parent.postMessage('{:type :runtime-error}', '*'); return false;};"]
     html
     [:script (or deps-src "")]
     (make-deps deps version)
     [:script "cljs.core.set_print_fn_BANG_.call(null,function(s){var s = s.replace(/\"/g, \"&quot;\"); parent.postMessage('{:type :runtime-print :to-print \"' + s + '\"}', '*');});"]
     [:script js]
     ]]))

(def saved? (atom false))

(defn toggle-saved! []
  (let [btn (css/sel "#save-btn")
        span (css/sel "#save-btn span")]
    (dom/set-style! btn :background-color "lightgreen")
    (dom/remove-class! span "glyphicon-floppy-save")
    (dom/add-class! span "glyphicon-floppy-saved")))

(defn toggle-not-saved! []
  (let [btn (css/sel "#save-btn")
        span (css/sel "#save-btn span")]
    (dom/set-style! btn :background-color "white")
    (dom/remove-class! span "glyphicon-floppy-saved")
    (dom/add-class! span "glyphicon-floppy-save")))

(defn editor-content-changed [e]
  (when @saved?
    (toggle-not-saved!)
    (swap! saved? not)))

(defn register-change-listeners [& editors]
  (doseq [editor editors]
    (.on editor "change" editor-content-changed)))

(defmulti output-hiccup #(or (:type %) (:status %)))

(defmethod output-hiccup :error [msg]
  [:div
   [:span.glyphicon.glyphicon-warning-sign {:style "color:red"}] " " [:strong (:msg msg)]])

(defmethod output-hiccup :log [msg]
  [:div
   [:span.glyphicon.glyphicon-chevron-right] " " (:msg msg)])

(defmethod output-hiccup :exception [msg]
  (output-hiccup (assoc msg :type :error)))

(defmethod output-hiccup :save-fail [msg]
  (if-let [msg (:msg msg)]
    [:div
     [:span.glyphicon.glyphicon-warning-sign {:style "color:red"}] " "
     [:strong msg]]
    [:div
     [:span.glyphicon.glyphicon-warning-sign {:style "color:red"}]
     " Can't save fiddle with namespace " [:strong (:ns msg)]
     ". Prefix the namespace with your username such as " [:strong (:user msg) "." (:ns msg)] "."]))

(defmethod output-hiccup :save-success [data]
  [:div
   [:span.glyphicon.glyphicon-floppy-saved {:style "color: green;"}]
   " Fiddle " [:strong (:ns data)] " saved successfully!"])

(defmethod output-hiccup :runtime-error [data]
  [:div
   [:span.glyphicon.glyphicon-warning-sign {:style "color: red;"}]
   [:strong " Runtime exception occured. Check your console logs for details."]])

(defmethod output-hiccup :runtime-print [data]
  [:div
   [:span.glyphicon.glyphicon-chevron-right]
   " " (s/escape (:to-print data) {"<" "&lt;"
                                   ">" "&gt;"})])

(defn output-html [msg]
  (hiccups/html (output-hiccup msg)))

(defn output-fn []
  (let [out (dom/by-id "output")]
   (fn [msg]
     (let [html (output-html msg)]
       (dom/append! out html)
       ;; Scroll to bottom
       (set! (.-scrollTop out) (.-scrollHeight out))))))


(defn ^:export init
  [version]

  (let [html-editor (code-mirror "html-editor" {:lineNumbers true})
        css-editor (code-mirror "css-editor" {:mode :css :lineNumbers true})
        cljs-editor (code-mirror "cljs-editor" {:mode :clojure
                                                :lineNumbers true
                                                :autoCloseBrackets true
                                                :matchBrackets true})
        result-frame (domina/by-id "result-frame")
        run-btn (domina/by-id "run-btn")
        save-btn (domina/by-id "save-btn")
        clear-output-btn (domina/by-id "clear-output-btn")
        resize-handle (domina/by-id "resize-handle")
        tab-container (domina/by-id "tab-container")
        output (output-fn)
        set-editor-heights (fn [height]
                             (doseq [editor [cljs-editor html-editor css-editor]]
                               (.setSize editor "100%" (str height "px"))))]

    (set-editor-heights 400)

    (events/listen! resize-handle :mousedown
                    (fn [e]
                      (let [start-y (.-clientY (.-evt e))
                            cur-height (js/parseInt (.-height (.-style (.getWrapperElement cljs-editor))))
                            move-handler (fn [e]
                                           (let [cur-y (.-clientY (.-evt e))
                                                dy (- cur-y start-y)]
                                             (set-editor-heights (+ cur-height dy))))]
                        (events/listen! tab-container :mousemove move-handler)
                        (events/listen! tab-container :mouseup
                                        (fn [e]
                                          (move-handler e)
                                          (events/unlisten! tab-container :mousemove)
                                          (events/unlisten! tab-container :mouseup))))))

    (events/listen! clear-output-btn :click
                    (fn [e]
                      (dom/set-html! (domina/by-id "output") "")))

    (events/listen! run-btn :click
                    (fn [e]
                      (dom/add-class! run-btn "disabled")
                      (http/POST "/compiler/compile"
                        {:params {:src (.getValue cljs-editor)}
                         :format :edn
                         :handler (fn [res]
                                    (dom/remove-class! run-btn "disabled")
                                    (condp = (:status res)
                                      :ok
                                      (let [srcdoc (make-srcdoc (.getValue html-editor)
                                                                (.getValue css-editor)
                                                                (:js-src res)
                                                                (:deps-src res)
                                                                (:dependencies res)
                                                                version)]
                                        (.setAttribute result-frame "srcdoc" srcdoc))
                                      :exception (output res)))})))
    (events/listen! save-btn :click
                    (fn [e]
                      (dom/add-class! save-btn "disabled")
                      (http/POST "/save"
                        {:params {:cljs (.getValue cljs-editor)
                                  :html (.getValue html-editor)
                                  :css (.getValue css-editor)}
                         :format :edn
                         :handler (fn [res]
                                    (dom/remove-class! save-btn "disabled")
                                    (if (= (:status res) :success)
                                      (do (reset! saved? true)
                                          (toggle-saved!)
                                          (output (assoc res :msg "Fiddle saved successfully!")))
                                      (output res)))})))

    (.on (js/$ "a[data-toggle=\"tab\"]")
         "shown.bs.tab"
         (fn [evt]
           (condp = (dom/attr (.-target evt) :href)
             "#cljs-editor-tab" (.refresh cljs-editor)
             "#html-editor-tab" (.refresh html-editor)
             "#css-editor-tab" (.refresh css-editor))))

    (register-change-listeners cljs-editor html-editor css-editor)

    (.addEventListener js/window "message"
                       (fn [evt]
                         (.log js/console evt)
                         (output (js->clj (reader/read-string (.-data evt))))))))
