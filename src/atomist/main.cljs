(ns atomist.main
  (:require [cljs.pprint :refer [pprint]]
            [cljs.core.async :refer [<! >! timeout chan]]
            [goog.string.format]
            [atomist.cljs-log :as log]
            [atomist.api :as api])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(defn handle-push [request]
(cond
  (contains? (:data request) :Push)
  ((-> (api/finished :message "----> Push event handler finished")

       (api/clone-ref)
       (api/create-ref-from-push-event)
       (api/skip-push-if-atomist-edited)
       (api/status)) request)
  :else
  (log/warn "did not understand")))

(defn ^:export handler
  "handler
    must return a Promise - we don't do anything with the value
    params
      data - Incoming Request #js object
      sendreponse - callback ([obj]) puts an outgoing message on the response topic"
  [data sendreponse]
  (api/make-request
   data
   sendreponse
   handle-push))
