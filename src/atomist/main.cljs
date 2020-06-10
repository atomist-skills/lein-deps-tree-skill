(ns atomist.main
  (:require [atomist.api :as api]
            [atomist.cljs-log :as log]
            [atomist.container :as container]
            [atomist.github :as github]
            [atomist.json :as json]
            [cljs-node-io.core :as io]
            [cljs-node-io.proc :as proc]
            [cljs.core.async :refer [<! >! chan timeout]]
            [cljs.pprint :refer [pprint]]
            [clojure.string :as str]
            goog.string.format)
  (:require-macros [cljs.core.async.macros :refer [go]]))

(def check-run {:name "lein deps tree"
                :status "completed"})

(defn- failed-run [request stderr]
  (go
   (let [response (<! (github/create-check request
                                           (-> request :data :Push first :repo :owner)
                                           (-> request :data :Push first :repo :name)
                                           (merge check-run
                                                  {:conclusion "failure"
                                                   :head_sha (-> request :data :Push first :after :sha)
                                                   :output {:title "lein deps :tree failure"
                                                            :summary stderr}})))]
     (log/info "check-run status " (:status response)))))

(defn- successful-run [request]
  (go
   (let [response (<! (github/create-check request
                                           (-> request :data :Push first :repo :owner)
                                           (-> request :data :Push first :repo :name)
                                           (merge check-run
                                                  {:conclusion "success"
                                                   :head_sha (-> request :data :Push first :after :sha)
                                                   :output {:title "lein deps :tree success"
                                                            :summary "No confusing dependencies found"}})))]
     (log/info "check-run status " (:status response)))))

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
              (do (<! (failed-run request (last (str/split stderr #"Possibly\ confusing\ dependencies\ found:"))))
                  (<! (handler request)))

              :else
              (do
                (<! (successful-run request))
                (<! (handler request)))))
          (do
            (log/warn "there was no checked out " (.getPath atmhome))
            (<! (api/finish request :failure "Failed to checkout"))))))))

(defn extract-maven-repo-creds
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
       (run-deps-tree)
       (extract-maven-repo-creds)
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