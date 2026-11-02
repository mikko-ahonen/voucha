(ns voucha.java-sdk-test
  "The Java SDK.

  The design's Java examples (sections 38-41) are compiled from real .java
  sources in java-src/ and executed against the real kernel. Run
  `clojure -T:build javac` first.

  The point of these tests is that Java is an authoring surface only: a Java
  handler compiles into the same transition spec, produces the same plan data,
  and lands in the same transition log as a Clojure one. Here the Java class
  authors the File-receive store, batch preparation (child creation), transaction
  screening (call + timeout) and its answer (an external id entering node state),
  and batch completion (emit + counter read); every other transition stays in
  Clojure."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [voucha.compiler :as compiler]
            [voucha.db :as db]
            [voucha.dispatcher :as dispatcher]
            [voucha.fake :as fake]
            [voucha.kernel :as kernel]
            [voucha.profiles.file-batch-transaction :as fbt]
            [voucha.replay :as replay]
            [voucha.repo.artifact :as artifact]
            [voucha.repo.effect :as effect]
            [voucha.repo.node :as node]
            [voucha.repo.transition-log :as tlog]
            [voucha.runtime :as runtime]
            [voucha.sdk.java :as jsdk]
            [voucha.test-db :as tdb :refer [*ds*]])
  (:import (example BatchTransitions)))

(use-fixtures :once tdb/with-db)
(use-fixtures :each tdb/clean-db)

(def tree-id "JT")

(def scanned (delay (jsdk/scan BatchTransitions)))

(defn- java-definition
  "The standard profile, with the transitions the Java class declares laid over
  the profile's own. The annotation carries the handler, effects, awaits and
  emits; a hierarchy constraint the profile declares on the same transition
  (`:requires`) stays, since the annotation has no surface for it. Everything
  else stays as it was."
  []
  (let [java-transitions (into {} (map (juxt (juxt :node-type :from :on) identity)) (:transitions @scanned))]
    (update fbt/definition :transitions
            (fn [ts] (mapv (fn [t] (if-let [j (java-transitions ((juxt :node-type :from :on) t))]
                                     (merge (dissoc t :plan) j)
                                     t))
                           ts)))))

