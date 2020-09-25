(ns atomist.main
  (:require [atomist.api :as api]
            [atomist.cljs-log :as log]
            [atomist.container :as container]
            [cljs-node-io.core :as io]
            [cljs-node-io.proc :as proc]
            [cljs.core.async :refer [<! >! chan timeout]]
            [cljs.pprint :refer [pprint]]
            [clojure.string :as str])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(defn run-deps-tree [handler]
  (fn [request]
    (go
      (let [ATM-HOME (.. js/process -env -ATOMIST_HOME)
            atmhome (io/file ATM-HOME)]
        (if (and (.exists atmhome) (.exists (io/file atmhome "project.clj")))
          (let [[err stdout stderr] (<! (proc/aexec "lein deps :tree-data" {:cwd (.getPath atmhome)
                                                                            :env {"ARTIFACTORY_USER" (-> request :maven :username)
                                                                                  "MVN_ARTIFACTORYMAVENREPOSITORY_USER" (-> request :maven :username)
                                                                                  "ARTIFACTORY_PWD" (-> request :maven :password)
                                                                                  "MVN_ARTIFACTORYMAVENREPOSITORY_PWD" (-> request :maven :password)
                                                                                  ;; use atm-home for .m2 directory
                                                                                  "_JAVA_OPTIONS" (str "-Duser.home=" ATM-HOME)}}))]
            (cond

              err
              (do
                (log/warnf "Error running lein deps: %s" stderr)
                (<! (api/finish request :failure (str "Error running lein deps: " stderr))))

              (str/includes? stderr "Possibly confusing dependencies found:")
              (<! (handler (assoc request
                                  :checkrun/conclusion "failure"
                                  :checkrun/output {:title "lein deps :tree failure"
                                                    :summary stderr})))

              :else
              (<! (handler (assoc request
                                  :checkrun/conclusion "success"
                                  :checkrun/output {:title "lein deps :tree success"
                                                    :summary "No confusing dependencies found"})))))
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
            (log/warn "Found Maven repo id in config, but not in event payload - this probably will not work")
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

(defn cancel-if-not-lein [handler]
  (fn [request]
    (go
      (let [atmhome (io/file (.. js/process -env -ATOMIST_HOME))]
        (if (.exists atmhome)
          (if (.exists (io/file atmhome "project.clj"))
            (<! (handler request))
            (<! (api/finish request :success "Skipping non-lein project" :visibility :hidden)))
          (do
            (log/warn "there was no checked out " (.getPath atmhome))
            (<! (api/finish request :failure "Failed to checkout"))))))))

(defn ^:export handler
  "no arguments because this handler runs in a container that should fulfill the Atomist container contract
   the context is extract fro the environment using the container/mw-make-container-request middleware"
  []
  ((-> (api/finished :message "----> Push event handler finished"
                     :success "completed line-deps-tree-skill")
       (run-deps-tree)
       (api/with-github-check-run :name "lein-deps-tree-skill")
       (extract-maven-repo-creds)
       (cancel-if-not-lein)
       (api/extract-github-token)
       (api/create-ref-from-event)
       (api/skip-push-if-atomist-edited)
       (api/status)
       (container/mw-make-container-request))
   {}))
