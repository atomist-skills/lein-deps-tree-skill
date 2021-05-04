;; Copyright © 2021 Atomist, Inc.
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
            [atomist.async :refer-macros [<? go-safe]]
            [clojure.string :as str]
            [clojure.edn :as edn]))

(defn create-ref-from-event
  [handler]
  (fn [request]
    (let [[commit] (-> request :subscription :result first)
          repo (:git.commit/repo commit)
          org (:git.repo/org repo)]
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
  (go-safe
    (try
      (let [[commit] (-> request :subscription :result first)
            repo (:git.commit/repo commit)
            org (:git.repo/org repo)
            deps-tx (->>
                     std-out
                     edn/read-string
                     (map first)
                     (map #(take 2 %))
                     (map ->tx))]

        (<? (api/transact request (concat [{:schema/entity-type :git/repo
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

(defn transact-project-version
  [request]
  (go-safe
    (try
      (let [f (io/file (-> request :project :path))
            [commit] (-> request :subscription :result first)
            repo (:git.commit/repo commit)
            org (:git.repo/org repo)
            version (nth (edn/read-string (io/slurp (io/file f "project.clj"))) 2)]
        (<? (api/transact request [{:schema/entity-type :git/repo
                                    :schema/entity "$repo"
                                    :git.provider/url (:git.provider/url org)
                                    :git.repo/source-id (:git.repo/source-id repo)}
                                   {:schema/entity-type :git/commit
                                    :schema/entity "$commit"
                                    :git.provider/url (:git.provider/url org)
                                    :project/version version
                                    :git.commit/sha (:git.commit/sha commit)
                                    :git.commit/repo "$repo"}])))
      (catch :default ex
        (log/error "Error transacting project version " ex)))))

(defn add-profile
  [handler]
  (fn [request]
    (go-safe
      (let [repo-map (reduce
                      (fn [acc [_ repo usage]]
                        (if (and repo usage)
                          (update acc (keyword usage) (fn [repos]
                                                        (conj (or repos []) repo)))
                          acc))
                      {}
                      (-> request :subscription :result))]
        (log/infof "Found resolve integration: %s"
                   (->> (:resolve repo-map)
                        (map #(gstring/format "%s - %s" (:maven.repository/repository-id %) (:maven.repository/url %)))
                        (str/join ", ")))

        (io/spit
         (io/file (-> request :project :path) "profiles.clj")
         (pr-str
          {:lein-deps-tree
           (merge
            {:repositories (->> (:resolve repo-map)
                                (map (fn [{:maven.repository/keys [repository-id url username secret]}]
                                       [repository-id {:url url
                                                       :username username
                                                       :password secret}]))
                                (into []))}
            (when-let [deps (not-empty (:gpg-verify-deps request))]
              {:gpg-verify {:deps (map symbol deps)}
               :plugins  '[[org.kipz/clj-gpg-verify "0.1.2"]]})
            ;; if the root project does not specify a url then add one to the profile
            (when-not (-> request :atomist.leiningen/non-evaled-project-map :url)
              {:url (gstring/format "https://github.com/%s/%s" (-> request :ref :owner) (-> request :ref :repo))}))}))
        (<? (handler request))))))

(defn run-deps-tree [handler]
  (fn [request]
    (go-safe
      (let [cwd (io/file (-> request :project :path))]
        (<? (transact-project-version request))
        (let [[err stdout stderr] (<? (proc/aexec "lein with-profile lein-deps-tree deps :tree-data"
                                                  {:cwd (.getPath cwd)
                                                   :env (-> (-js->clj+ (.. js/process -env))
                                                            (merge
                                                             {;; use atm-home for .m2 directory
                                                              "_JAVA_OPTIONS" (str "-Duser.home=" (.. js/process -env -ATOMIST_HOME))}))}))]
          (if (or err
                  (str/includes? stderr "Possibly confusing dependencies found:"))
            (<? (handler (assoc request
                                :checkrun/conclusion "failure"
                                :checkrun/output {:title "lein deps :tree failure"
                                                  :summary stderr}
                                :atomist/status {:code 1
                                                 :reason (gstring/format "Possibly confusing dependencies found %s/%s:%s" (-> request :ref :owner) (-> request :ref :repo) (-> request :ref :sha))})))
            (do
              (<? (transact-deps request stdout))
              (<? (handler (assoc request
                                  :atomist/status {:code 0
                                                   :reason (gstring/format "No confusing dependencies found %s/%s:%s" (-> request :ref :owner) (-> request :ref :repo) (-> request :ref :sha))}
                                  :checkrun/conclusion "success"
                                  :checkrun/output {:title "lein deps :tree success"
                                                    :summary "No confusing dependencies found"}))))))))))

(defn run-gpg-verify [handler]
  (fn [request]
    (go-safe
     (if (not-empty (:gpg-verify-deps request))
       (let [cwd (io/file (-> request :project :path))
             [err stdout stderr] (<? (proc/aexec "lein with-profile lein-deps-tree gpg-verify"
                                                 {:cwd (.getPath cwd)
                                                  :env (-> (-js->clj+ (.. js/process -env))
                                                           (merge
                                                            {;; use atm-home for .m2 directory
                                                             "GNUPGHOME" "/opt/gpg"
                                                             "_JAVA_OPTIONS" (str "-Duser.home=" (.. js/process -env -ATOMIST_HOME))}))}))]
         (if
          err
           (do
             (log/warnf "Error running lein gpg-verify: %s" stderr)
             (assoc request
                    :checkrun/conclusion "failure"
                    :checkrun/output {:title "lein gpg-verify failure"
                                      :summary stderr}
                    :atomist/status {:code 1
                                     :reason "lein gpg-verify failure. Check logs for details"}))

           (do
             (log/infof "Successfully ran lein gpg-verify, continuing")
             (<? (handler request)))))
       (do
         (log/infof "Skipping verify as not configured")
         (<? (handler request)))))))

(defn ^:export handler
  "no arguments because this handler runs in a container that should fulfill the Atomist container contract
   the context is extract fro the environment using the container/mw-make-container-request middleware"
  []
  ((-> (api/finished )
       (run-gpg-verify)
       (run-deps-tree)
       (add-profile)
       (api/add-skill-config)
       (api/clone-ref)
       (api/with-github-check-run :name "lein-deps-tree-skill")
       (create-ref-from-event)
       (api/log-event)
       (api/status)
       (container/mw-make-container-request))
   {}))
