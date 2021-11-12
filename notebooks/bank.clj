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
  (xt/start-node
   {:xtdb/tx-log {:kv-store {:xtdb/module 'xtdb.rocksdb/->kv-store
                             :db-dir (io/file "data/dev/tx-log")
                             :sync? true}}
    :xtdb/document-store {:kv-store {:xtdb/module 'xtdb.rocksdb/->kv-store
                                     :db-dir (io/file "data/dev/doc-store")
                                     :sync? true}}
    :xtdb/index-store {:kv-store {:xtdb/module 'xtdb.rocksdb/->kv-store
                                  :db-dir (io/file "data/dev/index-store")
                                  :sync? true}}}))


(defonce node (start-xtdb!))

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

(xt/await-tx node
 (xt/submit-tx
  node
  [[::xt/put create-mr-black-transaction]]))

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

(xt/await-tx node
 (xt/submit-tx
  node
  [[::xt/put {:xt/id 2
              :name "Mr. White"
              :account-number 2
              :balance 1000}]
   [::xt/put {:xt/id 800
              :name "Mr. Gray"
              :account-number 800
              :balance 1000}]]))

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
;;```json
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
;;```

;;BB cool, XTDB has a open-tx-log api that can help out here. Let's open a fresh in-memory node so we can apply the test data. While we're at it let's refactor the transfer, withdraw and deposit functions to take the node as an argument instead of referring to the node in the namespace scope.

(def fresh-node (xt/start-node {}))

(defn on-node-deposit-to-account [node amount account-number]
  (let [before (xt/entity (xt/db node) account-number)
        after  (update before :balance + amount)
        transaction [[::xt/match account-number before]
                     [::xt/put after]]]
    (if (seq before)
      transaction
      (throw (Exception. "Sender doesn't exist.")))))

(defn on-node-withdraw-from-account [node amount account-number]
  {:pre [(pos? amount)]}
  (let [before (xt/entity (xt/db node) account-number)
        after  (update before :balance - amount)
        transaction [[::xt/match account-number before]
                     [::xt/put after]]]
    (if (>= (:balance after) 0)
      transaction
      (throw (Exception. "Can't withdraw more than you own. Get a capitalism-plus-account for going into debt. It'll be great.")))))

(defn on-node-transfer-amount-from-sender-to-receiver [node amount sender-account-number receiver-account-number]
  (let [withdraw-tx-data (on-node-withdraw-from-account node amount sender-account-number)
        deposit-tx-data  (on-node-deposit-to-account node amount receiver-account-number)
        transaction-data (concat withdraw-tx-data deposit-tx-data)]

    (xt/await-tx node (xt/submit-tx node transaction-data))
    (account-number->bank-account sender-account-number)))

(def test-mutations
  [{:type :create-account
    :starting-balance 100
    :account-number 1}
   {:type :create-account
    :starting-balance 100
    :account-number 900}
   {:type :create-account
    :starting-balance 100
    :account-number 800}
   {:type :deposit
    :amount 100
    :to 1}
   {:type :transfer
    :amount 5
    :from 1
    :to 900}
   {:type :transfer
    :amount 10
    :from 800
    :to 1}
   {:type :withdraw
    :amount 20
    :from 1}])

(comment
  "Clerk doesn't know how to deal with this multimethod yet so have to use a regular function."
  (defmulti bank-employee-instructions (comp :type second vector))

  (defmethod bank-employee-instructions :create-account
    [node {:keys [starting-balance
                  account-number
                  name]}]
    (->> [[::xt/put (add-account-number-as-id
                     {:balance starting-balance
                      :account-number account-number
                      :name (or name account-number)})]]
         (xt/submit-tx node)
         (xt/await-tx node)))

  (defmethod bank-employee-instructions :deposit
    [node {:keys [amount to]}]
    (->> (on-node-deposit-to-account node amount to)
         (xt/submit-tx node)
         (xt/await-tx node)))

  (defmethod bank-employee-instructions :transfer
    [node {:keys [amount from to]}]
    (xt/await-tx node
                 (on-node-transfer-amount-from-sender-to-receiver node amount from to)))

  (defmethod bank-employee-instructions :withdraw
    [node {:keys [amount from]}]
    (->> (on-node-withdraw-from-account node amount from)
         (xt/submit-tx node)
         (xt/await-tx node))))

