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
            ["xhr2" :as xhr2]
            [atomist.graphql-channels :as channels])
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
          (let [[err stdout stderr] (<! (proc/aexec "lein deps :tree-data" {:cwd (.getPath atmhome)
                                                                            :env {"ARTIFACTORY_USER" (-> request :maven :username)
                                                                                  "ARTIFACTORY_PWD" (-> request :maven :password)}}))]
            (cond

              err
              (do
                (log/warnf "Error running lein deps: %s" stderr)
                (<! (api/finish request :failure (str "Error running lein deps: " stderr))))

              (str/includes? stderr "Possibly confusing dependencies found:")
              (<! (handler (assoc request :lein-deps-tree stderr)))

              :else
              (do
                (<! (close-issue-if-exists request))
                (<! (handler request)))))
          (do
            (log/warn "there was no checked out " (.getPath atmhome))
            (<! (api/finish request :failure "Failed to checkout"))))))))

(defn fetch-maven-repo-creds
  "TODO - make this work for many repositories"
  [handler]
  (fn [request]
    (go
      (let [rp-id (some->> request
                           :skill
                           :configuration
                           :instances
                           first
                           :resourceProviders
                           (filter #(= "maven" (:name %)))
                           first
                           :selectedResourceProviders
                           first
                           :id)
            rp (some->> request
                        :data
                        :Push
                        first
                        :repo
                        :org
                        :team
                        :resourceProviders
                        (filter #(= rp-id (:id %)))
                        first)]
        (cond
          (not rp-id)
          (do
            (log/warn "could not find maven resource provider id in skill config payload ")
            (<! (handler request)))

          (and rp-id (not rp))
          (do
            (log/warn "Found Maven repo id in config, but not in event payload - this might not work")
            (<! (handler request)))

          (not (-> rp :credential :secret))
          (do
            (log/warn "Found Maven repo id in config and payload, but not secret was found. Let's hope it's not needed")
            (<! (handler request)))

          (not (-> rp :credential :owner :login))
          (do
            (log/warn "Couldn't find maven repo username, let's hope we don't need it")
            (<! (handler request)))

          :otherwise
          (do
            (log/info "Found Maven repo credentials, making them available to lein")
            (<! (handler (assoc request :maven {:username (-> rp :credential :owner :login) :password (-> rp :credential :secret)})))))))))

(defn read-atomist-payload [handler]
  (letfn [(payload->owner [{:keys [data]}]
            (or (-> data :Push first :repo :owner)
                (-> data :Tag first :commit :repo :owner)))
          (payload->repo [{:keys [data]}]
            (or (-> data :Push first :repo :name)
                (-> data :Tag first :commit :repo :name)))]
    (fn [request]
      (go
        (api/trace "read-atomist-payload")
        (try
          (let [payload (-> (io/file (:payload request))
                            (io/slurp)
                            (json/->obj)
                            (api/event->request))]
            (log/info "extensions " (:extensions payload))
            (log/info "skill " (:skill payload))
            (log/info "secrets " (map :uri (:secrets payload)))
           ;;(log/info "data " (:data payload))
            (if (contains? (:data payload) :Push)
              (<! (handler
                   (-> request
                       (assoc :owner (payload->owner payload) :repo (payload->repo payload))
                       (merge payload {:api-key (->> payload :secrets (filter #(= "atomist://api-key" (:uri %))) first :value)}))))
              (do
                (when-let [f (io/file (:dir request))]
                  (log/infof "%s - %s" (.getPath f) (.exists f))
                  (log/infof "Tag %s - %s" (-> request :data :Tag :name) (-> request :data :Tag :description)))
                (<! (api/finish request :success "not building new Tag event")))))
          (catch :default ex
            (log/error ex)
            request))))))

(defn ^:export handler
  "no arguments because this handler runs in a container that should fulfill the Atomist container contract
   the context is extract fro the environment using the container/mw-make-container-request middleware"
  []
  ((-> (api/finished :message "----> Push event handler finished"
                     :success "completed line-deps-tree-skill")
       (raise-issue-if-not-exists)
       (run-deps-tree)
       (fetch-maven-repo-creds)
       (api/extract-github-token)
       (api/create-ref-from-push-event)
       (api/skip-push-if-atomist-edited)
       (api/status)
       ;; make sure we don't log any secrets etc...
       ((api/compose-middleware
         [read-atomist-payload]
         [container/create-logger]
         [container/check-environment]
         [container/make-container-request])))
   {}))