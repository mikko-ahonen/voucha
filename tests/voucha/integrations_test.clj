(ns voucha.integrations-test
  "The two reference integration styles, expressed on the SEPA instant payment example:

    screening.check   synchronous REST -- answers in one round trip
    rail.inst.send    a queue send -- answers later, on another channel, with
                      no request id to correlate on

  Between them these exercise every branch of the dispatcher's outcome handling
  from a real process rather than a unit test: :completed, :failed, :dispatched,
  :acknowledged, and an uncertain outcome resolved under each policy. A rail is a
  :query effect (never resent, only asked); sanctions screening is :retry-safe
  (resent under the same key when the outcome is unknown)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [voucha.compiler :as compiler]
            [voucha.db :as db]
            [voucha.dispatcher :as dispatcher]
            [voucha.example.batch :as app]
            [voucha.example.integrations :as ext]
            [voucha.fake :as fake]
            [voucha.kernel :as kernel]
            [voucha.profiles.file-batch-transaction :as fbt]
            [voucha.replay :as replay]
            [voucha.repo.artifact :as artifact]
            [voucha.repo.effect :as effect]
            [voucha.repo.node :as node]
            [voucha.runtime :as runtime]
            [voucha.test-db :as tdb :refer [*ds*]]))

(use-fixtures :once tdb/with-db)
(use-fixtures :each tdb/clean-db)

(def tree-id "T")

;; --- external systems ---------------------------------------------------------------

(defn- clear-screening [_req] {:status 200 :body {:result "clear" :reference "SCR-1"}})
(defn- refused-screening [_req] {:status 422 :body {:reason "malformed creditor identifier"}})

(defn- flaky-screening
  "503 on the first call, 200 afterwards. The first outcome is unknown: the
  request left, and the service may have acted on it."
  [calls]
  (fn [_req]
    (if (zero? (first (swap-vals! calls inc)))
      {:status 503 :body {}}
      {:status 200 :body {:result "clear" :reference "SCR-RETRY"}})))

(defn- inst-response
  "The rail's consumer answers a send with the reference it minted."
  [body] {:outcome :completed :payload {:rail-ref (str "INST-REF-" (:attempt body))}})

(defn- inst-reject [_body] {:outcome :failed :payload {:error "AC04: account closed"}})

;; --- runtime -------------------------------------------------------------------------

(defn- completed [] {:outcome :completed})

(defn- rt
  [{:keys [screening broker]}]
  (runtime/runtime
   {:handlers app/handlers
    :effect-adapters (cond-> {"screening.check" (fake/effect-adapter [(completed)])
                              "account.debit"   (fake/effect-adapter [(completed)])
                              "account.reverse" (fake/effect-adapter [(completed)])
                              "rail.inst.send"  (fake/effect-adapter [{:outcome :dispatched}])
                              "rail.sct.submit" (fake/effect-adapter [(completed)])}
                       screening (assoc "screening.check" screening)
                       broker    (assoc "rail.inst.send" (ext/broker-adapter broker)))
    :store-adapters {"file.writeReceive" (fake/store-adapter)
                     "file.writeResult"  (fake/store-adapter)}
    :config {:dispatcher {:batch-size 10 :backoff-ms 40 :max-attempts 3}}}))

(defn- install! []
  (db/in-tx [tx *ds*]
    (let [a (compiler/compile-definition fbt/definition)]
      (artifact/insert! tx a) (:artifact-id a))))

(defn- new-tree! []
  (let [aid (install!)]
    (db/in-tx [tx *ds*]
      (kernel/create-tree! tx {:tree-id tree-id :artifact-id aid :root-node-id "F1"
                               :root-node-type "File"
                               :profile-name fbt/profile-name :profile-version fbt/profile-version}))))

(defn- receive-file! [batches]
  (db/in-tx [tx *ds*]
    (kernel/submit-event! tx {:event-id (str (gensym "fr")) :tree-id tree-id :target-node-id "F1"
                              :event-type "FileReceived" :payload {:name "p.csv" :batches batches}})))

(defn- state-of [node-id]
  (:local-state (db/in-tx [tx *ds*] (node/fetch tx tree-id node-id))))

(defn- data-of [node-id]
  (:local-data (db/in-tx [tx *ds*] (node/fetch tx tree-id node-id))))

(defn- effect-of [node-id nm]
  (first (filter #(= nm (:effect-name %))
                 (db/in-tx [tx *ds*] (effect/by-owner tx tree-id node-id)))))

(defn- effects-of [node-id nm]
  (filter #(= nm (:effect-name %))
          (db/in-tx [tx *ds*] (effect/by-owner tx tree-id node-id))))

