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
            [cljs-node-io.core :as io]
            [cljs-node-io.proc :as proc]
            [cljs.core.async :refer [<! >! chan timeout]]
            [cljs.pprint :refer [pprint]]
            [clojure.string :as str])
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
                           :checkrun/conclusion "failure"
                           :checkrun/output {:title "lein deps :tree failure"
                                             :summary stderr})))

            :else
            (<! (handler (assoc request
                           :checkrun/conclusion "success"
                           :checkrun/output {:title "lein deps :tree success"
                                             :summary "No confusing dependencies found"})))))))))

(defn ^:export handler
  "no arguments because this handler runs in a container that should fulfill the Atomist container contract
   the context is extract fro the environment using the container/mw-make-container-request middleware"
  []
  ((-> (api/finished :message "----> Push event handler finished"
                     :success "completed line-deps-tree-skill")
       (run-deps-tree)
       (api/clone-ref)
       (create-ref-from-event)
       (api/with-github-check-run :name "lein-deps-tree-skill")
       (api/status)
       (container/mw-make-container-request))
   {}))
