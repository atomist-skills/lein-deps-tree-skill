(ns atomist.main
  (:require [cljs.pprint :refer [pprint]]
            [cljs.core.async :refer [<! >! timeout chan]]
            [goog.string.format]
            [atomist.container :as container]
            [atomist.cljs-log :as log]
            [atomist.api :as api]
            [cljs-node-io.core :as io]
            [cljs-node-io.proc :as proc])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(defn run-deps-tree [handler]
  (fn [request]
    (go
      (let [atmhome (io/file (.. js/process -env -ATOMIST_HOME))]
        (if (and (.exists atmhome) (.exists (io/file atmhome "project.clj")))
          (let [[err stdout stderr] (<! (proc/aexec "lein deps :tree" {:cwd (.getPath atmhome)}))]
            (log/info stdout)
            (if err
              (log/error stderr)))
          (log/warn "there was no checked out " (.getPath atmhome)))
        (<! (handler request))))))

(defn ^:export handler
  "no arguments because this handler runs in a container that should fulfill the Atomist container contract
   the context is extract fro the environment using the container/mw-make-container-request middleware"
  []
  ((-> (api/finished :message "----> Push event handler finished"
                     :success "completed line-deps-tree-skill")
       (run-deps-tree)
       (api/skip-push-if-atomist-edited)
       (api/status)
       (container/mw-make-container-request)) {}))