(ns atomist.main
  (:require [cljs.pprint :refer [pprint]]
            [cljs.core.async :refer [<! >! timeout chan]]
            [goog.string.format]
            [atomist.container :as container]
            [atomist.cljs-log :as log]
            [atomist.api :as api]
            [atomist.json :as json]
            [goog.string :as gstring]
            [cljs-node-io.core :as io]
            [cljs-node-io.proc :as proc]
            [clojure.string :as str]
            [cljs-http.client :as client]
            ["xhr2" :as xhr2])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(set! js/XMLHttpRequest xhr2)

(def issue-title "`lein deps tree` found confusing dependencies")
(def issue-body-stamp "[atomist:generated:lein-deps-tree-skill]")

(defn list-issues
  [request & [sha]]
  (go
    (try
      (let [response (<! (client/get "https://api.github.com/search/issues"
                                     {:query-params {"q" (gstring/format "%s state:open type:issue is:private repo:%s/%s %s in:body"
                                                                         (or sha "")
                                                                         (-> request :ref :owner)
                                                                         (-> request :ref :repo)
                                                                         issue-body-stamp)}
                                      :headers {"Authorization" (gstring/format "Bearer %s" (:token request))}}))]
        (if (= 200 (:status response))
          (-> response :body)
          (log/warnf "status %s - %s" (:status response) (-> response :body))))
      (catch :default ex
        (log/error "raised exception " ex)))))

(defn comment-issue
  [request issue comment]
  (go
    (try
      (let [response (<! (client/post (gstring/format "https://api.github.com/repos/%s/%s/issues/%s/comments"
                                                      (-> request :ref :owner)
                                                      (-> request :ref :repo)
                                                      (:number issue))
                                      {:body (json/->str {:body comment})
                                       :headers {"Authorization" (gstring/format "Bearer %s" (:token request))}}))]
        (if (= 201 (:status response))
          (-> response :body)
          (log/warnf "status %s - %s" (:status response) (-> response :body))))
      (catch :default ex
        (log/error "raised exception " ex)))))

(defn create-issue
  [request comment]
  (go
    (try
      (let [response (<! (client/post (gstring/format "https://api.github.com/repos/%s/%s/issues"
                                                      (-> request :ref :owner)
                                                      (-> request :ref :repo))
                                      {:body (json/->str {:title issue-title
                                                          :body (gstring/format "%s\n\n%s" comment issue-body-stamp)})
                                       :headers {"Authorization" (gstring/format "Bearer %s" (:token request))}}))]
        (if (= 201 (:status response))
          (-> response :body)
          (log/warnf "status %s - %s" (:status response) (-> response :body))))
      (catch :default ex
        (log/error "raised exception " ex)))))

(defn raise-issue-if-not-exists [handler]
  (fn [request]
    (go
      (if (:lein-deps-tree request)
        (let [sha (-> request :data :Push first :after :sha)]
          (if (and (:token request))
            (if-let [issue (-> (<! (list-issues request sha)) :items not-empty first)]
              (do
                (log/debugf "Found issue %s for current push, doing nothing" (:number issue))
                (<! (handler request)))
              (if-let [issue (some-> (<! (list-issues request)) :items first)]
                (do
                  (log/debugf "Found issue %s for another commit, updating" (:number issue))
                  (<! (comment-issue request issue (gstring/format "Commit %s still has confusing lein dependencies\n\n```%s```" sha (:lein-deps-tree request))))
                  (<! (handler request)))
                (do
                  (log/debugf "Didn't find an issue, creating")
                  (<! (create-issue request (gstring/format "Commit %s introduced confusing lein dependencies\n\n```%s```" sha (:lein-deps-tree request))))
                  (<! (handler request)))))
            (do
              (log/warnf "Could not find github token")
              (<! (api/finish request :failure "Could not find a github token to raise issue")))))
        (<! (handler request))))))

(defn close-issue
  [request issue]
  (go
    (try
      (let [response (<! (client/patch (gstring/format "https://api.github.com/repos/%s/%s/issues/%s"
                                                       (-> request :ref :owner)
                                                       (-> request :ref :repo)
                                                       (:number issue))
                                       {:body (json/->str {:state "closed"})
                                        :headers {"Authorization" (gstring/format "Bearer %s" (:token request))}}))]
        (if (= 201 (:status response))
          (-> response :body)
          (log/warnf "status %s - %s" (:status response) (-> response :body))))
      (catch :default ex
        (log/error "raised exception " ex)))))

(defn close-issue-if-exists
  [request]
  (go
    (log/infof "Closing issue")
    (doseq [issue (some-> (<! (list-issues request)) :items)]
      (do
        (log/infof "Found issue %s for another commit, closing" (:number issue))
        (-> (comment-issue request issue (gstring/format "Commit %s removed confusing lein dependencies" (-> request :data :Push first :after :sha))))
        (<! (close-issue request issue))))))

(defn run-deps-tree [handler]
  (fn [request]
    (go
      (let [atmhome (io/file (.. js/process -env -ATOMIST_HOME))]
        (if (and (.exists atmhome) (.exists (io/file atmhome "project.clj")))
          (let [[err stdout stderr] (<! (proc/aexec "lein deps :tree-data" {:cwd (.getPath atmhome)}))]
            (cond

              err
              (do
                (log/warnf "Error running lein deps: %s" stderr)
                (<! (api/finish request :failure (str "Error running lein deps: " stderr))))

              (str/includes? stdout "Possibly confusing dependencies found:")
              (<! (handler (assoc request :lein-deps-tree stdout)))

              :else
              (do
                (<! (close-issue-if-exists request))
                (<! (handler request)))))
          (do
            (log/warn "there was no checked out " (.getPath atmhome))
            (<! (api/finish request :failure "Failed to checkout"))))))))

(defn ^:export handler
  "no arguments because this handler runs in a container that should fulfill the Atomist container contract
   the context is extract fro the environment using the container/mw-make-container-request middleware"
  []
  ((-> (api/finished :message "----> Push event handler finished"
                     :success "completed line-deps-tree-skill")
       (raise-issue-if-not-exists)
       (run-deps-tree)
       (api/extract-github-token)
       (api/create-ref-from-push-event)
       (api/skip-push-if-atomist-edited)
       (api/status)
       (container/mw-make-container-request))
   {}))