(defn- a-transaction-in-screening!
  "Drive TX1 to TX_SCREENING with a screening.check intent created but not yet
  dispatched. The receive-file write is completed by the dispatcher, so the
  screening under test is the only thing left outstanding."
  [r]
  (new-tree!)
  (receive-file! [{:batch-id "B1" :transactions [{:tx-id "TX1" :amount 100 :payment-type "INST"}]}])
  (kernel/drain! *ds* r)          ; F1 -> WRITING_RECEIVE_FILE
  (dispatcher/run-once! *ds* r)   ; receive-file write commits
  (kernel/drain! *ds* r))         ; F1 -> FILE_PROCESS_BATCH, B1 validates and prepares, TX1 -> TX_SCREENING

(defn- an-inst-attempt!
  "Drive an instant transaction down to an Attempt whose rail.inst.send intent is
  created but not yet dispatched. Retry-safe effects along the way -- the store
  write, screening, the account debit -- are completed by the dispatcher; the
  loop stops the instant the attempt is ready to send, before the send leaves."
  [r]
  (new-tree!)
  (receive-file! [{:batch-id "B1" :transactions [{:tx-id "TX1" :amount 100 :payment-type "INST"}]}])
  (loop [i 0]
    (kernel/drain! *ds* r)
    (cond
      (= "ATTEMPT_INST_SENDING" (state-of "TX1-A0")) :ready
      (> i 30) (throw (ex-info "did not reach the instant attempt" {:file (state-of "F1")}))
      :else (do (dispatcher/run-once! *ds* r) (recur (inc i))))))

;; --- REST: synchronous request/response ---------------------------------------------------

(deftest a-rest-effect-completes-in-one-round-trip
  (let [screening (ext/rest-service clear-screening)
        r (rt {:screening screening})]
    (a-transaction-in-screening! r)
    (is (= "TX_SCREENING" (state-of "TX1")))
    (let [e (effect-of "TX1" "screening.check")]
      (is (= "CREATED" (:status e)) "the transition created an intent, not a call")
      (is (empty? (ext/rest-calls screening)) "no HTTP happened inside the transaction"))

    (dispatcher/run-once! *ds* r)
    (testing "a synchronous endpoint goes straight to COMPLETED: there is no ack state"
      (is (= "COMPLETED" (:status (effect-of "TX1" "screening.check"))))
      (is (= 1 (count (ext/rest-calls screening)))))

    (kernel/drain! *ds* r)
    (is (= "TX_PAYING" (state-of "TX1")) "the cleared transaction advanced to payment")
    (testing "the endpoint's answer is durably recorded on the effect"
      (let [e (effect-of "TX1" "screening.check")]
        (is (= "clear" (:result (:completion-payload e))))
        (is (= "SCR-1" (:reference (:completion-payload e))))))))

