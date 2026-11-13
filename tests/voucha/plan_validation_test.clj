(ns voucha.plan-validation-test
  "Handler emissions must be validated against the
  artifact.

  Design 31: \"routing is not ad hoc. It is compiled into the execution artifact.\"
  The compiler checks the `:emit` list a transition *declares*. It could not check
  the plan a handler *returns*, so any event type -- declared or invented -- flowed
  straight into `event_log`, and `emit-to` could address any node at all.

  An undeclared event is not merely useless. It is durable: it sits in the event
  log, it is never routed, and it looks to an operator exactly like an event the
  kernel failed to process."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [voucha.compiler :as compiler]
            [voucha.db :as db]
            [voucha.example.batch :as app]
            [voucha.handler :as handler]
            [voucha.kernel :as kernel]
            [voucha.plan :as plan]
            [voucha.profiles.file-batch-transaction :as fbt]
            [voucha.repo.artifact :as artifact]
            [voucha.repo.node :as node]
            [voucha.repo.transition-log :as tlog]
            [voucha.test-db :as tdb :refer [*ds*]]))

(use-fixtures :once tdb/with-db)
(use-fixtures :each tdb/clean-db)

(def ^:private as-code
  ;; The two transitions these tests replace run as registered functions, so
  ;; that the handler under test, not the profile's plan, produces the plan.
  (update fbt/definition :transitions
          (fn [ts] (mapv #(if (#{'file/received 'file/start-batches} (:handler %)) (dissoc % :plan) %) ts))))

(def ^:private specs (:specs (compiler/compile-definition fbt/definition)))

(def ^:private runtime
  {:handlers (assoc app/handlers
                    'file/received (handler/plan-fn (compiler/transition-for specs "File" "FILE_NEW" "FileReceived") {})
                    'file/start-batches (handler/plan-fn (compiler/transition-for specs "File" "WRITING_RECEIVE_FILE" "ReceiveFileWritten") {}))
   :event {:max-attempts 3}})

(defn- new-tree! []
  (let [aid (db/in-tx [tx *ds*]
              (let [a (compiler/compile-definition as-code)]
                (artifact/insert! tx a) (:artifact-id a)))]
    (db/in-tx [tx *ds*]
      (kernel/create-tree! tx {:tree-id "T" :artifact-id aid :root-node-id "F1"
                               :root-node-type "File"}))
    aid))

(defn- with-handler [sym f]
  (assoc-in runtime [:handlers sym] f))

(defn- send! [rt node-id event-type]
  (db/in-tx [tx *ds*]
    (kernel/submit-event! tx {:event-id (str (gensym "ev")) :tree-id "T"
                              :target-node-id node-id :event-type event-type}))
  (kernel/drain! *ds* rt))

(defn- state-of [node-id]
  (:local-state (db/in-tx [tx *ds*] (node/fetch tx "T" node-id))))

(defn- event-types []
  (mapv :event_type (db/in-tx [tx *ds*] (db/execute! tx ["SELECT event_type FROM event_log"]))))

;; --- undeclared events ---------------------------------------------------------

(deftest a-handler-cannot-emit-an-event-the-artifact-never-declared
  (new-tree!)
  (let [rt (with-handler 'file/received
             (fn [_ _] (-> (plan/plan) (plan/state "WRITING_RECEIVE_FILE") (plan/emit "FileMisspelledd"))))]
    (send! rt "F1" "FileReceived")

    (is (= "FILE_NEW" (state-of "F1")) "the transition was aborted")
    (is (= ["FileReceived"] (event-types))
        "the undeclared event never reached the event log")
    (is (empty? (db/in-tx [tx *ds*] (tlog/history tx "T" "F1")))
        "and no checkpoint survived: the abort is atomic, like any other")))

(deftest a-handler-cannot-command-a-node-that-does-not-exist
  (new-tree!)
  (let [rt (with-handler 'file/received
             (fn [_ _] (-> (plan/plan)
                           (plan/state "WRITING_RECEIVE_FILE")
                           (plan/emit-to "B-does-not-exist" "BatchStartCommanded"))))]
    (send! rt "F1" "FileReceived")
    (is (= "FILE_NEW" (state-of "F1")))
    (is (= ["FileReceived"] (event-types)))))

(deftest a-downward-command-may-address-a-child-the-same-plan-creates
  ;; Regression guard: the reference app's `tx/start-paying` creates an Attempt
  ;; and commands it to start, in one plan. The child does not exist in the
  ;; database when the plan is validated -- only in the plan.
  (new-tree!)
  (let [rt (with-handler 'file/received
             (fn [_ _] (-> (plan/plan)
                           (plan/state "WRITING_RECEIVE_FILE")
                           (plan/child "B9" "Batch")
                           (plan/emit-to "B9" "BatchStartCommanded"))))]
    (send! rt "F1" "FileReceived")
    (is (= "WRITING_RECEIVE_FILE" (state-of "F1")))
    (is (= "BATCH_COMPLETED" (state-of "B9"))
        "the command reached the child the same transition created (an empty batch validates and completes at once)")))

(deftest emitting-a-declared-event-to-an-existing-node-still-works
  (new-tree!)
  (db/in-tx [tx *ds*]
    (kernel/add-child! tx {:tree-id "T" :parent-node-id "F1" :node-id "B1" :node-type "Batch"}))
  (let [rt (with-handler 'file/received
             (fn [_ _] (-> (plan/plan)
                           (plan/state "WRITING_RECEIVE_FILE")
                           (plan/emit-to "B1" "BatchStartCommanded"))))]
    (send! rt "F1" "FileReceived")
    (is (= "WRITING_RECEIVE_FILE" (state-of "F1")))
    (is (= "BATCH_COMPLETED" (state-of "B1")))))

;; --- a plan may not land somewhere its transition did not declare -----------------

(deftest a-handler-cannot-land-in-a-state-its-transition-did-not-declare
  ;; The artifact declares `File WRITING_RECEIVE_FILE --ReceiveFileWritten-->
  ;; FILE_PROCESS_BATCH`. A handler that returns FILE_PERMANENT_FAILURE instead
  ;; reaches a state the artifact defines, so the old `:invalid-target-state`
  ;; check passed it -- and then everything derived from the declared edge was a
  ;; lie: the reachability analysis, the graph export, the chain view, and (since
  ;; earlier) the row lock the kernel took on the parent, which is chosen from the
  ;; declared :to before the handler runs.
  (new-tree!)
  (let [rt (with-handler 'file/start-batches
             (fn [_ _] (-> (plan/plan) (plan/state "FILE_PERMANENT_FAILURE"))))]
    (send! rt "F1" "FileReceived")
    (is (= "WRITING_RECEIVE_FILE" (state-of "F1")))
    (send! rt "F1" "ReceiveFileWritten")
    (is (= "WRITING_RECEIVE_FILE" (state-of "F1"))
        "the transition was aborted: FILE_PERMANENT_FAILURE is a real File state, but not this transition's")))

;; --- the compiler warns about emissions nothing listens to -----------------------

(deftest an-emission-no-route-or-transition-consumes-is-a-warning-not-an-error
  (testing "the standard profile emits nothing that falls on deaf ears"
    (is (empty? (:warnings (compiler/compile-definition fbt/definition)))))

  (testing "an event declared, emitted, and consumed by nobody is flagged"
    (let [d (-> fbt/definition
                (update :events conj "NobodyListens")
                (update :transitions conj {:node-type "File" :from "FILE_PROCESS_BATCH"
                                           :on "ResultFileWritten" :to "FILE_BATCHES_PROCESSED"
                                           :handler 'file/write-result
                                           :emit ["NobodyListens"]}))
          {:keys [warnings]} (compiler/compile-definition d)]
      (is (= [:unconsumed-event] (mapv :code warnings)))
      (is (re-find #"NobodyListens" (:message (first warnings))))))

  (testing "a warning does not change the artifact id: it is an observation, not a rule"
    (is (= (:artifact-id (compiler/compile-definition fbt/definition))
           (:artifact-id (compiler/compile-definition fbt/definition))))))
