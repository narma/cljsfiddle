(ns cljsfiddle.views
  (:require [hiccup.util :refer (escape-html)]
            [environ.core :refer (env)]
            [cljsfiddle.db.util :refer [cljs-object-from-src]]))

(def google-analytics-script
  "(function(i,s,o,g,r,a,m){i['GoogleAnalyticsObject']=r;i[r]=i[r]||function(){
  (i[r].q=i[r].q||[]).push(arguments)},i[r].l=1*new Date();a=s.createElement(o),
  m=s.getElementsByTagName(o)[0];a.async=1;a.src=g;m.parentNode.insertBefore(a,m)
  })(window,document,'script','//www.google-analytics.com/analytics.js','ga');

  ga('create', 'UA-9233187-2', 'cljsfiddle.net');
  ga('send', 'pageview');")

(def google-analytics-script "") ;; tmp: delete me

(defn base [nav & content]
  [:html {:lang "en"}
   [:head
    [:title "CLJSFiddle"]
    [:meta {:name "viewport"
            :content "width=device-width, initial-scale=1.0"}]
    [:link {:rel "stylesheet"
            :href "//netdna.bootstrapcdn.com/bootstrap/3.0.0/css/bootstrap.min.css"}]
    [:link {:rel "stylesheet"
            :href "/css/codemirror.css"}]
    [:link {:rel "stylesheet"
            :href "/css/style.css"}]
    [:script google-analytics-script]]
   [:body
    nav
    [:div.full-width-container content]
    [:script {:src "//ajax.googleapis.com/ajax/libs/jquery/1.10.2/jquery.min.js"}]
    [:script {:src "//netdna.bootstrapcdn.com/bootstrap/3.0.0/js/bootstrap.min.js"}]
    [:script {:src "/js/codemirror-compressed.js"}]
    [:script {:src "/js/app.js"}]
    [:script "cljsfiddle.core.init('" (env :cljsfiddle-version) "');"]]])

(def ^:private github-login-url (str "https://github.com/login/oauth/authorize?client_id=" (env :github-client-id)))


(defn navbar [user & buttons]
  [:nav.navbar.navbar-default.navbar-static-top {:role "navigation"}
   [:div.navbar-header
    [:a.navbar-brand {:href "/"} "CLJSFiddle"]]
   [:ul.nav.navbar-nav
    buttons
    (when user [:li [:a {:href (str "/user/" user)} "My namespaces"]])
    [:li [:a {:href "/about"} "About"]]]
   [:ul.nav.navbar-nav.navbar-right
    [:li (if user
           [:a {:href "/logout"} "Logout (" user ")"]
           [:a {:href github-login-url} "Login"])]]])

(defn main-view
  [fiddle user]
  (base (navbar user)
        [:div.row
         [:div.col-lg-12
          [:div#alert]]]
        [:div.row
         [:div#tab-container.col-lg-6

          [:ul#editor-tabs.nav.nav-tabs
           [:li [:a {:href "#deps-editor-tab" :data-toggle "tab"} "deps"]]
           [:li.active [:a {:href "#cljs-editor-tab" :data-toggle "tab"} "cljs"]]
           [:li [:a {:href "#html-editor-tab" :data-toggle "tab"} "html"]]
           [:li [:a {:href "#css-editor-tab" :data-toggle "tab"} "css"]]
           [:span#cljsfiddle-buttons
            [:button#run-btn.btn.btn-default.btn-xs {:data-toggle "tooltip"
                                                     :title "Compile & Run"}
             [:span.glyphicon.glyphicon-play]]
            [:button#save-btn.btn.btn-default.btn-xs {:data-toggle "tooltip"
                                                      :title "Save"}
             [:span.glyphicon.glyphicon-floppy-save]]]]

          [:div.tab-content
           [:div#deps-editor-tab.tab-pane
            [:textarea#deps-editor.tab-pane (escape-html (-> fiddle
                                                                    :cljsfiddle/deps
                                                                    :cljsfiddle.src/blob
                                                                    :cljsfiddle.blob/text))]]
           [:div#cljs-editor-tab.tab-pane.active
            [:textarea#cljs-editor.tab-pane.active (escape-html (-> fiddle
                                                                    :cljsfiddle/cljs
                                                                    :cljsfiddle.src/blob
                                                                    :cljsfiddle.blob/text))]]
           [:div#html-editor-tab.tab-pane
            [:textarea#html-editor.tab-pane (escape-html (-> fiddle
                                                             :cljsfiddle/html
                                                             :cljsfiddle.src/blob
                                                             :cljsfiddle.blob/text))]]
           [:div#css-editor-tab.tab-pane
            [:textarea#css-editor.tab-pane (escape-html (-> fiddle
                                                            :cljsfiddle/css
                                                            :cljsfiddle.src/blob
                                                            :cljsfiddle.blob/text))]]]

          [:div#outut-container {:style "position: relative;"}
           [:div#resize-handle {:style "height:3px; background: #ccc; cursor: ns-resize;"}]
           [:div#output {:style "height:100px;width:100%;border:1px solid lightgray;overflow:auto;padding-left:6px;padding-top:6px"}]
           [:button#clear-output-btn.btn.btn-default.btn-xs {:style "position: absolute; right: 5px; bottom: 5px;"
                                                             :title "clear output"
                                                             :data-toggle "tooltip"} "clear"]]]

         [:div.col-lg-6
          [:div.row
           [:div.col-lg-12
            [:iframe#result-frame {:seamless "seamless"
                                   :sandbox "allow-scripts"
                                   :width "100%"
                                   :style "border: 1px solid lightgray;height:532px;"}]]]]]

        [:div.row {:style "margin-top:20px;"}
         [:div.col-lg-12
          [:p.text-center {:style "margin-bottom: 10px;"}
           [:a {:href "http://cljsfiddle.net"} "cljsfiddle.net"] " &copy; 2013 Jonas Enlund"]]]))

