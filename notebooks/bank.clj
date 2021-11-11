;;## Bank Clerk
;;BB: This notebook describes the process of working through a Clojure challenge.
;;I have been wanting to try out [[Clerk](https://github.com/nextjournal/clerk)] as a development tool that comes closer to the constantly refreshing codebase style of coding that is familiar in ClojureScript browser app development. These banking feature challenges seem like a great fit for solving while describing the process.

(ns notebooks.bank
  (:require [nextjournal.clerk :as clerk]))

;;# Banking API

;;## Summary

;;Create an HTTP API for managing banking accounts. Your API will have six features, each with its own endpoint. Additionally, there are some global requirements.

;;Deliver a link to a repository containing your project (e.g. GitHub). To get an impression of your way of working we want to be able to see your commit history.
;;## Global requirements
;;- The API and its tests should be written in Clojure.
;;- Automated tests prove each feature/requirement is correctly implemented.
;; - Make use of a database such that domain information (accounts etc.) is not lost on a system restart.
;; - The API is able to asynchronously process 1000 concurrent requests.
;; - Request and response bodies are in JSON format.

;;BB: Okay, could use a dedicated test-runner, but probably we can use this notebook to demonstrate correctly implemented features.
;;Database that I like is XTDB (formerly cruxdb), it certainly has banking and auditing in mind. It can be configured with kafka back-end for the transaction log, and we'll use rocksdb to build the indexes on the local disk.

;;Will go through the features and add new requirements as we progress. We'll need json (un)parsing, a database, a web server and some basic routing.


;;## Feature 1 - Create a bank account

;;### Requirements

;;- You can create a bank account.
;;- Each bank account has a unique account number.
;;- Account numbers are generated automatically.
;;- New bank accounts start with a balance of 0.

;;### API

;;Endpoint:

;;POST /account

;;Request body:
;;
;;```json
;;{
;;  name: "Mr. Black"
;;}
;;```

;;Response body describes the created account:
;;```json
;;{
;; "account-number": 1,
;; name: "Mr. Black",
;; balance: 0
;;}
;;```

;;BB: Let's start off setting up a database:
(require '[clojure.java.io :as io]
         '[xtdb.api :as xt])

(defn start-xtdb! []
  (let [_run-at #inst "2021-11-10T15:09:43.090-00:00"]
    (xt/start-node
     {:xtdb/tx-log {:kv-store {:xtdb/module 'xtdb.rocksdb/->kv-store
                               :db-dir (io/file "data/dev/tx-log")
                               :sync? true}}
      :xtdb/document-store {:kv-store {:xtdb/module 'xtdb.rocksdb/->kv-store
                                       :db-dir (io/file "data/dev/doc-store")
                                       :sync? true}}
      :xtdb/index-store {:kv-store {:xtdb/module 'xtdb.rocksdb/->kv-store
                                    :db-dir (io/file "data/dev/index-store")
                                    :sync? true}}})))


;;BB: Hmmm, Clerk tries to restart the node when it's still running, which leads to a lockfile error. No biggie, we'll close the node on each namespace refresh and wait a little while to start the node:

(declare node)

(def node
  (if (bound? (var node))
    node
    (start-xtdb!)))

(comment (.close node))

;;BB: Now we have a database node to transact and query against we can start working on the first feature. Let's turn the json object into clojure EDN and put it in DB.

(require '[clojure.data.json :as json])

(def mr-black-as-edn
  (json/read-json
   "{
   \"account-number\": 1,
   \"name\": \"Mr. Black\",
   \"balance\": 0
   }"))

(defn add-account-number-as-id [entity]
  (assoc entity
         :xt/id
         (:account-number entity)))

(def create-mr-black-transaction (add-account-number-as-id mr-black-as-edn))

(xt/submit-tx
 node
 [[::xt/put create-mr-black-transaction]])

(xt/entity (xt/db node) 1)

;; ## Feature 2 - View a bank account

;; ### Requirements

;; - You can retrieve an existing bank account.

;; ### API

;; Endpoint

;; GET /account/:id

;; Response body:
;;```json
;; {
;;  "account-number": 1,
;;  name: "Mr. Black",
;;  balance: 0
;;  }
;;```

;;BB: use pull syntax to form an object from the fields we desire of the entity

(let [mr-black-edn (xt/pull (xt/db node)
                            [:account-number :name :balance]
                            1)]
  (json/write-str mr-black-edn))

(defn account-number->bank-account [account-number]
  (xt/pull (xt/db node)
           [:account-number :name :balance]
           account-number))

(json/write-str (account-number->bank-account 1))

;; ## Feature 3 - Deposit money to an account

;; ### Requirements

;; - You can deposit money to an existing bank account.
;; - You can only deposit a positive amount of money.

;; ### API

;; Endpoint

;; POST /account/:id/deposit

;; Request body:

;;```json
;;{
;;  amount: 100
;; }
;;```


;;The response body describes the new situation:

;;```json
;;{
;; "account-number": 1,
;; name: "Mr. Black",
;; balance: 100
;;}
;;```

;;BB let's grab the current balance and add the deposited amount, then we submit to the database. We will add a match transaction for this, this ensures nothing happened to the entity during getting the current balance and updating it, no infinite money glitches!

(let [deposit 100
      mr-black-poor (account-number->bank-account 1)
      mr-black-rich (update mr-black-poor :balance + deposit)]
  mr-black-rich)

(defn deposit-to-account [amount account-number]
  (let [before (xt/entity (xt/db node) account-number)
        after  (update before :balance + amount)
        transaction [[::xt/match account-number before]
                     [::xt/put after]]]
    (xt/await-tx node (xt/submit-tx node transaction))
    (account-number->bank-account account-number)))

(deposit-to-account 101 1)

;; ## Feature 4 - Withdraw money from an account

;; ### Requirements

;; - You can withdraw money from an existing bank account.
;; - You can only withdraw a positive amount of money.
;; - The resulting balance should not fall below zero.

;; ### API

;; Endpoint

;; POST /account/:id/withdraw

;; Request body:
;;```json
;;{
;; \"amount\": 5
;; }
;;```

;;The response body describes the new situation:

;;```json
;; {
;;  \"account-number\": 1,
;;  \"name\": \"Mr. Black\",
;;  \"balance\": 95
;;  }
;;```

;;BB Same as deposits, but adding some checks to the resulting balance

(defn withdraw-from-account [amount account-number]
  {:pre [(pos? amount)]}
  (let [before (xt/entity (xt/db node) account-number)
        after  (update before :balance - amount)
        transaction [[::xt/match account-number before]
                     [::xt/put after]]]
    (if (>= (:balance after) 0)
      (do (xt/await-tx node (xt/submit-tx node transaction))
          (account-number->bank-account account-number))
      (throw (Exception. "Can't withdraw more than you own. Get a capitalism-plus-account for going into debt. It'll be great.")))))

#_"This pre check error gets Clerk stuck (alpha..), but throws exceptions as it's supposed to."

#_(withdraw-from-account -100 1)

(withdraw-from-account 100 1)

;; ## Feature 5 - Transfer money between accounts

;; ### Requirements

;; - You can transfer money from one existing account to another existing account.
;; - You cannot transfer money from an account to itself.
;; - The resulting balance of the sending account should not fall below
;; zero.

;; ### API

;; Endpoint

;; POST /account/:id/send

;; Request body:

;; {
;;  amount: 50,
;;  "account-number": 800
;; }

;; Where `:id` describes the sender and `\"account-number\"` the receiver.

;; The response body describes the new situation of the sending
;; account:

;; {
;;  "account-number": 1,
;;  name: "Mr. Black",
;;  "balance": 45
;;  }

;;BB in this case we match both the before and after accounts.

;;create accounts so our transactions don't error
(xt/submit-tx
 node
 [[::xt/put {:xt/id 2
             :name "Mr. White"
             :account-number 2
             :balance 1000}]
  [::xt/put {:xt/id 800
             :name "Mr. Gray"
             :account-number 800
             :balance 1000}]])


(defn transfer-amount-from-sender-to-receiver [amount sender-account-number receiver-account-number]
  {:pre [(pos? amount)]}
  (let [sender-before (xt/entity (xt/db node) sender-account-number)
        sender-after  (update sender-before :balance - amount)
        receiver-before   (xt/entity (xt/db node) receiver-account-number)
        receiver-after    (update sender-before :balance + amount)
        transaction [[::xt/match sender-account-number sender-before]
                     [::xt/put sender-after]
                     [::xt/match receiver-account-number receiver-before]
                     [::xt/put receiver-after]]]
    (cond
      (< (:balance sender-after) 0)
      (throw (Exception. "Can't withdraw more than you own. Get a capitalism-plus-account for going into debt. It'll be great."))
      (not (seq receiver-before))
      (throw (Exception. "Receiver doesn't exist. Don't burn money, this isn't crypto."))
      (not (seq sender-before))
      (throw (Exception. "Sender doesn't exist."))
      :else
      (do (xt/await-tx node (xt/submit-tx node transaction))
          (account-number->bank-account sender-account-number)))))

(transfer-amount-from-sender-to-receiver 50 2 800)

;;BB This works, but could be improved: it doesn't compose with the deposit and withdraw functions for feature 3 and 4. They do too much for that. We could refactor them to just return the transaction data and do the checks. And transfer-amount-from-sender-to-receiver-composed could then do the actual transaction.

(defn deposit-to-account-composable [amount account-number]
  (let [before (xt/entity (xt/db node) account-number)
        after  (update before :balance + amount)
        transaction [[::xt/match account-number before]
                     [::xt/put after]]]
    (if (seq before)
      transaction
      (throw (Exception. "Sender doesn't exist.")))))

(defn withdraw-from-account-composable [amount account-number]
  {:pre [(pos? amount)]}
  (let [before (xt/entity (xt/db node) account-number)
        after  (update before :balance - amount)
        transaction [[::xt/match account-number before]
                     [::xt/put after]]]
    (if (>= (:balance after) 0)
      transaction
      (throw (Exception. "Can't withdraw more than you own. Get a capitalism-plus-account for going into debt. It'll be great.")))))

(defn transfer-amount-from-sender-to-receiver-composed [amount sender-account-number receiver-account-number]
  (let [withdraw-tx-data (withdraw-from-account-composable amount sender-account-number)
        deposit-tx-data  (deposit-to-account-composable amount receiver-account-number)
        transaction-data (vector withdraw-tx-data deposit-tx-data)]
    (xt/await-tx node (xt/submit-tx node transaction-data))
    (account-number->bank-account sender-account-number)))

(transfer-amount-from-sender-to-receiver 50 2 800)

;; ## Feature 6 - Retrieve account audit log

;; ### Requirements

;; - You can retrieve the audit log of an account.
;; - The audit log consists of records describing the events on the account.
;; - The audit log records appear in reverse chronological
;; order.
;; - An audit record has the following fields:
;; - `sequence`: incrementing transaction sequence number
;; - `debit`: amount of money that was removed
;; - `credit`: amount of money that was added
;; - `description`: describes the action that took place. Possible values:
;; - \"withdraw\"
;; - \"deposit\"
;; - \"send to #900\" (a transfer to account 900)
;; - \"receive from #800\" (a transfer from account 800)

;; ### API

;; Endpoint:

;; GET /account/:id/audit

;; Assuming the following sequence of transactions:

;; - deposit $100 to account #1
;; - transfer $5 from account #1 to account #900
;; - transfer $10 from account #800 to account #1
;; - withdraw $20 from account #1

;; The endpoint responds with this body:

;; [
;;  {
;;   "sequence": 3,
;;   "debit": 20,
;;   "description": "withdraw"
;;   },
;;  {
;;   "sequence": 2,
;;   "credit": 10,
;;   "description": "receive from #800"
;;   },
;;  {
;;   "sequence": 1,
;;   "debit": 5,
;;   "description": "send to #900"
;;   },
;;  {
;;   "sequence": 0,
;;   "credit": 100,
;;   "description": "deposit"
;;   }
;;  ]