(deftest a-rest-4xx-is-a-definite-failure
  ;; The service understood the request and refused it. That is an answer, and it
  ;; drives the node through its declared failure event -- here into the
  ;; transaction's retry cycle, which issues a fresh screening intent.
  (let [screening (ext/rest-service refused-screening)
        r (rt {:screening screening})]
    (a-transaction-in-screening! r)
    (dispatcher/run-once! *ds* r)
    (is (= "FAILED" (:status (effect-of "TX1" "screening.check"))) "not DISPATCH_FAILED: the answer was definite")
    (kernel/drain! *ds* r)
    (is (= "TX_SCREENING" (state-of "TX1")) "the refusal drove TransactionScreeningFailed, and a new cycle began")
    (is (= 1 (:screening-retries (data-of "TX1"))))
    (is (= #{"FAILED" "CREATED"} (set (map :status (effects-of "TX1" "screening.check"))))
        "the refused intent stays on the record; the retry is a distinct one")))

(deftest a-rest-5xx-is-an-unknown-outcome-not-a-failure
  ;; The request left our process. Whether the service acted on it is unknown.
  ;; screening.check is :retry-safe, so it is sent again under the same key.
  (let [calls (atom 0)
        screening (ext/rest-service (flaky-screening calls))
        r (rt {:screening screening})]
    (a-transaction-in-screening! r)

    (dispatcher/run-once! *ds* r)
    (let [e (effect-of "TX1" "screening.check")]
      (is (= "DISPATCH_FAILED" (:status e)) "not FAILED: nobody knows what happened")
      (is (= 1 (:retry-count e)))
      (is (= "TX_SCREENING" (state-of "TX1")) "the transaction waits, it does not fail"))

    (Thread/sleep 100)
    (dispatcher/run-once! *ds* r)
    (kernel/drain! *ds* r)
    (is (= "COMPLETED" (:status (effect-of "TX1" "screening.check"))))
    (is (= "TX_PAYING" (state-of "TX1")) "the cleared transaction advanced to payment")
    (testing "both sends carried the same idempotency key"
      (let [keys* (map :idempotency-key (ext/rest-calls screening))]
        (is (= 2 (count keys*)))
        (is (apply = keys*))))))

;; --- queue: send now, answer later ----------------------------------------------------------

(deftest a-queue-effect-rests-in-dispatched-until-the-consumer-answers
  (let [broker (ext/message-broker)
        r (rt {:broker broker})]
    (an-inst-attempt! r)

    (testing "the transaction delegates to an Attempt on the instant rail"
      (is (= "TX_PAYING" (state-of "TX1")))
      (is (= "ATTEMPT_INST_SENDING" (state-of "TX1-A0"))))

    (testing "the send returns only the fact that the message left"
      (dispatcher/run-once! *ds* r)
      (is (= 1 (ext/depth broker)) "the message is on the queue")
      (let [e (effect-of "TX1-A0" "rail.inst.send")]
        (is (= "DISPATCHED" (:status e)))
        (is (nil? (:external-request-id e))
            "a queue send returns no request id -- correlation must not depend on one"))
      (kernel/drain! *ds* r)
      (is (= "ATTEMPT_INST_WAITING" (state-of "TX1-A0"))
          "InstPaymentSent moved the attempt to waiting"))

    (testing "the consumer acknowledges receipt, then publishes the result"
      (ext/drain! *ds* broker inst-response)
      (let [e (effect-of "TX1-A0" "rail.inst.send")]
        (is (= "COMPLETED" (:status e)))
        (is (= "ledger-poster" (:consumer (:ack-payload e)))
            "the acknowledgement landed on the effect on its way through"))
      (kernel/drain! *ds* r)
      (is (= "ATTEMPT_SUCCEEDED" (state-of "TX1-A0")))
      (is (= "INST-REF-TX1-A0" (:rail-ref (data-of "TX1-A0")))
          "the completion payload landed in node state"))

    (testing "completion aggregates upward: attempt -> transaction -> batch"
      (is (= "TX_COMPLETED" (state-of "TX1")))
      (is (= 1 (get-in (data-of "B1") [:counters :tx-paid]))))))

(deftest a-queue-effect-is-correlated-without-a-request-id
  ;; The consumer knows only the key that travelled with the message.
  (let [broker (ext/message-broker)
        r (rt {:broker broker})]
    (an-inst-attempt! r)
    (dispatcher/run-once! *ds* r)
    (kernel/drain! *ds* r)

    (let [msg (first (:inbound @broker))]
      (is (= "TX1-A0" (:correlation-key msg)) "the attempt node's id travelled with it")
      (is (= "rail.inst.send" (:effect-name msg))))

    (testing "delivering by correlation key alone resolves the effect"
      (dispatcher/deliver-callback! *ds* {:tree-id tree-id
                                          :correlation-key "TX1-A0"
                                          :effect-name "rail.inst.send"
                                          :outcome :acknowledged
                                          :payload {:consumer "c1"}})
      (is (= "ACKNOWLEDGED" (:status (effect-of "TX1-A0" "rail.inst.send")))))

    (testing "an unknown correlation key is refused rather than guessed"
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"could not be correlated"
           (dispatcher/deliver-callback! *ds* {:tree-id tree-id
                                               :correlation-key "nobody"
                                               :effect-name "rail.inst.send"
                                               :outcome :completed}))))

    (testing "a second completion for an already-terminal effect is refused"
      (dispatcher/deliver-callback! *ds* {:tree-id tree-id :correlation-key "TX1-A0"
                                          :effect-name "rail.inst.send"
                                          :outcome :completed :payload {:rail-ref "R1"}})
      (is (thrown-with-msg?
           clojure.lang.ExceptionInfo #"already terminal"
           (dispatcher/deliver-callback! *ds* {:tree-id tree-id :correlation-key "TX1-A0"
                                               :effect-name "rail.inst.send"
                                               :outcome :completed :payload {:rail-ref "R1"}}))))))