(defn bank-employee-instructions [node {:keys [type] :as task}]
  (case type
    :create-account
    (let [{:keys [starting-balance
                  account-number
                  name]} task]
      (->> [[::xt/put (add-account-number-as-id
                       {:balance starting-balance
                        :account-number account-number
                        :name (or name account-number)})]]
           (xt/submit-tx node)
           (xt/await-tx node)))
    :deposit
    (let [{:keys [amount to]} task]
      (->> (on-node-deposit-to-account node amount to)
           (xt/submit-tx node)
           (xt/await-tx node)))
    :withdraw
    (let [{:keys [amount from]} task]
      (->> (on-node-withdraw-from-account node amount from)
           (xt/submit-tx node)
           (xt/await-tx node)))
    :transfer
    (let [{:keys [amount from to]} task]
      (->> (on-node-transfer-amount-from-sender-to-receiver
            node amount from to)
           (xt/await-tx node)))))

(def employee-instructions-working-at-fresh-node
  (partial bank-employee-instructions fresh-node))

(doall
 (for [mutation test-mutations]
   (apply employee-instructions-working-at-fresh-node [mutation])))

(with-open [cursor (xt/open-tx-log fresh-node nil true)]
  (let [result (iterator-seq cursor)]
    (def log result)))

;;BB The log has information on the transactions as they happened. We can deduce that the fourth transaction was a deposit, the fifth a transfer etc. from the tx operations. Then shape the response into the desired {:sequence x :debit y :description z} format. After filtering and shaping the log we reverse it for the chronological order requirement. I would recommend using the tx-id for the sequence to stay close to the data the database uses: Note that since we use two match and puts in the same transaction for transfers we wouldn't have a -by-one-incremented- sequence id. So to stay true to the assignment we'll assign a sequence id manually.


(let [{::xt/keys [tx-id tx-ops]} (nth log 3)
      [[_ _ {balance-before :balance}]
       [_ {balance-after :balance}]] tx-ops
      difference-balance (- balance-after balance-before)]
  (if (pos? difference-balance)
    {:credit difference-balance
     :description "deposit"}
    {:debit (Math/abs difference-balance)
     :description "withdraw"}))

(let [audit-log-for-account 1
      {::xt/keys [tx-id tx-ops]} (nth log 4) ;;(nth log 5)
      [[_ _ {balance-before :balance}]
       [_ {balance-after :balance
           sender-account :account-number}]
       _
       [_ {receiver-account :account-number}]] tx-ops
      difference-balance (- balance-after balance-before)]
  (cond (= receiver-account audit-log-for-account)
        {:debit (Math/abs difference-balance)
         :description
         (str "receive from #" sender-account)}
        (= sender-account audit-log-for-account)
        {:debit (Math/abs difference-balance)
         :description
         (str "send to #" receiver-account)}))

(defn grab-audit-item [audit-log-for-account {::xt/keys [tx-id tx-ops]}]
  (let [[[_ _ {balance-before :balance}]
         [_ {sender-account :account-number
             balance-after :balance}]
         _
         [_ {receiver-account :account-number}]] tx-ops
        ]
    (when (and balance-before balance-after)
      (let [difference-balance (- balance-after balance-before)]
        (cond
          (= receiver-account audit-log-for-account)
          {:credit (Math/abs difference-balance)
           :description
           (str "receive from #" sender-account)}
          (and receiver-account
               (= sender-account audit-log-for-account))
          {:debit (Math/abs difference-balance)
           :description
           (str "send to #" receiver-account)}
          (and (= sender-account audit-log-for-account)
               (pos? difference-balance))
          {:credit difference-balance
           :description "deposit"}
          (= sender-account audit-log-for-account)
          {:debit (Math/abs difference-balance)
           :description "withdraw"})))))

(->> log
     (keep (partial grab-audit-item 1))
     (map-indexed (fn [i item]
                    (assoc item :sequence i)))
     reverse)

(defn audit-log-for-account-id [node id]
  (with-open [cursor (xt/open-tx-log node nil true)]
    (let [log (iterator-seq cursor)]
      (->> log
           (keep (partial grab-audit-item id))
           (map-indexed (fn [i item]
                          (assoc item :sequence i)))
           reverse))))