(defn- clojure-handlers []
  ;; The transitions the Java class does not claim still run their Clojure handlers.
  (require 'voucha.example.batch)
  @(resolve 'voucha.example.batch/handlers))

(defn- rt []
  (runtime/runtime
   {:handlers (merge (clojure-handlers) (:handlers @scanned))
    :effect-adapters {"screening.check" (fake/effect-adapter [{:outcome :completed
                                                               :external-request-id "SCREEN-JAVA-1"}])
                      "account.debit"   (fake/effect-adapter [{:outcome :completed}])
                      "account.reverse" (fake/effect-adapter [{:outcome :completed}])
                      "rail.sct.submit" (fake/effect-adapter [{:outcome :completed}])
                      "rail.inst.send"  (fake/effect-adapter [{:outcome :dispatched}])}
    :store-adapters {"file.writeReceive" (fake/store-adapter)
                     "file.writeResult"  (fake/store-adapter)}
    :config {:dispatcher {:batch-size 10 :backoff-ms 20 :max-attempts 3}}}))

(defn- install! []
  (db/in-tx [tx *ds*]
    (let [a (compiler/compile-definition (java-definition))]
      (artifact/insert! tx a) (:artifact-id a))))

(defn- send! [r node-id event-type payload]
  (db/in-tx [tx *ds*]
    (kernel/submit-event! tx {:event-id (str (gensym "ev")) :tree-id tree-id
                              :target-node-id node-id :event-type event-type
                              :payload payload}))
  (kernel/drain! *ds* r))

(defn- state-of [node-id]
  (:local-state (db/in-tx [tx *ds*] (node/fetch tx tree-id node-id))))

(defn- data-of [node-id]
  (:local-data (db/in-tx [tx *ds*] (node/fetch tx tree-id node-id))))

(defn- effects-of [node-id]
  (db/in-tx [tx *ds*] (effect/by-owner tx tree-id node-id)))

;; --- scanning ------------------------------------------------------------------

(deftest annotations-become-transition-specs
  (let [{:keys [transitions handlers]} @scanned
        screen (first (filter #(= "TransactionScreeningStart" (:on %)) transitions))]
    (is (= 5 (count transitions)))
    (is (= {:node-type "Transaction" :from "TX_VALIDATED" :on "TransactionScreeningStart"
            :to "TX_SCREENING"
            :handler 'BatchTransitions/screen
            :effects ["screening.check"]
            :await ["TransactionScreeningCleared"]}
           screen))
    (is (contains? handlers 'BatchTransitions/screen))))

(deftest scanning-is-order-stable
  (testing "the artifact id must not depend on JVM reflection order"
    (is (= (mapv :handler (:transitions (jsdk/scan BatchTransitions)))
           (mapv :handler (:transitions (jsdk/scan BatchTransitions)))))))

(deftest a-java-definition-compiles
  (let [errors (compiler/validate (java-definition))]
    (is (empty? errors) (compiler/explain errors))))

;; --- execution ------------------------------------------------------------------

(deftest java-handlers-run-on-the-kernel
  ;; The design's sections 38-41, executed.
  (let [r (rt)
        aid (install!)]
    (db/in-tx [tx *ds*]
      (kernel/create-tree! tx {:tree-id tree-id :artifact-id aid :root-node-id "F1"
                               :root-node-type "File"}))

    (testing "design 41: .store(...) creates a store effect, it does not write"
      (send! r "F1" "FileReceived" {:name "java.csv"
                                    :batches [{:batch-id "B1"
                                               :transactions [{:tx-id "TX1" :amount 100 :payment-type "SCT"}]}]})
      (is (= "WRITING_RECEIVE_FILE" (state-of "F1")))
      (let [[e] (effects-of "F1")]
        (is (= "file.writeReceive" (:effect-name e)))
        (is (= "CREATED" (:status e)) "no I/O happened inside the transition")
        (is (= 1 (:batch-count (:request-payload e))) "the Java handler counted the batches"))
      (dispatcher/run-once! *ds* r)
      (kernel/drain! *ds* r))

    (testing "a Java transition creates child nodes and reads its own patch back"
      (is (= "BATCH_PROCESSING" (state-of "B1")) "the batch validated, the Java handler prepared it, and the decision chose to wait")
      (is (= 1 (:validated-count (data-of "B1"))))
      (is (= 1 (:child-count (data-of "B1"))))
      (is (= "TX_NEW" (state-of "TX1"))))

    ;; The Java plan surface has no downward command, so the child is commanded on
    ;; by a separate event -- exactly as the design's Java examples were driven.
    (send! r "TX1" "TransactionStartCommanded" {:amount 100 :payment-type "SCT"})

    (testing "design 38: .call(...) creates an effect intent and arms a timeout"
      (is (= "TX_SCREENING" (state-of "TX1")))
      (let [e (first (filter #(= "screening.check" (:effect-name %)) (effects-of "TX1")))]
        (is (= "CREATED" (:status e)) "no I/O happened inside the transition")
        (is (= 0 (:attempt (:request-payload e))))
        (is (= 100 (:amount (:request-payload e)))))
      (is (= 1 (:n (db/in-tx [tx *ds*]
                     (db/execute-one! tx ["SELECT COUNT(*) AS n FROM timeout_schedule
                                            WHERE status = 'PENDING'"]))))))

    (testing "design 39: the screening answer's external id enters node state"
      (dispatcher/run-once! *ds* r)
      (kernel/drain! *ds* r)
      (is (= "SCREEN-JAVA-1" (:screening-ref (data-of "TX1"))))
      (is (= "TX_PAYING" (state-of "TX1")) "and readiness bubbled: the batch commanded it to pay"))

    (runtime/settle! *ds* r)

    (testing "design 40: completion reads a counter and bubbles"
      (is (= "BATCH_COMPLETED" (state-of "B1")))
      (is (= 1 (:paid (data-of "B1"))) "the Java handler read the kernel's aggregate counter")
      (is (= "FILE_COMPLETED" (state-of "F1")) "and it bubbled to the file"))

    (testing "the transition log names the Java handler like any other"
      (is (contains? (set (map :handler-name (db/in-tx [tx *ds*] (tlog/history tx tree-id "TX1"))))
                     "BatchTransitions/screen")))

    (testing "Java handlers are deterministic and replay reproduces them"
      (let [result (db/in-tx [tx *ds*]
                     (replay/verify-deterministic
                      tx tree-id (merge (clojure-handlers) (:handlers @scanned))))]
        (is (:ok? result) (pr-str (:divergences result)))))))

(deftest a-java-handler-may-not-emit-an-unpermitted-effect
  ;; The artifact governs, whatever the language. Transaction may emit this one;
  ;; nothing else, and no annotation can grant it.
  (let [specs (:specs (compiler/compile-definition (java-definition)))]
    (is (= ["screening.check"]
           (:may-emit-effects (compiler/node-type-spec specs "Transaction"))))))

(deftest the-compiler-rejects-a-java-transition-that-lies
  ;; An annotation claiming an await the effect cannot produce is caught at
  ;; compile time, exactly as it would be for a Clojure definition.
  (let [d (update (java-definition) :transitions
                  (fn [ts] (mapv (fn [t] (if (= "TransactionScreeningStart" (:on t))
                                           (assoc t :await ["ReceiveFileWritten"])
                                           t))
                                 ts)))
        codes (set (map :code (compiler/validate d)))]
    (is (contains? codes :invalid-effect-completion-mapping))))
