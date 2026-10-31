(ns voucha.profiles.file-batch-transaction
  "The SEPA instant payment example -- the reference process profile (design 11):
  a credit-transfer file, as a debtor bank processes one. Every term is public -- ISO 20022 pain.001/pain.002,
  the EPC SEPA Credit Transfer and SCT Instant rulebooks -- and nothing here
  describes any particular institution's process.

      File -> Batch -> Transaction -> Attempt

  A File (a pain.001 message) is archived to a receive file, then each Batch (a
  payment-information block: one debtor account, one execution date) is checked
  against its control sum, its Transactions validated and screened against the
  sanctions list, and each Transaction paid by an Attempt: debit the debtor
  account, send over one of two rails, and reverse the debit if the rail rejects
  the payment.

    * SCT Inst -- the instant scheme: an asynchronous send whose answer arrives
                  later as a callback (a queued effect whose outcome is delivered
                  on a separate channel). A send with no answer is resolved by a
                  status inquiry, never by sending again.
    * SCT      -- the standard scheme: a synchronous submission accepted or
                  rejected in one round trip.

  Both rail effects use the :query uncertainty policy (design 29): a payment whose
  outcome is unknown is asked about, because a blind resend could pay twice. The
  account debit and its reversal are retry-safe under an idempotency key.

  This is a profile, not kernel logic. Nothing in the kernel knows what a rail is;
  it reads these node types, states, effects, counters and constraints out of a
  compiled artifact (design 12).

  Orchestration idioms:

    * A parent that creates children records their ids in `:children`, so it can
      fan a downward command out to each (`command-each` in the handlers).
    * A child bubbles a single settle event upward per milestone (design 31); the
      parent aggregates with a counter and `:on-unsatisfied :ignore`, so a
      not-yet-complete parent simply waits rather than rejecting.
    * A worker-owned step is an effect: its completion or failure event drives the
      next transition. A platform-owned step is an internal self-emit or an
      aggregate.
    * 'Are all the children done?' is a decision state with auto transitions
      over the kernel's counters, not a check in handler code. Entered with
      nothing to wait for, it moves on at once; otherwise it waits in the state
      that hosts the children, and every child's settle event brings it back to
      decide again."
  (:require [voucha.profile :as profile]))

(def profile-name "file-batch-transaction")
(def profile-version "v3")

(def events
  #{;; --- File ----------------------------------------------------------------
    "FileReceived" "ReceiveFileWritten" "ReceiveFileWriteFailed"
    "BatchSettled" "AllBatchesProcessed" "BatchesPending" "ResultFileWritten" "ResultFileWriteFailed"
    ;; --- Batch ---------------------------------------------------------------
    "BatchStartCommanded" "BatchValidated" "BatchRejected"
    "TransactionReady" "TransactionRejected" "TransactionPaid" "TransactionFailedSettled"
    "AllTransactionsScreened" "ScreeningPending" "AllTransactionsSettled" "PaymentsPending"
    ;; --- Transaction ---------------------------------------------------------
    "TransactionStartCommanded" "TransactionValidated" "TransactionValidationFailed"
    "TransactionScreeningStart" "TransactionScreeningCleared" "TransactionScreeningFailed"
    "TransactionScreeningRetry" "TransactionScreeningGaveUp" "TransactionCancelled"
    "TransactionPayCommanded" "AttemptSucceeded" "AttemptFailed"
    "TransactionRetry" "TransactionGaveUp"
    ;; --- Attempt -------------------------------------------------------------
    "AttemptCommanded" "AccountDebited" "AccountDebitFailed"
    "AttemptSendInst" "AttemptSubmitSct"
    "InstPaymentSent" "InstPaymentAccepted" "InstPaymentRejected"
    "SctPaymentAccepted" "SctPaymentRejected"
    "DebitReversed" "DebitReversalFailed"})