(require '[ruuter.core :as ruuter]
         '[org.httpkit.server :as http])

(def routes
  [{:path     "/account"
    :method   :post
    :response (fn [{:keys [body]}]
                (let [name    (:name (json/read-json (slurp body)))
                      action  {:type :create-account
                               :starting-balance 0
                               :account-number (str (java.util.UUID/randomUUID))
                               :name name}]
                  (bank-employee-instructions node action)
                  {:status 200
                   :body (-> (:account-number action)
                             account-number->bank-account
                             json/write-str)}))}
   {:path     "/account/:id"
    :method   :get
    :response (fn [{:keys [params]}]
                {:status 200
                 :body (-> (:id params)
                           account-number->bank-account
                           json/write-str)})}
   {:path     "/account/:id/deposit"
    :method   :post
    :response (fn [{:keys [body params]}]
                (let [amount (:amount (json/read-json (slurp body)))
                      action  {:type :deposit
                               :amount amount
                               :to (:id params)}]
                  (bank-employee-instructions node action)
                  {:status 200
                   :body (-> (:id params)
                             account-number->bank-account
                             json/write-str)}))}
   {:path     "/account/:id/withdraw"
    :method   :post
    :response (fn [{:keys [body params]}]
                (let [amount  (:amount (json/read-json (slurp body)))
                      action  {:type :withdraw
                               :amount amount
                               :from (:id params)}]
                  (bank-employee-instructions node action)
                  {:status 200
                   :body (-> (:id params)
                             account-number->bank-account
                             json/write-str)}))}
   {:path     "/account/:id/send"
    :method   :post
    :response (fn [{:keys [body params]}]
                (let [{:keys [amount account-number]}
                      (json/read-json (slurp body))
                      action  {:type   :transfer
                               :amount amount
                               :from  (:id params)
                               :to    account-number}]
                  (bank-employee-instructions node action)
                  {:status 200
                   :body (-> (:id params)
                             account-number->bank-account
                             json/write-str)}))}
   {:path     "/account/:id/audit"
    :method   :post
    :response (fn [{:keys [body params]}]
                {:status 200
                 :body (-> (audit-log-for-account-id node (:id params))
                           json/write-str)})}])


(defonce web-server
  (http/run-server #(ruuter/route routes %) {:port 8080}))

(require '[org.httpkit.client :as http-client])


(comment "some rough testing the api directly"

         (defonce created-account-number
           (-> "http://localhost:8080/account"
               (http-client/post  {:body (json/write-str {:name "Mr. White"})})
               deref
               :body
               slurp
               json/read-json
               :account-number))

         (-> (str "http://localhost:8080/account/" created-account-number)
             (http-client/get)
             deref
             :body
             slurp
             json/read-json)

         (-> (str "http://localhost:8080/account/"
                  created-account-number
                  "/deposit")
             (http-client/post {:body (json/write-str {:amount 100})})
             deref
             :body
             slurp
             json/read-json)

         (-> (str "http://localhost:8080/account/"
                  created-account-number
                  "/withdraw")
             (http-client/post {:body (json/write-str {:amount 20})})
             deref
             :body
             slurp
             json/read-json)

         (defonce other-account-number
           (-> "http://localhost:8080/account"
               (http-client/post  {:body (json/write-str {:name "Mr. Red"})})
               deref
               :body
               slurp
               json/read-json
               :account-number))

         (-> (str "http://localhost:8080/account/"
                  created-account-number
                  "/send")
             (http-client/post {:body (json/write-str {:amount 30
                                                       :account-number other-account-number})})
             deref
             )

         (-> (str "http://localhost:8080/account/"
                  other-account-number
                  "/send")
             (http-client/post {:body (json/write-str {:amount 15
                                                       :account-number created-account-number})})
             deref

             )

         (-> (str "http://localhost:8080/account/"
                  created-account-number
                  "/audit")
             (http-client/post {:body (json/write-str {})})
             deref
             :body
             slurp
             json/read-json)

         "pmap is not a perfect async map, but completes fast enough to say it the api can handle 1000 concurrent requests"

         (with-out-str (time (doall (pmap (fn [i]
                                            (-> "http://localhost:8080/account"
                                                (http-client/post  {:body (json/write-str {:name (str "Mr. White " i)})})
                                                deref
                                                :body
                                                slurp
                                                json/read-json
                                                :account-number))
                                          (range 1000)))))
         "\"Elapsed time: 1981.191208 msecs\"\n")
