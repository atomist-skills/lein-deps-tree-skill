;; Copyright © 2020 Atomist, Inc.
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns atomist.main
  (:require [atomist.api :as api]
            [atomist.cljs-log :as log]
            [atomist.container :as container]
            [goog.string :as gstring]
            [cljs-node-io.core :as io]
            [cljs-node-io.proc :as proc]
            [cljs.core.async :refer [<! >! chan timeout]]
            [cljs.pprint :refer [pprint]]
            [clojure.string :as str]
            [clojure.edn :as edn])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(defn create-ref-from-event
  [handler]
  (fn [request]
    (let [[org commit repo] (-> request :subscription :result first)]
      (handler (assoc request :ref {:repo (:git.repo/name repo)
                                    :owner (:git.org/name org)
                                    :sha (:git.commit/sha commit)}
                      :token (:github.org/installation-token org))))))

(defn -js->clj+
  "For cases when built-in js->clj doesn't work. Source: https://stackoverflow.com/a/32583549/4839573"
  [x]
  (into {} (for [k (js-keys x)] [k (aget x k)])))

(defn ->tx
  "Add each dep to a new commit for the `many`"
  [dep]
  (let [group-and-name (str (first dep))
        [group artifact-name] (str/split group-and-name #"/")
        ;; for clojure where sometimes group is same as artifact
        artifact-name (or artifact-name group)
        version (second dep)
        entity-id (str group-and-name ":" version)]
    (log/infof "Extracted dependency --> %s/%s:%s" group artifact-name version)
    {:schema/entity-type :maven/artifact
     :maven.artifact/version version
     :maven.artifact/group group
     :maven.artifact/name artifact-name
     :schema/entity entity-id}))

(defn transact-deps
  [request std-out]
  (go
    (try
      (let [[org commit repo] (-> request :subscription :result first)
            deps-tx (->>
                     std-out
                     edn/read-string
                     (map first)
                     (map #(take 2 %))
                     (map ->tx))]

        (<! (api/transact request (concat [{:schema/entity-type :git/repo
                                            :schema/entity "$repo"
                                            :git.provider/url (:git.provider/url org)
                                            :git.repo/source-id (:git.repo/source-id repo)}
                                           {:schema/entity-type :git/commit
                                            :git.provider/url (:git.provider/url org)
                                            :git.commit/sha (:git.commit/sha commit)
                                            :project.dependencies/maven {:add (map :schema/entity deps-tx)}
                                            :git.commit/repo "$repo"}]
                                          deps-tx))))
      (catch :default ex
        (log/error "Error transacting deps: " ex)))))

(defn run-deps-tree [handler]
  (fn [request]
    (go
      (let [cwd (io/file (-> request :project :path))]
        (let [[err stdout stderr] (<! (proc/aexec "lein deps :tree-data" {:cwd (.getPath cwd)
                                                                          :env (-> (-js->clj+ (.. js/process -env))
                                                                                   (merge
                                                                                    {"MVN_ARTIFACTORYMAVENREPOSITORY_USER"
                                                                                     (.. js/process -env -MVN_ARTIFACTORYMAVENREPOSITORY_USER)
                                                                                     "MVN_ARTIFACTORYMAVENREPOSITORY_PWD"
                                                                                     (.. js/process -env -MVN_ARTIFACTORYMAVENREPOSITORY_PWD)
                                                                                     ;; use atm-home for .m2 directory
                                                                                     "_JAVA_OPTIONS" (str "-Duser.home=" (.. js/process -env -ATOMIST_HOME))}))}))]
          (cond

            err
            (do
              (log/warnf "Error running lein deps: %s" stderr)
              (<! (api/finish request :failure (str "Error running lein deps: " stderr))))

            (str/includes? stderr "Possibly confusing dependencies found:")
            (<! (handler (assoc request
                                :atomist/summary (gstring/format "Possibly confusing dependencies found %s/%s:%s" (-> request :ref :owner) (-> request :ref :repo) (-> request :ref :sha))
                                :checkrun/conclusion "failure"
                                :checkrun/output {:title "lein deps :tree failure"
                                                  :summary stderr})))

            :else
            (do
              (<! (transact-deps request stdout))
              (<! (handler (assoc request
                                  :atomist/summary (gstring/format "No confusing dependencies found %s/%s:%s" (-> request :ref :owner) (-> request :ref :repo) (-> request :ref :sha))
                                  :checkrun/conclusion "success"
                                  :checkrun/output {:title "lein deps :tree success"
                                                    :summary "No confusing dependencies found"}))))))))))

(defn ^:export handler
  "no arguments because this handler runs in a container that should fulfill the Atomist container contract
   the context is extract fro the environment using the container/mw-make-container-request middleware"
  []
  ((-> (api/finished :message "----> Push event handler finished"
                     :success "completed line-deps-tree-skill")
       (run-deps-tree)
       (api/clone-ref)
       (api/with-github-check-run :name "lein-deps-tree-skill")
       (create-ref-from-event)
       (api/status :send-status (fn [{:atomist/keys [summary]}] summary))
       (container/mw-make-container-request))
   {}))