(def effects
  {;; --- Sanctions screening, per transaction -------------------------------
   ;; A transient failure of the screening service is retried as a new cycle, so
   ;; `:attempt` is part of the key: the same node re-issuing the effect must get
   ;; a new intent, not the old failed one (design 25).
   "screening.check"
   {:version "v1" :kind :external
    :idempotency-key [:tree-id :node-id :attempt] :correlation-key :node-id
    :uncertainty-policy :retry-safe
    :completion-event "TransactionScreeningCleared"
    :failure-event "TransactionScreeningFailed"}

   ;; --- Debtor account, per attempt ----------------------------------------
   ;; Debit before sending; the attempt id already names the retry cycle.
   "account.debit"
   {:version "v1" :kind :external
    :idempotency-key [:tree-id :node-id] :correlation-key :node-id
    :uncertainty-policy :retry-safe
    :completion-event "AccountDebited"
    :failure-event "AccountDebitFailed"}

   ;; The compensating reversal when the rail rejects a payment already debited.
   ;; Idempotent, so a transient failure is safe to retry.
   "account.reverse"
   {:version "v1" :kind :external
    :idempotency-key [:tree-id :node-id] :correlation-key :node-id
    :uncertainty-policy :retry-safe
    :completion-event "DebitReversed"
    :failure-event "DebitReversalFailed"}

   ;; --- Rails --------------------------------------------------------------
   ;; SCT Inst: asynchronous. The send leaves and the scheme's answer returns
   ;; later as a callback correlated by the attempt's key. An uncertain send is
   ;; resolved by a status inquiry, never by resending a payment (design 29).
   "rail.inst.send"
   {:version "v1" :kind :external
    :idempotency-key [:tree-id :node-id] :correlation-key :node-id
    :uncertainty-policy :query :status-query "rail.inst.status"
    :dispatch-event "InstPaymentSent"
    :completion-event "InstPaymentAccepted"
    :failure-event "InstPaymentRejected"}

   ;; SCT: synchronous submission. One round trip yields accepted or rejected; an
   ;; uncertain outcome is resolved by querying, because a blind resend could
   ;; pay twice.
   "rail.sct.submit"
   {:version "v1" :kind :external
    :idempotency-key [:tree-id :node-id] :correlation-key :node-id
    :uncertainty-policy :query :status-query "rail.sct.status"
    :completion-event "SctPaymentAccepted"
    :failure-event "SctPaymentRejected"}

   ;; --- File I/O (store effects) -------------------------------------------
   "file.writeReceive"
   {:version "v1" :kind :store :adapter "file-store"
    :idempotency-key [:tree-id :node-id] :correlation-key :node-id
    :uncertainty-policy :retry-safe
    :completion-event "ReceiveFileWritten"
    :failure-event "ReceiveFileWriteFailed"}

   "file.writeResult"
   {:version "v1" :kind :store :adapter "file-store"
    :idempotency-key [:tree-id :node-id] :correlation-key :node-id
    :uncertainty-policy :retry-safe
    :completion-event "ResultFileWritten"
    :failure-event "ResultFileWriteFailed"}})

;; A transaction may pay only while its batch is processing, and an attempt may
;; run only while its transaction is paying -- the constraints that stop a late
;; command or callback from moving a node under a parent that has not started
;; or has finished (design 16).
(def ^:private under-processing-batch
  {:constraint :parent-state :allowed ["BATCH_SCREEN_TRANSACTIONS" "BATCH_PROCESSING" "BATCH_PAY_TRANSACTIONS"]})
(def ^:private under-paying-transaction {:constraint :parent-state :allowed ["TX_PAYING"]})

;; The shapes of the data the handlers read and write, for the typed
;; specification (`voucha prove --inductive`): a field is :int, :str, :bool,
;; [:set record] or [:maybe T] when a handler asks whether it is absent.
(def ^:private transaction-record {:tx-id :str :amount :int :payment-type :str})
(def ^:private batch-record {:batch-id :str :control-sum [:maybe :int] :count [:maybe :int]
                             :transactions [:set transaction-record]})

(def payloads
  {"FileReceived" {:name :str :batches [:set batch-record]}
   "BatchStartCommanded" {:transactions [:set transaction-record] :control-sum [:maybe :int] :count [:maybe :int]}
   "TransactionStartCommanded" transaction-record
   "AttemptCommanded" {:payment-type :str :amount :int}
   "TransactionCancelled" {:reason :str}
   "TransactionValidationFailed" {:reason :str}
   "ReceiveFileWriteFailed" {:error :str}
   "ResultFileWriteFailed" {:error :str}
   "AccountDebitFailed" {:error :str}
   "InstPaymentRejected" {:error :str}
   "SctPaymentRejected" {:error :str}
   "InstPaymentAccepted" {:rail-ref :str}
   "SctPaymentAccepted" {:rail-ref :str}
   "DebitReversalFailed" {:error :str}})

