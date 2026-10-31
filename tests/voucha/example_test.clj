(ns voucha.example-test
  "The SEPA instant payment example, end to end (design 67).

  A file of batches of transactions is archived to a receive file, each batch is
  checked against its control sum, its transactions validated and screened, and
  each transaction paid by an attempt: debit the debtor account, send over one of
  two rails -- SCT Inst (asynchronous, resolved by a callback) or SCT
  (synchronous) -- and reverse the debit if the rail rejects. A status report is
  written at the end. Every rule the handlers lean on lives in the artifact."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [voucha.compiler :as compiler]
            [voucha.db :as db]
            [voucha.dispatcher :as dispatcher]
            [voucha.example.batch :as app]
            [voucha.fake :as fake]
            [voucha.kernel :as kernel]
            [voucha.profiles.file-batch-transaction :as fbt]
            [voucha.repo.artifact :as artifact]
            [voucha.repo.node :as node]
            [voucha.runtime :as runtime]
            [voucha.test-db :as tdb :refer [*ds*]]))

(use-fixtures :once tdb/with-db)
(use-fixtures :each tdb/clean-db)

(def ^:private tree-id "FILE-1")

(defn- completed-outcome [] {:outcome :completed})

(defn- rt
  "A runtime whose rails all succeed. `overrides` replaces individual adapters --
  e.g. to make a rail reject."
  ([] (rt {}))
  ([overrides]
   (runtime/runtime
    {:handlers app/handlers
     :effect-adapters (merge {"screening.check" (fake/effect-adapter [(completed-outcome)])
                              "account.debit"   (fake/effect-adapter [(completed-outcome)])
                              "account.reverse" (fake/effect-adapter [(completed-outcome)])
                              ;; SCT Inst is async: the send is dispatched, the outcome
                              ;; arrives later as a callback (delivered by `drive!`).
                              "rail.inst.send"  (fake/effect-adapter [{:outcome :dispatched}])
                              "rail.sct.submit" (fake/effect-adapter [{:outcome :completed
                                                                      :external-request-id "SCT-1"}])}
                             (:effect-adapters overrides))
     :store-adapters {"file.writeReceive" (fake/store-adapter)
                      "file.writeResult"  (fake/store-adapter)}
     :config {:dispatcher {:batch-size 25 :backoff-ms 30 :max-attempts 3}
              :event {:max-attempts 3}}})))

(defn- new-tree! []
  (let [aid (db/in-tx [tx *ds*]
              (let [a (compiler/compile-definition fbt/definition)]
                (artifact/insert! tx a) (:artifact-id a)))]
    (db/in-tx [tx *ds*]
      (kernel/create-tree! tx {:tree-id tree-id :artifact-id aid :root-node-id "F1"
                               :root-node-type "File"
                               :profile-name fbt/profile-name :profile-version fbt/profile-version}))
    aid))

(defn- receive-file! [batches]
  (db/in-tx [tx *ds*]
    (kernel/submit-event! tx {:event-id "file-received" :tree-id tree-id :target-node-id "F1"
                              :event-type "FileReceived"
                              :payload {:name "reference-file" :batches batches}})))

(defn- state-of [nid] (:local-state (db/in-tx [tx *ds*] (node/fetch tx tree-id nid))))

