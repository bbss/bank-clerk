(ns user
  (:require [nextjournal.clerk :as clerk]))

;; start Clerk's buit-in webserver on the default port 7777, opening the browser when done
(clerk/serve! {:browse? true})

;;let Clerk watch the given `:paths` for changes
(clerk/serve! {:watch-paths ["notebooks" "src"]})

(clerk/show! "notebooks/bank.clj")