(def node-types
  {"File"
   {:may-contain ["Batch"]
    :initial-state "FILE_NEW"
    :data {:batches [:set batch-record] :children [:set :str] :error :str}
    ;; FILE_PROCESS_BATCHES is the decision: all batches processed, or some still
    ;; pending? Its auto transitions choose, from the counters, between
    ;; FILE_BATCHES_PROCESSED and FILE_PROCESS_BATCH, where the batches run and
    ;; whose every settle event brings the file back to decide again.
    :states ["FILE_NEW" "WRITING_RECEIVE_FILE" "FILE_PROCESS_BATCHES" "FILE_PROCESS_BATCH"
             "FILE_BATCHES_PROCESSED" "FILE_COMPLETED" "FILE_PERMANENT_FAILURE"]
    :terminal-states ["FILE_COMPLETED" "FILE_PERMANENT_FAILURE"]
    :failure-state "FILE_PERMANENT_FAILURE"
    :may-emit-effects ["file.writeReceive" "file.writeResult"]
    :hosts {"Batch" "FILE_PROCESS_BATCH"}
    :counters {:batches-completed {:child-type "Batch" :when-child-enters ["BATCH_COMPLETED"]}
               :batches-rejected  {:child-type "Batch" :when-child-enters ["BATCH_REJECTED"]}}
    :child-states-allowed {"FILE_COMPLETED" {"Batch" ["BATCH_COMPLETED" "BATCH_REJECTED"]}}}

   "Batch"
   {:may-contain ["Transaction"]
    :initial-state "BATCH_NEW"
    :data {:transactions [:set transaction-record] :children [:set :str] :outcome :str :error :str
           :pay-commanded :bool :paid :int :failed :int}
    ;; Two phases, screen every transaction then pay the survivors, each a
    ;; decision state: an auto transition over the counters. BATCH_PROCESSING is
    ;; where the transactions live, in both phases; a transaction's settle event
    ;; names its phase and takes the batch to that phase's decision.
    :states ["BATCH_NEW" "BATCH_VALIDATING" "BATCH_SCREEN_TRANSACTIONS" "BATCH_PROCESSING"
             "BATCH_PAY_TRANSACTIONS" "BATCH_COMPLETED" "BATCH_REJECTED"]
    :terminal-states ["BATCH_COMPLETED" "BATCH_REJECTED"]
    :failure-state "BATCH_REJECTED"
    :may-emit-effects []
    :hosts {"Transaction" "BATCH_PROCESSING"}
    :counters {:tx-ready  {:child-type "Transaction" :when-child-enters ["TX_SCREENED"]}
               :tx-paid   {:child-type "Transaction" :when-child-enters ["TX_COMPLETED"]}
               :tx-failed {:child-type "Transaction" :when-child-enters ["TX_FAILED"]}}
    :child-states-allowed {"BATCH_COMPLETED" {"Transaction" ["TX_COMPLETED" "TX_FAILED"]}
                           "BATCH_REJECTED"  {"Transaction" []}}}

   "Transaction"
   {:may-contain ["Attempt"]
    :initial-state "TX_NEW"
    :data {:amount :int :payment-type :str :screening-retries :int :attempts :int :error :str}
    ;; Screening and paying each have a call state and a retry wait. The
    ;; attempts live in TX_PAYING, one per cycle.
    :states ["TX_NEW" "TX_VALIDATING" "TX_VALIDATED" "TX_SCREENING" "TX_SCREEN_RETRY_WAIT"
             "TX_SCREENED" "TX_PAYING" "TX_PAY_RETRY_WAIT" "TX_COMPLETED" "TX_FAILED"]
    :terminal-states ["TX_COMPLETED" "TX_FAILED"]
    :failure-state "TX_FAILED"
    :may-emit-effects ["screening.check"]
    :hosts {"Attempt" "TX_PAYING"}
    ;; The batch commands every transaction to pay; one that failed screening
    ;; ignores the command rather than refusing it.
    ;; A pay command to a transaction that failed screening is the batch's stale command; a
    ;; cancel that arrives once screening cleared is too late; a screening outcome that was
    ;; already on its way when a cancel failed the transaction is moot: ignored, not rejected.
    :ignores {"TX_FAILED" ["TransactionPayCommanded" "TransactionCancelled"
                           "TransactionScreeningCleared" "TransactionScreeningFailed"
                           "TransactionScreeningRetry" "TransactionScreeningGaveUp"]
              "TX_SCREENED" ["TransactionCancelled"]
              "TX_PAYING" ["TransactionCancelled"]
              "TX_PAY_RETRY_WAIT" ["TransactionCancelled"]
              "TX_COMPLETED" ["TransactionCancelled"]}
    :child-states-allowed {"TX_COMPLETED" {"Attempt" ["ATTEMPT_SUCCEEDED" "ATTEMPT_FAILED"]}
                           "TX_FAILED"    {"Attempt" ["ATTEMPT_SUCCEEDED" "ATTEMPT_FAILED"]}}}

   "Attempt"
   {:may-contain []
    :data {:amount :int :payment-type :str :error :str :rail-ref :str :reversal-pending :bool}
    :may-emit-effects ["account.debit" "account.reverse" "rail.inst.send" "rail.sct.submit"]
    :initial-state "ATTEMPT_NEW"
    :states ["ATTEMPT_NEW" "ATTEMPT_DEBITING" "ATTEMPT_DEBITED"
             "ATTEMPT_INST_SENDING" "ATTEMPT_INST_WAITING" "ATTEMPT_SCT_SUBMITTING"
             "ATTEMPT_REVERSING" "ATTEMPT_SUCCEEDED" "ATTEMPT_FAILED"]
    :terminal-states ["ATTEMPT_SUCCEEDED" "ATTEMPT_FAILED"]
    :failure-state "ATTEMPT_FAILED"}})