(defn html-view [ns fiddle deps]
  (let [cljs-obj (-> fiddle
                       :cljsfiddle/cljs
                       :cljsfiddle.src/blob
                       :cljsfiddle.blob/text
                       cljs-object-from-src)]
  [:html
   [:head
    [:title ns]
    [:style (-> fiddle
                :cljsfiddle/css
                :cljsfiddle.src/blob
                :cljsfiddle.blob/text)]
    [:script google-analytics-script]]
   [:body
    (-> fiddle
        :cljsfiddle/html
        :cljsfiddle.src/blob
        :cljsfiddle.blob/text)
    [:script "CLOSURE_NO_DEPS=true;"]
    [:script "COMPILED=true;"]
    [:script (:deps-src cljs-obj)]
    (for [dep deps]
      [:script {:src (str "/jscache/" (env :cljsfiddle-version) "/" dep)}])
    [:script (:js-src cljs-obj)]]]))

(defn about-view [user]
  (base (navbar user)
        [:div.row
         [:div.col-lg-12
          [:h3 "About CLJSFiddle"]
          [:ul
           [:li "CLJSFiddle is open source and available on " [:a {:href "http://github.com/jonase/cljsfiddle"} "github."]]
           [:li "Feel free to open bug reports and feature requests! Pull requests are also appreciated!"]
           [:li [:strong "Help needed"] " especially around user interface design."]]

          [:h3 "How does it work?"]
          [:ul
           [:li "In order to save your work you" [:strong " must login "] "via your github account."]
           [:li "Prefix your namespace with your username: " [:pre "(ns username.test)"]]
           [:li "Saved fiddles can be accessed either by"
            [:ul
             [:li "Fiddle view: " [:span {:style "font-family:monospace"} "http://cljsfiddle.net/fiddle/name.space"]]
             [:li "Html view: " [:span {:style "font-family:monospace"} "http://cljsfiddle.net/view/name.space"]]
             [:li "Append the url with " [:span {:style "font-family:monospace"} "?as-of=&lt;some-date&gt;"]
              " for older versions and permalinks."]
             [:li "The date format is the same as clojure instant literals (without the #inst part): 2013-09-29 or 2013-10-02T13:15:01 "]]]]]]))

(defn user-view [logged-in-user user  fiddles]
  (base (navbar logged-in-user)
        [:div.row
         [:div.col-lg-12
          [:h3 "User: " user]
          [:ul
           (for [[ns date] (reverse (sort-by second fiddles))]
             [:li (subs (pr-str date) 7 26) " " [:a {:href (str "/fiddle/" ns)} ns]
              " | " [:a {:href (str "/view/" ns)} "HTML view"]])]]]))