(deftest the-broker-deduplicates-a-resent-message
  ;; An uncertain queue send is retried under the same idempotency key, which is
  ;; the message id. The broker must not enqueue it twice.
  (let [broker (ext/message-broker)
        adapter (ext/broker-adapter broker)
        r (assoc-in (rt {:broker broker}) [:effect-adapters "rail.inst.send"] adapter)]
    (an-inst-attempt! r)
    (dispatcher/run-once! *ds* r)
    (is (= 1 (ext/depth broker)))

    ;; Simulate a dispatcher that crashed after sending: the effect goes back to
    ;; CREATED and is claimed again.
    (db/in-tx [tx *ds*]
      (db/execute-one! tx ["UPDATE effect_instance SET status = 'CREATED'
                             WHERE effect_name = 'rail.inst.send'"]))
    (dispatcher/run-once! *ds* r)

    (is (= 2 (count (ext/broker-calls adapter))) "the adapter was called twice")
    (is (= 1 (ext/depth broker)) "but the broker holds one message")))

(deftest a-queue-consumer-failure-fails-the-attempt
  (let [broker (ext/message-broker)
        r (rt {:broker broker})]
    (an-inst-attempt! r)
    (dispatcher/run-once! *ds* r)
    (kernel/drain! *ds* r)

    (ext/drain! *ds* broker inst-reject)
    (kernel/drain! *ds* r)

    (is (= "FAILED" (:status (effect-of "TX1-A0" "rail.inst.send"))))
    (is (= "ATTEMPT_REVERSING" (state-of "TX1-A0"))
        "design 17 -- the attempt reverses the debit it already made")
    (is (= "TX_PAYING" (state-of "TX1")) "and the transaction has not moved")

    (testing "once reversed, the attempt fails and the transaction retries with a new one"
      (dispatcher/run-once! *ds* r)   ; account.reverse completes
      (kernel/drain! *ds* r)
      (is (= "ATTEMPT_FAILED" (state-of "TX1-A0")))
      (is (= "TX_PAYING" (state-of "TX1")) "the transaction does not fail on one rejected attempt")
      (is (= "ATTEMPT_DEBITING" (state-of "TX1-A1")) "a second attempt is under way"))))

;; --- a live queue processor ---------------------------------------------------------------

(deftest a-background-queue-processor-drives-the-tree-to-completion
  ;; The processor runs on its own thread and speaks to the kernel only through
  ;; deliver-callback!, exactly as an out-of-process consumer would.
  (let [broker (ext/message-broker)
        r (rt {:broker broker})
        stop (ext/start-processor! *ds* broker inst-response {:poll-interval-ms 10})]
    (try
      (an-inst-attempt! r)
      (let [deadline (+ (System/currentTimeMillis) 5000)]
        (loop []
          (dispatcher/run-once! *ds* r)
          (kernel/drain! *ds* r)
          (when (and (not= "FILE_COMPLETED" (state-of "F1"))
                     (< (System/currentTimeMillis) deadline))
            (Thread/sleep 20)
            (recur))))
      (is (= "FILE_COMPLETED" (state-of "F1")))
      (is (zero? (ext/depth broker)) "the queue drained")
      (is (= "COMPLETED" (:status (effect-of "TX1-A0" "rail.inst.send"))))
      (finally (stop)))))

;; --- both integrations, one transaction ------------------------------------------------------

(deftest screening-then-queue-posting-end-to-end
  (let [broker (ext/message-broker)
        screening (ext/rest-service clear-screening)
        r (rt {:screening screening :broker broker})]
    (new-tree!)
    (receive-file! [{:batch-id "B1" :transactions [{:tx-id "TX1" :amount 100 :payment-type "INST"}]}])

    ;; One transaction, screened over REST and paid over the queue rail, driven to
    ;; the file's terminal state. The dispatcher runs the synchronous effects; the
    ;; queue consumer answers the rail send out of band.
    (let [deadline (+ (System/currentTimeMillis) 5000)]
      (loop []
        (dispatcher/run-once! *ds* r)
        (kernel/drain! *ds* r)
        (ext/drain! *ds* broker inst-response)
        (kernel/drain! *ds* r)
        (when (and (not= "FILE_COMPLETED" (state-of "F1"))
                   (< (System/currentTimeMillis) deadline))
          (Thread/sleep 10)
          (recur))))

    (is (= "TX_COMPLETED" (state-of "TX1")))
    (is (= "BATCH_COMPLETED" (state-of "B1")))
    (is (= "FILE_COMPLETED" (state-of "F1")))
    (testing "the screening REST call happened exactly once"
      (is (= 1 (count (ext/rest-calls screening)))))

    (testing "two integration styles, one tree, and every handler replays"
      (let [result (db/in-tx [tx *ds*] (replay/verify-deterministic tx tree-id app/handlers))]
        (is (:ok? result) (pr-str (:divergences result)))))))