(def transitions
  [;; ============================ File ==================================
   {:node-type "File" :from "FILE_NEW" :on "FileReceived" :to "WRITING_RECEIVE_FILE"
    :handler 'file/received
    :plan [[:set [:batches] [:payload :batches]]
           [:store "file.writeReceive" {:name [:payload :name] :batch-count [:count [:payload :batches]]}]
           [:await "ReceiveFileWritten"]]}
   {:node-type "File" :from "WRITING_RECEIVE_FILE" :on "ReceiveFileWritten" :to "FILE_PROCESS_BATCHES"
    :handler 'file/start-batches
    :plan [[:set [:children] [:map [:data :batches] :batch-id]]
           [:each :b [:data :batches]
            [:child [:get [:var :b] :batch-id] "Batch"]
            [:emit-to [:get [:var :b] :batch-id] "BatchStartCommanded"
             [:select [:var :b] [:transactions :control-sum :count]]]]]}
   {:node-type "File" :from "WRITING_RECEIVE_FILE" :on "ReceiveFileWriteFailed" :to "FILE_PERMANENT_FAILURE"
    :handler 'file/fail
    :plan [[:set [:error] [:or [:payload :error] "file I/O failed"]]]}
   ;; The decision, taken on entry: every batch completed or rejected, or not yet.
   ;; A file with no batches satisfies the rule at once and never waits.
   {:node-type "File" :from "FILE_PROCESS_BATCHES" :on "AllBatchesProcessed" :to "FILE_BATCHES_PROCESSED"
    :auto true
    :requires [{:constraint :counters-cover-children :counters [:batches-completed :batches-rejected]}]
    :handler 'file/write-result
    :plan [[:store "file.writeResult" {:completed [:counter :batches-completed]
                                       :rejected [:counter :batches-rejected]}]
           [:await "ResultFileWritten"]]}
   {:node-type "File" :from "FILE_PROCESS_BATCHES" :on "BatchesPending" :to "FILE_PROCESS_BATCH"
    :auto true}
   ;; A batch settling brings the file back to decide; one settling while the
   ;; file is already deciding makes it decide again.
   {:node-type "File" :from "FILE_PROCESS_BATCH" :on "BatchSettled" :to "FILE_PROCESS_BATCHES"}
   {:node-type "File" :from "FILE_PROCESS_BATCHES" :on "BatchSettled" :to "FILE_PROCESS_BATCHES"}
   {:node-type "File" :from "FILE_BATCHES_PROCESSED" :on "ResultFileWritten" :to "FILE_COMPLETED"
    :handler 'file/complete
    :plan []}
   {:node-type "File" :from "FILE_BATCHES_PROCESSED" :on "ResultFileWriteFailed" :to "FILE_PERMANENT_FAILURE"
    :handler 'file/fail
    :plan [[:set [:error] [:or [:payload :error] "file I/O failed"]]]}

   ;; ============================ Batch =================================
   ;; The control sum and transaction count must agree with the records; a batch
   ;; that fails this is rejected as a whole, before any transaction exists.
   {:node-type "Batch" :from "BATCH_NEW" :on "BatchStartCommanded" :to "BATCH_VALIDATING"
    :handler 'batch/start-validation
    :requires [{:constraint :ancestor-state :node-type "File" :forbidden ["FILE_PERMANENT_FAILURE"]}]
    :plan [[:set [:transactions] [:payload :transactions]]
           [:if [:and [:or [:nil? [:payload :control-sum]]
                           [:= [:payload :control-sum] [:sum [:payload :transactions] :amount]]]
                      [:or [:nil? [:payload :count]]
                           [:= [:payload :count] [:count [:payload :transactions]]]]]
            [[:emit-to :self "BatchValidated"]]
            [[:emit-to :self "BatchRejected"]]]]}
   {:node-type "Batch" :from "BATCH_VALIDATING" :on "BatchValidated" :to "BATCH_SCREEN_TRANSACTIONS"
    :handler 'batch/prepare
    :plan [[:set [:children] [:map [:data :transactions] :tx-id]]
           [:each :t [:data :transactions]
            [:child [:get [:var :t] :tx-id] "Transaction"]
            [:emit-to [:get [:var :t] :tx-id] "TransactionStartCommanded" [:var :t]]]]}
   {:node-type "Batch" :from "BATCH_VALIDATING" :on "BatchRejected" :to "BATCH_REJECTED"
    :handler 'batch/rejected
    :plan [[:set [:outcome] "rejected"]
           [:set [:error] "control sum or count does not match the records"]
           [:emit "BatchSettled"]]}
   ;; Phase 1, screening. Decision: every transaction screened or rejected? Then
   ;; command the survivors to pay; otherwise wait, and decide again on each one.
   {:node-type "Batch" :from "BATCH_SCREEN_TRANSACTIONS" :on "AllTransactionsScreened" :to "BATCH_PAY_TRANSACTIONS"
    :auto true
    :requires [{:constraint :counters-cover-children :counters [:tx-ready :tx-failed]}]
    :handler 'batch/start-paying
    :plan [[:when [:not [:data :pay-commanded]]
            [:set [:pay-commanded] true]
            [:each :id [:data :children] [:emit-to [:var :id] "TransactionPayCommanded"]]]]}
   {:node-type "Batch" :from "BATCH_SCREEN_TRANSACTIONS" :on "ScreeningPending" :to "BATCH_PROCESSING"
    :auto true}
   ;; Phase 2, paying. Decision: every transaction paid or failed? Then the batch
   ;; is complete -- at once, if nothing was left to pay.
   {:node-type "Batch" :from "BATCH_PAY_TRANSACTIONS" :on "AllTransactionsSettled" :to "BATCH_COMPLETED"
    :auto true
    :requires [{:constraint :counters-cover-children :counters [:tx-paid :tx-failed]}]
    :handler 'batch/completed
    :plan [[:set [:paid] [:counter :tx-paid]]
           [:set [:failed] [:counter :tx-failed]]
           [:emit "BatchSettled"]]}
   {:node-type "Batch" :from "BATCH_PAY_TRANSACTIONS" :on "PaymentsPending" :to "BATCH_PROCESSING"
    :auto true}
   ;; A transaction's settle event names its phase and takes the batch to that
   ;; phase's decision, from waiting or from deciding again. The decisions are
   ;; taken from the counters, so a screening event may arrive after the batch
   ;; has already decided to pay: it then decides to pay once more, which sends
   ;; nothing twice, and a paying batch merely decides again.
   {:node-type "Batch" :from "BATCH_PROCESSING" :on "TransactionReady" :to "BATCH_SCREEN_TRANSACTIONS"}
   {:node-type "Batch" :from "BATCH_PROCESSING" :on "TransactionRejected" :to "BATCH_SCREEN_TRANSACTIONS"}
   {:node-type "Batch" :from "BATCH_SCREEN_TRANSACTIONS" :on "TransactionReady" :to "BATCH_SCREEN_TRANSACTIONS"}
   {:node-type "Batch" :from "BATCH_SCREEN_TRANSACTIONS" :on "TransactionRejected" :to "BATCH_SCREEN_TRANSACTIONS"}
   {:node-type "Batch" :from "BATCH_PAY_TRANSACTIONS" :on "TransactionReady" :to "BATCH_PAY_TRANSACTIONS"}
   {:node-type "Batch" :from "BATCH_PAY_TRANSACTIONS" :on "TransactionRejected" :to "BATCH_PAY_TRANSACTIONS"}
   {:node-type "Batch" :from "BATCH_PROCESSING" :on "TransactionPaid" :to "BATCH_PAY_TRANSACTIONS"}
   {:node-type "Batch" :from "BATCH_PROCESSING" :on "TransactionFailedSettled" :to "BATCH_PAY_TRANSACTIONS"}
   {:node-type "Batch" :from "BATCH_PAY_TRANSACTIONS" :on "TransactionPaid" :to "BATCH_PAY_TRANSACTIONS"}
   {:node-type "Batch" :from "BATCH_PAY_TRANSACTIONS" :on "TransactionFailedSettled" :to "BATCH_PAY_TRANSACTIONS"}
   {:node-type "Batch" :from "BATCH_SCREEN_TRANSACTIONS" :on "TransactionPaid" :to "BATCH_PAY_TRANSACTIONS"}
   {:node-type "Batch" :from "BATCH_SCREEN_TRANSACTIONS" :on "TransactionFailedSettled" :to "BATCH_PAY_TRANSACTIONS"}

   ;; ========================= Transaction =============================
   {:node-type "Transaction" :from "TX_NEW" :on "TransactionStartCommanded" :to "TX_VALIDATING"
    :handler 'tx/start-validation
    :plan [[:set [:amount] [:payload :amount]]
           [:set [:payment-type] [:payload :payment-type]]
           [:if [:> [:payload :amount] 0]
            [[:emit-to :self "TransactionValidated"]]
            [[:emit-to :self "TransactionValidationFailed"]]]]}
   {:node-type "Transaction" :from "TX_VALIDATING" :on "TransactionValidated" :to "TX_VALIDATED"
    :handler 'tx/validated
    :plan [[:emit-to :self "TransactionScreeningStart"]]}
   ;; A transaction that fails before it is paid is rejected: the batch's
   ;; screening phase counts it, not the paying phase.
   {:node-type "Transaction" :from "TX_VALIDATING" :on "TransactionValidationFailed" :to "TX_FAILED"
    :handler 'tx/reject
    :plan [[:set [:error] [:or [:payload :reason] "validation or screening failed"]]
           [:emit "TransactionRejected"]]}
   {:node-type "Transaction" :from "TX_VALIDATED" :on "TransactionScreeningStart" :to "TX_SCREENING"
    :handler 'tx/start-screening
    :plan [[:call "screening.check" {:tx-id [:node-id] :amount [:data :amount]
                                      :attempt [:or [:data :screening-retries] 0]}]
           [:await "TransactionScreeningCleared"]]}
   {:node-type "Transaction" :from "TX_SCREENING" :on "TransactionScreeningCleared" :to "TX_SCREENED"
    :handler 'tx/screening-cleared
    :plan [[:emit "TransactionReady"]]}
   {:node-type "Transaction" :from "TX_SCREENING" :on "TransactionScreeningFailed" :to "TX_SCREEN_RETRY_WAIT"
    :handler 'tx/screening-decide
    :plan [[:if [:< [:or [:data :screening-retries] 0] 3]
            [[:inc [:screening-retries] 1] [:emit-to :self "TransactionScreeningRetry"]]
            [[:emit-to :self "TransactionScreeningGaveUp"]]]]}
   {:node-type "Transaction" :from "TX_SCREEN_RETRY_WAIT" :on "TransactionScreeningRetry" :to "TX_SCREENING"
    :handler 'tx/start-screening
    :plan [[:call "screening.check" {:tx-id [:node-id] :amount [:data :amount]
                                      :attempt [:or [:data :screening-retries] 0]}]
           [:await "TransactionScreeningCleared"]]}
   {:node-type "Transaction" :from "TX_SCREEN_RETRY_WAIT" :on "TransactionScreeningGaveUp" :to "TX_FAILED"
    :handler 'tx/reject
    :plan [[:set [:error] [:or [:payload :reason] "validation or screening failed"]]
           [:emit "TransactionRejected"]]}
   ;; An operator may cancel a transaction while it is being screened, whether a
   ;; check is in flight or it is waiting to retry.
   {:node-type "Transaction" :from "TX_SCREENING" :on "TransactionCancelled" :to "TX_FAILED"
    :handler 'tx/reject
    :plan [[:set [:error] [:or [:payload :reason] "validation or screening failed"]]
           [:emit "TransactionRejected"]]}
   {:node-type "Transaction" :from "TX_SCREEN_RETRY_WAIT" :on "TransactionCancelled" :to "TX_FAILED"
    :handler 'tx/reject
    :plan [[:set [:error] [:or [:payload :reason] "validation or screening failed"]]
           [:emit "TransactionRejected"]]}
   ;; Pay: one Attempt per cycle, living in TX_PAYING; a rejected attempt is
   ;; retried up to a bound.
   {:node-type "Transaction" :from "TX_SCREENED" :on "TransactionPayCommanded" :to "TX_PAYING"
    :handler 'tx/start-paying
    :requires [under-processing-batch]
    :plan [[:let {:aid [:str [:node-id] "-A" [:or [:data :attempts] 0]]}
            [:child [:var :aid] "Attempt"]
            [:emit-to [:var :aid] "AttemptCommanded" {:payment-type [:data :payment-type]
                                                      :amount [:data :amount]}]]]}

   {:node-type "Transaction" :from "TX_PAYING" :on "AttemptSucceeded" :to "TX_COMPLETED"
    :handler 'tx/paid
    :plan [[:emit "TransactionPaid"]]}
   {:node-type "Transaction" :from "TX_PAYING" :on "AttemptFailed" :to "TX_PAY_RETRY_WAIT"
    :handler 'tx/decide-retry
    :plan [[:if [:< [:or [:data :attempts] 0] 3]
            [[:inc [:attempts] 1] [:emit-to :self "TransactionRetry"]]
            [[:emit-to :self "TransactionGaveUp"]]]]}
   {:node-type "Transaction" :from "TX_PAY_RETRY_WAIT" :on "TransactionRetry" :to "TX_PAYING"
    :handler 'tx/start-paying
    :requires [under-processing-batch]
    :plan [[:let {:aid [:str [:node-id] "-A" [:or [:data :attempts] 0]]}
            [:child [:var :aid] "Attempt"]
            [:emit-to [:var :aid] "AttemptCommanded" {:payment-type [:data :payment-type]
                                                      :amount [:data :amount]}]]]}
   {:node-type "Transaction" :from "TX_PAY_RETRY_WAIT" :on "TransactionGaveUp" :to "TX_FAILED"
    :handler 'tx/fail
    :plan [[:set [:error] [:or [:payload :reason] "payment failed"]]
           [:emit "TransactionFailedSettled"]]}

   ;; =========================== Attempt ===============================
   ;; Debit the debtor account first; the rail is chosen once the debit holds.
   {:node-type "Attempt" :from "ATTEMPT_NEW" :on "AttemptCommanded" :to "ATTEMPT_DEBITING"
    :handler 'attempt/debit
    :requires [under-paying-transaction]
    :plan [[:set [:amount] [:payload :amount]]
           [:set [:payment-type] [:payload :payment-type]]
           [:call "account.debit" {:amount [:payload :amount] :attempt [:node-id]}]
           [:await "AccountDebited"]]}
   {:node-type "Attempt" :from "ATTEMPT_DEBITING" :on "AccountDebited" :to "ATTEMPT_DEBITED"
    :handler 'attempt/debited
    :plan [[:if [:= [:data :payment-type] "SCT"]
            [[:emit-to :self "AttemptSubmitSct"]]
            [[:emit-to :self "AttemptSendInst"]]]]}
   ;; Nothing was debited, so there is nothing to reverse.
   {:node-type "Attempt" :from "ATTEMPT_DEBITING" :on "AccountDebitFailed" :to "ATTEMPT_FAILED"
    :handler 'attempt/failed
    :plan [[:set [:error] [:or [:payload :error] [:data :error] "attempt failed"]]
           [:emit "AttemptFailed"]]}
   ;; SCT Inst: asynchronous send, then wait for the scheme's answer.
   {:node-type "Attempt" :from "ATTEMPT_DEBITED" :on "AttemptSendInst" :to "ATTEMPT_INST_SENDING"
    :handler 'attempt/send-inst
    :plan [[:call "rail.inst.send" {:amount [:data :amount] :attempt [:node-id]}]
           [:await "InstPaymentSent"]]}
   {:node-type "Attempt" :from "ATTEMPT_INST_SENDING" :on "InstPaymentSent" :to "ATTEMPT_INST_WAITING"
    :handler 'attempt/inst-sent
    :plan []}
   {:node-type "Attempt" :from "ATTEMPT_INST_WAITING" :on "InstPaymentAccepted" :to "ATTEMPT_SUCCEEDED"
    :handler 'attempt/succeeded
    :plan [[:set [:rail-ref] [:payload :rail-ref]]
           [:emit "AttemptSucceeded"]]}
   {:node-type "Attempt" :from "ATTEMPT_INST_WAITING" :on "InstPaymentRejected" :to "ATTEMPT_REVERSING"
    :handler 'attempt/reverse
    :plan [[:set [:error] [:or [:payload :error] "rail rejected the payment"]]
           [:call "account.reverse" {:amount [:data :amount] :attempt [:node-id]}]
           [:await "DebitReversed"]]}
   ;; SCT: synchronous submission.
   {:node-type "Attempt" :from "ATTEMPT_DEBITED" :on "AttemptSubmitSct" :to "ATTEMPT_SCT_SUBMITTING"
    :handler 'attempt/submit-sct
    :plan [[:call "rail.sct.submit" {:amount [:data :amount] :attempt [:node-id]}]
           [:await "SctPaymentAccepted"]]}
   {:node-type "Attempt" :from "ATTEMPT_SCT_SUBMITTING" :on "SctPaymentAccepted" :to "ATTEMPT_SUCCEEDED"
    :handler 'attempt/succeeded
    :plan [[:set [:rail-ref] [:payload :rail-ref]]
           [:emit "AttemptSucceeded"]]}
   {:node-type "Attempt" :from "ATTEMPT_SCT_SUBMITTING" :on "SctPaymentRejected" :to "ATTEMPT_REVERSING"
    :handler 'attempt/reverse
    :plan [[:set [:error] [:or [:payload :error] "rail rejected the payment"]]
           [:call "account.reverse" {:amount [:data :amount] :attempt [:node-id]}]
           [:await "DebitReversed"]]}
   ;; The reversal restores the account; a reversal that fails is recorded for
   ;; reconciliation out of band -- the attempt is over either way.
   {:node-type "Attempt" :from "ATTEMPT_REVERSING" :on "DebitReversed" :to "ATTEMPT_FAILED"
    :handler 'attempt/failed
    :plan [[:set [:error] [:or [:payload :error] [:data :error] "attempt failed"]]
           [:emit "AttemptFailed"]]}
   {:node-type "Attempt" :from "ATTEMPT_REVERSING" :on "DebitReversalFailed" :to "ATTEMPT_FAILED"
    :handler 'attempt/failed-unreversed
    :plan [[:set [:reversal-pending] true]
           [:emit "AttemptFailed"]]}])

