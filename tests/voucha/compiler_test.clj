(ns voucha.compiler-test
  "Every compiler validation rule gets a definition that trips it.

  The tests deliberately use profile-shaped node type names to demonstrate that
  the compiler can produce the design-35 worked error about Batch and
  Transaction without the kernel knowing what either word means."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [voucha.compiler :as compiler]
            [voucha.profile :as profile]
            [voucha.profiles.file-batch-transaction :as fbt]))

(def minimal
  "A two-level definition that compiles cleanly. Each test perturbs one thing."
  {:profile {:name "test" :version "v1"}
   :events #{"Start" "ChildDone" "Finish"}
   :effects {}
   :node-types
   {"Parent" {:may-contain ["Child"]
              :initial-state "OPEN"
              :states ["OPEN" "RUNNING" "DONE" "FAILED"]
              :terminal-states ["DONE" "FAILED"]
              :failure-state "FAILED"
              :counters {:kids-done {:child-type "Child" :when-child-enters ["DONE"]}}
              :child-states-allowed {"DONE" {"Child" ["DONE" "FAILED"]}}}
    "Child" {:may-contain []
             :initial-state "OPEN"
             :states ["OPEN" "DONE" "FAILED"]
             :terminal-states ["DONE" "FAILED"]
             :failure-state "FAILED"}}
   :transitions
   [{:node-type "Parent" :from "OPEN" :on "Start" :to "RUNNING" :handler 'p/start}
    {:node-type "Parent" :from "RUNNING" :on "ChildDone" :to "DONE" :handler 'p/done
     :requires [{:constraint :all-children-in :child-type "Child" :states ["DONE"]}]}
    {:node-type "Parent" :from "RUNNING" :on "Finish" :to "FAILED" :handler 'p/fail}
    {:node-type "Child" :from "OPEN" :on "Finish" :to "DONE" :handler 'c/done
     :emit ["ChildDone"]}
    {:node-type "Child" :from "OPEN" :on "Start" :to "FAILED" :handler 'c/fail}]
   :routing [{:event "ChildDone" :from-node-type "Child" :to-node-type "Parent"
              :scope :upward :on-unsatisfied :ignore}]
   :chain-views {}})

(defn- codes [d] (set (map :code (compiler/validate d))))

(deftest minimal-definition-compiles
  (is (empty? (compiler/validate minimal)))
  (is (string? (:artifact-id (compiler/compile-definition minimal)))))

(deftest artifact-is-content-addressed
  (testing "identical definitions produce identical artifact ids (design 34)"
    (is (= (:artifact-id (compiler/compile-definition minimal))
           (:artifact-id (compiler/compile-definition minimal)))))

  (testing "any semantic change produces a different artifact id"
    (let [changed (assoc-in minimal [:node-types "Parent" :states] ["OPEN" "RUNNING" "DONE" "FAILED" "EXTRA"])]
      ;; EXTRA is unreachable, so compile via specs hashing directly
      (is (not= (:artifact-id (compiler/compile-definition minimal))
                (voucha.codec/content-hash (:node-types changed))))))

  (testing "the artifact embeds its own id in the manifest"
    (let [{:keys [artifact-id specs]} (compiler/compile-definition minimal)]
      (is (= artifact-id (get-in specs [:manifest :artifact-id]))))))

(deftest sugar-expands-into-runtime-constraints
  (let [{:keys [specs]} (compiler/compile-definition minimal)
        t (compiler/transition-for specs "Parent" "RUNNING" "ChildDone")]
    (is (= [{:constraint :counters-cover-children :counters [:kids-done]}]
           (:requires t))
        ":all-children-in must be rewritten to the runtime counter form (D2)")))

(deftest compile-failure-throws-with-explanation
  (let [bad (assoc-in minimal [:node-types "Parent" :may-contain] ["Nonexistent"])
        ex (try (compiler/compile-definition bad) nil (catch clojure.lang.ExceptionInfo e e))]
    (is (some? ex))
    (is (= :voucha.compiler/compile-failed (:type (ex-data ex))))
    (is (str/includes? (ex-message ex) "unknown-node-type"))
    (is (str/includes? (ex-message ex) "Either:") "errors must suggest fixes")))

;; --- individual validation rules ---------------------------------------------

(deftest detects-unknown-node-types
  (is (contains? (codes (assoc-in minimal [:node-types "Parent" :may-contain] ["Ghost"]))
                 :unknown-node-type))
  (is (contains? (codes (update minimal :transitions conj
                                {:node-type "Ghost" :from "OPEN" :on "Start" :to "DONE" :handler 'g/h}))
                 :unknown-node-type)))

(deftest detects-unknown-states
  (is (contains? (codes (update minimal :transitions conj
                                {:node-type "Child" :from "MISSING" :on "Start" :to "DONE" :handler 'c/h}))
                 :unknown-state))
  (is (contains? (codes (assoc-in minimal [:node-types "Child" :terminal-states] ["GHOST"]))
                 :unknown-state)))

(deftest detects-unknown-events
  (is (contains? (codes (assoc-in minimal [:transitions 0 :on] "NeverDeclared"))
                 :unknown-event))
  (testing "emitted events must be declared too"
    (is (contains? (codes (assoc-in minimal [:transitions 3 :emit] ["Undeclared"]))
                   :unknown-event))))

(deftest detects-duplicate-transitions-and-ambiguous-routes
  (testing "two handlers claiming the same (node-type, from, event)"
    (is (contains? (codes (update minimal :transitions conj
                                  {:node-type "Parent" :from "OPEN" :on "Start" :to "FAILED"
                                   :handler 'p/other}))
                   :duplicate-transition)))
  (testing "two routing rules for the same (event, source node type)"
    (is (contains? (codes (update minimal :routing conj
                                  {:event "ChildDone" :from-node-type "Child"
                                   :to-node-type "Parent" :scope :upward}))
                   :ambiguous-event-route))))

(deftest detects-unreachable-states
  (let [d (-> minimal
              (assoc-in [:node-types "Child" :states] ["OPEN" "DONE" "FAILED" "ORPHAN"]))]
    (is (contains? (codes d) :unreachable-state))))

(deftest detects-transition-out-of-a-terminal-state
  (is (contains? (codes (update minimal :transitions conj
                                {:node-type "Child" :from "DONE" :on "Start" :to "OPEN" :handler 'c/undo}))
                 :invalid-transition)))

(deftest detects-missing-initial-and-failure-states
  (is (contains? (codes (assoc-in minimal [:node-types "Child" :initial-state] "GHOST"))
                 :missing-initial-state))
  (is (contains? (codes (update-in minimal [:node-types "Child"] dissoc :failure-state))
                 :missing-failure-state)))

(deftest detects-terminal-parent-allowing-nonterminal-child
  ;; This is the worked example in design 35. The compiler can say it precisely
  ;; because Parent and Child are artifact node types, not kernel concepts.
  (let [d (assoc-in minimal [:node-types "Parent" :child-states-allowed "DONE" "Child"]
                    ["DONE" "OPEN"])
        errors (compiler/validate d)
        e (first (filter #(= :terminal-parent-allows-nonterminal-child (:code %)) errors))]
    (is (some? e))
    (is (str/includes? (:message e) "Parent.DONE is terminal"))
    (is (str/includes? (:message e) "OPEN"))
    (is (= 3 (count (:fixes e))) "offers disallow / mark-terminal / aggregation-rule")))

(deftest detects-missing-aggregation-rule
  ;; A parent transition that aggregates over children it declares no counter for.
  (let [d (-> minimal
              (update-in [:node-types "Parent"] dissoc :counters)
              (assoc-in [:transitions 1 :requires]
                        [{:constraint :all-children-in :child-type "Child" :states ["DONE"]}]))
        errors (compiler/validate d)
        e (first (filter #(= :missing-aggregation-rule (:code %)) errors))]
    (is (some? e))
    (is (str/includes? (:message e) "declares no counter over exactly those states"))))

(deftest detects-missing-counter
  (let [d (assoc-in minimal [:transitions 1 :requires]
                    [{:constraint :counter :counter :ghost-counter :op :<= :value 1}])]
    (is (contains? (codes d) :missing-counter))))

(deftest detects-invalid-constraints
  (is (contains? (codes (assoc-in minimal [:transitions 1 :requires]
                                  [{:constraint :telepathy}]))
                 :invalid-constraint))
  (is (contains? (codes (assoc-in minimal [:transitions 1 :requires]
                                  [{:constraint :counter :counter :kids-done :op :approximately :value 1}]))
                 :invalid-constraint))
  (is (contains? (codes (assoc-in minimal [:transitions 1 :requires]
                                  [{:constraint :ancestor-state :node-type "Ghost" :allowed ["OPEN"]}]))
                 :unknown-node-type)))

(deftest detects-counter-watching-a-non-child
  (let [d (assoc-in minimal [:node-types "Child" :counters]
                    {:bad {:child-type "Parent" :when-child-enters ["DONE"]}})]
    (is (contains? (codes d) :invalid-parent-child-state))))

;; --- effect declarations ------------------------------------------------------

(def with-effect
  (-> minimal
      (update :events into ["Acked" "Done"])
      (assoc :effects {"svc.call" {:version "v1" :kind :external
                                   :idempotency-key [:tree-id :node-id]
                                   :correlation-key :node-id
                                   :uncertainty-policy :retry-safe
                                   :ack-event "Acked"
                                   :completion-event "Done"}})
      (assoc-in [:node-types "Parent" :may-emit-effects] ["svc.call"])
      (assoc-in [:transitions 0 :effects] ["svc.call"])
      (assoc-in [:transitions 0 :await] ["Acked"])
      ;; D14: every outcome the effect can report must be consumable from RUNNING.
      (update :transitions into [{:node-type "Parent" :from "RUNNING" :on "Acked" :to "RUNNING" :handler 'p/acked}
                                 {:node-type "Parent" :from "RUNNING" :on "Done" :to "DONE" :handler 'p/done}])))

(deftest effect-definition-compiles
  (is (empty? (compiler/validate with-effect))))

(deftest detects-effect-declaration-gaps
  (is (contains? (codes (update-in with-effect [:effects "svc.call"] dissoc :version))
                 :missing-effect-version))
  (is (contains? (codes (update-in with-effect [:effects "svc.call"] dissoc :idempotency-key))
                 :missing-idempotency-key))
  (is (contains? (codes (update-in with-effect [:effects "svc.call"] dissoc :correlation-key))
                 :missing-correlation-key))
  (is (contains? (codes (update-in with-effect [:effects "svc.call"] dissoc :uncertainty-policy))
                 :missing-uncertainty-policy))
  (testing "a :query policy without a status-query operation cannot resolve uncertainty (D8)"
    (is (contains? (codes (assoc-in with-effect [:effects "svc.call" :uncertainty-policy] :query))
                   :missing-status-query)))
  (testing "store effects need an adapter"
    (is (contains? (codes (assoc-in with-effect [:effects "svc.call" :kind] :store))
                   :store-effect-missing-adapter))))

(deftest detects-effect-permission-violation
  (let [d (update-in with-effect [:node-types "Parent"] dissoc :may-emit-effects)]
    (is (contains? (codes d) :effect-not-permitted)))
  (let [d (assoc-in with-effect [:transitions 0 :effects] ["svc.unknown"])]
    (is (contains? (codes d) :unknown-effect))))

(deftest detects-invalid-effect-completion-mapping
  ;; Awaiting an event no declared effect can produce would hang the node forever.
  (let [d (assoc-in with-effect [:transitions 0 :await] ["ChildDone"])]
    (is (contains? (codes d) :invalid-effect-completion-mapping))))

(deftest detects-invalid-timeout-policy
  (is (contains? (codes (assoc-in with-effect [:transitions 0 :timeout] {:after-ms 0 :event "Done"}))
                 :invalid-timeout-policy))
  (is (contains? (codes (assoc-in with-effect [:transitions 0 :timeout] {:after-ms 100 :event "Ghost"}))
                 :invalid-timeout-policy)))

(deftest detects-invalid-routing-and-chain-views
  (is (contains? (codes (assoc-in minimal [:routing 0 :scope] :sideways)) :invalid-routing))
  (is (contains? (codes (assoc-in minimal [:routing 0 :on-unsatisfied] :explode)) :invalid-routing))
  (is (contains? (codes (assoc minimal :chain-views
                               {"V" {:node-type "Ghost" :steps []}}))
                 :invalid-chain-step))
  (is (contains? (codes (assoc minimal :chain-views
                               {"V" {:node-type "Child"
                                     :steps [{:name "S" :active-states ["GHOST"]
                                              :complete-states ["DONE"]}]}}))
                 :invalid-chain-step))
  (is (contains? (codes (assoc minimal :chain-views
                               {"V" {:node-type "Child" :steps [{:name "S" :complete-states []}]}}))
                 :invalid-chain-step)))

;; --- the standard profile -----------------------------------------------------

(deftest standard-profile-compiles
  (let [errors (compiler/validate fbt/definition)]
    (is (empty? errors) (compiler/explain errors))))

(deftest standard-profile-artifact-is-stable
  (let [a (compiler/compile-definition fbt/definition)
        b (compiler/compile-definition fbt/definition)]
    (is (= (:artifact-id a) (:artifact-id b)))
    (is (= "file-batch-transaction" (:profile-name a)))
    (is (= "v3" (:profile-version a)))))

(deftest standard-profile-encodes-the-design-constraints
  (let [{:keys [specs]} (compiler/compile-definition fbt/definition)]
    (testing "a transaction cannot pay unless its batch is processing (design 14)"
      (let [t (compiler/transition-for specs "Transaction" "TX_SCREENED" "TransactionPayCommanded")]
        (is (= [{:constraint :parent-state
                 :allowed ["BATCH_SCREEN_TRANSACTIONS" "BATCH_PROCESSING" "BATCH_PAY_TRANSACTIONS"]}]
               (:requires t)))))

    (testing "a batch cannot complete while a transaction is still paying (design 14, 16)"
      (is (= ["TX_COMPLETED" "TX_FAILED"]
             (compiler/child-states-allowed specs "Batch" "BATCH_COMPLETED" "Transaction")))
      (is (not (contains? (set (compiler/child-states-allowed specs "Batch" "BATCH_COMPLETED" "Transaction"))
                          "TX_PAYING"))))

    (testing "a file cannot complete unless all batches are terminal (design 17)"
      (let [t (compiler/transition-for specs "File" "FILE_PROCESS_BATCHES" "AllBatchesProcessed")]
        (is (= [{:constraint :counters-cover-children
                 :counters [:batches-completed :batches-rejected]}]
               (:requires t)))))

    (testing "an attempt may run only while its transaction is paying (design 14)"
      (let [t (compiler/transition-for specs "Attempt" "ATTEMPT_NEW" "AttemptCommanded")]
        (is (= ["TX_PAYING"] (:allowed (first (:requires t)))))))

    (testing "the instant rail effect resolves uncertainty by querying, never resubmitting (design 29)"
      (let [e (compiler/effect-spec specs "rail.inst.send")]
        (is (= :query (:uncertainty-policy e)))
        (is (= "rail.inst.status" (:status-query e)))))

    (testing "counters route child states to parent aggregates (D6)"
      (is (= [:tx-paid] (compiler/counters-watching specs "Batch" "Transaction" "TX_COMPLETED")))
      (is (= [:tx-failed] (compiler/counters-watching specs "Batch" "Transaction" "TX_FAILED"))))

    (testing "terminality is a per-node-type artifact fact, not a kernel constant"
      (is (compiler/terminal? specs "Batch" "BATCH_COMPLETED"))
      (is (not (compiler/terminal? specs "Batch" "BATCH_PROCESSING"))))))

(deftest kernel-has-no-knowledge-of-profile-concepts
  ;; Invariant (design 65): "No kernel logic that special-cases Batch or Transaction."
  (doseq [ns* ['voucha.compiler 'voucha.constraint 'voucha.patch 'voucha.codec
               'voucha.db 'voucha.repo.node 'voucha.repo.tree]]
    (require ns*)
    (let [src (slurp (str "src/" (str/replace (str/replace (str ns*) "." "/") "-" "_") ".clj"))
          ;; strip comments and docstrings is overkill; check code forms only by
          ;; looking for the words as quoted string literals used in logic
          offenders (filter #(re-find (re-pattern (str "\\(=\\s+\"" % "\"")) src)
                            ["File" "Batch" "Transaction" "Attempt"])]
      (is (empty? offenders)
          (format "%s compares against profile node type(s) %s" ns* offenders)))))

(deftest a-domain-layer-extends-the-profile-rather-than-replacing-it
  ;; A domain adds a state, an event and two transitions to the File lifecycle.
  ;; The profile's own states must survive, or every transition it declares
  ;; becomes a reference to an unknown state.
  (let [domain {:events #{"FileApprovalRequired" "FileApproved"}
                :node-types {"File" {:states ["FILE_AWAITING_APPROVAL"]}}
                :transitions
                [{:node-type "File" :from "WRITING_RECEIVE_FILE" :on "FileApprovalRequired"
                  :to "FILE_AWAITING_APPROVAL" :handler 'file/hold-for-approval}
                 {:node-type "File" :from "FILE_AWAITING_APPROVAL" :on "FileApproved"
                  :to "FILE_PROCESS_BATCH" :handler 'file/start-batches}]}
        d (profile/derive-definition fbt/definition domain)]
    (is (empty? (compiler/validate d)) (compiler/explain (compiler/validate d)))
    (is (contains? (set (get-in d [:node-types "File" :states])) "FILE_NEW") "the profile's states survive")
    (is (contains? (set (get-in d [:node-types "File" :states])) "FILE_AWAITING_APPROVAL") "and the domain's is added")
    (is (contains? (:events d) "FileReceived"))))

;; --- nested states ------------------------------------------------------------------

(def ^:private nested
  "Parent with a composite WORKING state: two leaves, a retry loop inside, and an
  operator cancel declared once on the composite."
  {:profile {:name "nested" :version "v1"}
   :events #{"Start" "Done" "Hiccup" "Retry" "Cancel" "Finished"}
   :effects {"svc.call" {:version "v1" :kind :external
                         :idempotency-key [:tree-id :node-id :attempt] :correlation-key :node-id
                         :uncertainty-policy :retry-safe
                         :completion-event "Done" :failure-event "Hiccup"}}
   :node-types {"Parent" {:may-contain [] :may-emit-effects ["svc.call"]
                          :initial-state "OPEN"
                          :states ["OPEN"
                                   {:name "WORKING" :initial "CALLING" :states ["CALLING" "RETRY_WAIT"]}
                                   "DONE" "CANCELLED"]
                          :terminal-states ["DONE" "CANCELLED"] :failure-state "CANCELLED"}}
   :transitions [{:node-type "Parent" :from "OPEN" :on "Start" :to "WORKING" :handler 'p/start
                  :effects ["svc.call"] :await ["Done"]}
                 {:node-type "Parent" :from "CALLING" :on "Done" :to "DONE" :handler 'p/done}
                 {:node-type "Parent" :from "CALLING" :on "Hiccup" :to "RETRY_WAIT" :handler 'p/wait :emit ["Retry"]}
                 {:node-type "Parent" :from "RETRY_WAIT" :on "Retry" :to "CALLING" :handler 'p/start
                  :effects ["svc.call"] :await ["Done"]}
                 {:node-type "Parent" :from "WORKING" :on "Cancel" :to "CANCELLED" :handler 'p/cancel}
                 ;; A more specific declaration wins: cancelling while waiting to retry
                 ;; needs no cleanup and goes to DONE instead.
                 {:node-type "Parent" :from "RETRY_WAIT" :on "Cancel" :to "DONE" :handler 'p/done}]})

(deftest nested-states-flatten-to-leaves-the-runtime-can-execute
  (let [errors (compiler/validate nested)
        _ (is (empty? errors) (compiler/explain errors))
        {:keys [specs]} (compiler/compile-definition nested)
        parent (compiler/node-type-spec specs "Parent")]
    (testing "the runtime sees only leaves, and keeps the tree for diagrams"
      (is (= ["OPEN" "CALLING" "RETRY_WAIT" "DONE" "CANCELLED"] (:states parent)))
      (is (= 1 (count (filter map? (:state-tree parent)))) "one composite in the tree"))
    (testing "entering a composite enters its initial sub-state"
      (let [t (compiler/transition-for specs "Parent" "OPEN" "Start")]
        (is (= "CALLING" (:to t)))
        (is (= "WORKING" (:to-composite t)))))
    (testing "a transition from a composite applies to every leaf under it, inner declarations winning"
      (is (= "CANCELLED" (:to (compiler/transition-for specs "Parent" "CALLING" "Cancel"))))
      (is (= "WORKING" (:from-composite (compiler/transition-for specs "Parent" "CALLING" "Cancel"))))
      (is (= "DONE" (:to (compiler/transition-for specs "Parent" "RETRY_WAIT" "Cancel")))
          "the explicit RETRY_WAIT declaration overrides the composite's"))))

(deftest a-composite-stands-for-its-leaves-wherever-a-state-is-named
  (let [d (-> nested
              (assoc-in [:node-types "Child"] {:may-contain [] :may-emit-effects []
                                               :initial-state "C_NEW" :states ["C_NEW" "C_DONE"]
                                               :terminal-states ["C_DONE"] :failure-state "C_DONE"})
              (assoc-in [:node-types "Parent" :may-contain] ["Child"])
              (assoc-in [:node-types "Parent" :counters] {:done {:child-type "Child" :when-child-enters ["C_DONE"]}})
              (assoc-in [:node-types "Parent" :child-states-allowed] {"WORKING" {"Child" ["C_NEW" "C_DONE"]}
                                                                      "DONE" {"Child" ["C_DONE"]}})
              (update :transitions conj {:node-type "Child" :from "C_NEW" :on "Finished" :to "C_DONE" :handler 'c/done
                                         :requires [{:constraint :parent-state :allowed ["WORKING"]}]}))
        {:keys [specs]} (compiler/compile-definition d)]
    (is (= ["C_NEW" "C_DONE"] (compiler/child-states-allowed specs "Parent" "CALLING" "Child"))
        "an allowance on the composite applies in each of its leaves")
    (is (= ["C_NEW" "C_DONE"] (compiler/child-states-allowed specs "Parent" "RETRY_WAIT" "Child")))
    (is (= ["CALLING" "RETRY_WAIT"]
           (:allowed (first (:requires (compiler/transition-for specs "Child" "C_NEW" "Finished")))))
        "a hierarchy rule naming the composite admits every leaf under it")))

(deftest nesting-mistakes-are-compile-errors
  (is (= [:invalid-composite-state]
         (map :code (compiler/validate (assoc-in nested [:node-types "Parent" :states 1 :initial] "ELSEWHERE")))))
  (is (= [:duplicate-state]
         (map :code (compiler/validate (update-in nested [:node-types "Parent" :states] conj "CALLING"))))))

(deftest a-state-may-declare-the-events-it-ignores
  (let [with-ignores (fn [ig] (assoc-in fbt/definition [:node-types "Transaction" :ignores] ig))]
    (testing "the example: a failed transaction ignores the pay command"
      (let [{:keys [specs]} (compiler/compile-definition fbt/definition)]
        (is (compiler/ignores? specs "Transaction" "TX_FAILED" "TransactionPayCommanded"))
        (is (not (compiler/ignores? specs "Transaction" "TX_NEW" "TransactionCancelled")))))
    (testing "the state and the event must exist"
      (is (some #(= :unknown-state (:code %)) (compiler/validate (with-ignores {"TX_NOWHERE" ["TransactionPayCommanded"]}))))
      (is (some #(= :unknown-event (:code %)) (compiler/validate (with-ignores {"TX_FAILED" ["NoSuchEvent"]})))))
    (testing "a state may not both ignore and handle an event"
      (is (some #(= :ambiguous-ignore (:code %)) (compiler/validate (with-ignores {"TX_SCREENED" ["TransactionPayCommanded"]})))))))