(defn- deliver-inst-callbacks!
  "The instant rail answers out of band: for every send that has been dispatched
  and is awaiting a response, deliver the rail's callback."
  [ds outcome]
  (let [pending (db/in-tx [tx ds]
                  (db/execute! tx ["SELECT effect_instance_id FROM effect_instance
                                    WHERE effect_name = 'rail.inst.send' AND status = 'DISPATCHED'"]))]
    (doseq [row pending]
      (dispatcher/deliver-callback! ds (assoc outcome :effect-instance-id (:effect_instance_id row)
                                              :payload {:rail-ref "INST-REF"})))
    (count pending)))

(defn- drive!
  "Run the world forward until the file is terminal: process events, run
  dispatchers, and deliver instant-rail callbacks, repeating until settled."
  ([ds runtime*] (drive! ds runtime* (completed-outcome)))
  ([ds runtime* inst-outcome]
   (loop [n 0]
     (when (> n 400) (throw (ex-info "runtime did not settle" {:steps n :file (state-of "F1")})))
     (kernel/drain! ds runtime*)
     (dispatcher/run-once! ds runtime*)
     (deliver-inst-callbacks! ds inst-outcome)
     (kernel/drain! ds runtime*)
     (let [s (state-of "F1")]
       (if (contains? #{"FILE_COMPLETED" "FILE_PERMANENT_FAILURE"} s)
         s
         (recur (inc n)))))))

(def ^:private two-rails
  [{:batch-id "B1"
    :transactions [{:tx-id "B1-T1" :amount 100 :payment-type "INST"}
                   {:tx-id "B1-T2" :amount 200 :payment-type "SCT"}]}])

(deftest a-file-is-paid-end-to-end-across-both-rails
  (new-tree!)
  (let [r (rt)]
    (receive-file! two-rails)
    (is (= "FILE_COMPLETED" (drive! *ds* r)) "the whole file finalized")

    (testing "every level of the hierarchy reached its successful terminal"
      (is (= "BATCH_COMPLETED" (state-of "B1")))
      (is (= "TX_COMPLETED" (state-of "B1-T1")) "the instant transaction paid")
      (is (= "TX_COMPLETED" (state-of "B1-T2")) "the SCT transaction paid")
      (is (= "ATTEMPT_SUCCEEDED" (state-of "B1-T1-A0")) "instant attempt succeeded via callback")
      (is (= "ATTEMPT_SUCCEEDED" (state-of "B1-T2-A0")) "SCT attempt succeeded synchronously"))))

(deftest a-batch-whose-control-sum-is-wrong-is-rejected-without-paying
  (new-tree!)
  (let [r (rt)]
    (receive-file! [(assoc (first two-rails) :control-sum 299 :count 2)])
    (is (= "FILE_COMPLETED" (drive! *ds* r)))
    (testing "the batch was rejected as a whole; no transactions were created"
      (is (= "BATCH_REJECTED" (state-of "B1")))
      (is (nil? (state-of "B1-T1")) "rejected before any transaction node existed"))))

(deftest a-rejected-payment-is-reversed-and-retried-with-a-new-attempt
  (new-tree!)
  (let [r (rt {:effect-adapters {"rail.sct.submit"
                                 (fake/effect-adapter [{:outcome :failed :payload {:error "AC04: account closed"}}
                                                       {:outcome :completed :external-request-id "SCT-2"}])}})]
    (receive-file! [{:batch-id "B1" :transactions [{:tx-id "B1-T1" :amount 100 :payment-type "SCT"}]}])
    (is (= "FILE_COMPLETED" (drive! *ds* r)))
    (is (= "ATTEMPT_FAILED" (state-of "B1-T1-A0")) "the first attempt was rejected by the rail")
    (is (= "ATTEMPT_SUCCEEDED" (state-of "B1-T1-A1")) "the retry paid")
    (is (= "TX_COMPLETED" (state-of "B1-T1")))
    (let [debits (fake/calls (get-in r [:effect-adapters "account.debit"]))
          reversals (fake/calls (get-in r [:effect-adapters "account.reverse"]))]
      (is (= 2 (count debits)) "each attempt debited once")
      (is (= 1 (count reversals)) "only the rejected attempt was reversed"))))

(deftest an-invalid-transaction-fails-while-its-siblings-pay
  (new-tree!)
  (let [r (rt)]
    (receive-file! [{:batch-id "B1"
                     :transactions [{:tx-id "B1-T1" :amount 100 :payment-type "SCT"}
                                    {:tx-id "B1-T2" :amount 0 :payment-type "SCT"}]}])
    (is (= "FILE_COMPLETED" (drive! *ds* r)))
    (is (= "TX_COMPLETED" (state-of "B1-T1")) "the valid transaction paid")
    (is (= "TX_FAILED" (state-of "B1-T2")) "the zero-amount transaction failed validation")
    (is (= "BATCH_COMPLETED" (state-of "B1")) "a batch completes even with a failed transaction")))

(deftest a-batch-whose-every-transaction-fails-still-completes
  ;; Found by the simulator (D16): with nothing left to pay, no settle event would
  ;; ever arrive, and the batch -- then the file -- waited forever with every
  ;; invariant intact.
  (new-tree!)
  (let [r (rt)]
    (receive-file! [{:batch-id "B1" :transactions [{:tx-id "B1-T1" :amount 0 :payment-type "SCT"}
                                                   {:tx-id "B1-T2" :amount 0 :payment-type "INST"}]}])
    (is (= "FILE_COMPLETED" (drive! *ds* r)))
    (is (= "TX_FAILED" (state-of "B1-T1")))
    (is (= "TX_FAILED" (state-of "B1-T2")))
    (is (= "BATCH_COMPLETED" (state-of "B1")) "completed with nothing paid")))

(deftest an-empty-file-and-an-empty-batch-complete
  (new-tree!)
  (let [r (rt)]
    (receive-file! [{:batch-id "B1" :transactions []}])
    (is (= "FILE_COMPLETED" (drive! *ds* r)))
    (is (= "BATCH_COMPLETED" (state-of "B1"))))
  (tdb/clean-db
   (fn []
     (new-tree!)
     (let [r (rt)]
       (receive-file! [])
       (is (= "FILE_COMPLETED" (drive! *ds* r)) "a file with no batches has nothing to wait for")))))