(def routing
  [;; Transaction -> Batch
   {:event "TransactionReady" :from-node-type "Transaction" :to-node-type "Batch"
    :scope :upward :on-unsatisfied :ignore}
   {:event "TransactionPaid" :from-node-type "Transaction" :to-node-type "Batch"
    :scope :upward :on-unsatisfied :ignore}
   {:event "TransactionFailedSettled" :from-node-type "Transaction" :to-node-type "Batch"
    :scope :upward :on-unsatisfied :ignore}
   {:event "TransactionRejected" :from-node-type "Transaction" :to-node-type "Batch"
    :scope :upward :on-unsatisfied :ignore}
   ;; Attempt -> Transaction
   {:event "AttemptSucceeded" :from-node-type "Attempt" :to-node-type "Transaction"
    :scope :upward :on-unsatisfied :ignore}
   {:event "AttemptFailed" :from-node-type "Attempt" :to-node-type "Transaction"
    :scope :upward :on-unsatisfied :ignore}
   ;; Batch -> File
   {:event "BatchSettled" :from-node-type "Batch" :to-node-type "File"
    :scope :upward :on-unsatisfied :ignore}])

(def chain-views
  {"PaymentChain"
   {:node-type "Transaction"
    :steps [{:name "Validate"
             :active-states ["TX_NEW" "TX_VALIDATING"]
             :complete-states ["TX_VALIDATED" "TX_SCREENING" "TX_SCREEN_RETRY_WAIT" "TX_SCREENED"
                               "TX_PAYING" "TX_PAY_RETRY_WAIT" "TX_COMPLETED"]
             :failed-states ["TX_FAILED"]}
            {:name "Screen"
             :active-states ["TX_SCREENING" "TX_SCREEN_RETRY_WAIT"]
             :complete-states ["TX_SCREENED" "TX_PAYING" "TX_PAY_RETRY_WAIT" "TX_COMPLETED"]
             :failed-states ["TX_FAILED"]}
            {:name "Pay"
             :active-states ["TX_PAYING" "TX_PAY_RETRY_WAIT"]
             :complete-states ["TX_COMPLETED"]
             :failed-states ["TX_FAILED"]}]}

   ;; The batch's state does not tell its phase apart -- the transactions live in
   ;; BATCH_PROCESSING throughout -- so its view has one processing step whose
   ;; progress is the counters.
   "BatchChain"
   {:node-type "Batch"
    :steps [{:name "Validate"
             :active-states ["BATCH_NEW" "BATCH_VALIDATING"]
             :complete-states ["BATCH_SCREEN_TRANSACTIONS" "BATCH_PROCESSING" "BATCH_PAY_TRANSACTIONS" "BATCH_COMPLETED"]
             :failed-states ["BATCH_REJECTED"]}
            {:name "Process"
             :active-states ["BATCH_SCREEN_TRANSACTIONS" "BATCH_PROCESSING" "BATCH_PAY_TRANSACTIONS"]
             :complete-states ["BATCH_COMPLETED"]
             :progress-counters [:tx-ready :tx-paid :tx-failed]}]}})

