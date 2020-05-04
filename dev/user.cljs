(ns user
  (:require [atomist.main]
            [atomist.leiningen]
            [cljs.core.async :refer [<! chan]]
            [cljs-node-io.core :refer [slurp spit]])
  (:require-macros [cljs.core.async.macros :refer [go]]))