(def predicates
  "Named questions the properties ask (docs/design/invariants.md)."
  {:debited? [:fn :a [:exists :e [:effects "account.debit" :owner [:var :a] :status ["COMPLETED"]]]]})

(def invariants
  "What must hold of every tree, beyond what the framework checks: stated
  once, checked by the simulator, the models, the proof and the runtime's
  oracle. All provable: over the tree and the effects, with quantifiers."
  {:one-success-per-transaction
   [:forall :tx [:nodes "Transaction"]
    [:at-most-one :a [:nodes "Attempt" :children-of [:var :tx] :in ["ATTEMPT_SUCCEEDED"]]]]

   :success-only-after-debit
   [:forall :a [:nodes "Attempt" :in ["ATTEMPT_SUCCEEDED"]] [:pred :debited? [:var :a]]]

   :reversal-only-after-debit
   [:forall :a [:nodes "Attempt" :in ["ATTEMPT_REVERSING" "ATTEMPT_FAILED"]]
    [:implies [:exists :e [:effects "account.reverse" :owner [:var :a]]]
     [:pred :debited? [:var :a]]]]

   :paid-means-one-success
   [:forall :tx [:nodes "Transaction" :in ["TX_COMPLETED"]]
    [:exactly-one :a [:nodes "Attempt" :children-of [:var :tx] :in ["ATTEMPT_SUCCEEDED"]]]]

   :batch-settled-means-transactions-settled
   [:forall :b [:nodes "Batch" :in ["BATCH_COMPLETED"]]
    [:forall :tx [:nodes "Transaction" :children-of [:var :b]]
     [:or [:= [:state-of [:var :tx]] "TX_COMPLETED"] [:= [:state-of [:var :tx]] "TX_FAILED"]]]]})

(def definition
  {:profile {:name profile-name :version profile-version}
   :events events
   :effects effects
   :node-types node-types
   :payloads payloads
   :transitions transitions
   :routing routing
   :chain-views chain-views
   :predicates predicates
   :invariants invariants})

(deftype FileBatchTransactionProfile []
  profile/ProcessProfile
  (definition [_] definition))

(defn profile [] (->FileBatchTransactionProfile))
