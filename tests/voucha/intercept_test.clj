(ns voucha.intercept-test
  "Phase A of the interception facility (docs/design/testing-api.md): the
  mechanism, and a proof that its block/signal primitive makes a race
  deterministic without the hand-rolled promises the F1 tests used."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [voucha.adapter :as adapter]
            [voucha.compiler :as compiler]
            [voucha.db :as db]
            [voucha.debug :as debug]
            [voucha.dispatcher :as dispatcher]
            [voucha.example.batch :as app]
            [voucha.intercept :as intercept]
            [voucha.kernel :as kernel]
            [voucha.profiles.file-batch-transaction :as fbt]
            [voucha.repo.artifact :as artifact]
            [voucha.repo.effect :as effect]
            [voucha.repo.node :as node]
            [voucha.test-db :as tdb :refer [*ds*]]))

(use-fixtures :once tdb/with-db)
(use-fixtures :each (fn [f] (tdb/clean-db #(try (f) (finally (intercept/reset!))))))

;; --- the mechanism (no database) ---------------------------------------------

(deftest hit-is-identity-with-nothing-installed
  (is (= {:a 1} (intercept/hit! :some/point {:a 1}))
      "the production path: one lookup, no change"))

(deftest observe-records-without-changing-the-data
  (let [seen (debug/observe! :p)]
    (is (= {:a 1} (intercept/hit! :p {:a 1})))
    (is (= {:a 2} (intercept/hit! :p {:a 2})))
    (is (= [{:a 1} {:a 2}] @seen) "every hit, in order, unmodified")))

(deftest transform-rewrites-the-data
  (debug/transform! :p #(update % :a inc))
  (is (= {:a 2} (intercept/hit! :p {:a 1}))))

(deftest transforms-compose-in-install-order
  (debug/transform! :p #(update % :a (fnil inc 0)))
  (debug/transform! :p #(update % :a * 10))
  (is (= {:a 10} (intercept/hit! :p {:a 0})) "increment then times-ten"))

(deftest match-scopes-an-interceptor-to-specific-data
  (let [seen (debug/observe! :p :match #(= "B1" (:node %)))]
    (intercept/hit! :p {:node "B1"})
    (intercept/hit! :p {:node "B2"})
    (is (= [{:node "B1"}] @seen) "only matching hits fire the action")))

(deftest on-hit-fires-only-on-the-nth-hit
  (let [fired (atom [])]
    (debug/transform! :p (fn [d] (swap! fired conj (:n d)) d) :on-hit 3)
    (doseq [n [1 2 3 4]] (intercept/hit! :p {:n n}))
    (is (= [3] @fired) "crash-after-n semantics: only the 3rd reach")))

(deftest signal-releases-a-blocked-thread
  (debug/block! :p :go :timeout-ms 5000)
  (let [released (promise)
        _ (future (intercept/hit! :p {}) (deliver released :done))]
    (Thread/sleep 150)
    (is (not (realized? released)) "parked until signalled")
    (debug/signal :go)
    (is (= :done (deref released 5000 :timeout)) "and proceeds once signalled")))

(deftest reset-clears-everything
  (debug/transform! :p (constantly {:tampered true}))
  (intercept/reset!)
  (is (= {:a 1} (intercept/hit! :p {:a 1})) "no interceptor survives a reset"))

;; --- the proof: a controlled race, no hand-rolled latch ----------------------

(def ^:private runtime {:handlers app/handlers :event {:max-attempts 5}})

(defn- given-attempt-under-paying-transaction! []
  (let [aid (db/in-tx [tx *ds*]
              (let [a (compiler/compile-definition fbt/definition)]
                (artifact/insert! tx a)
                (:artifact-id a)))]
    (db/in-tx [tx *ds*]
      (kernel/create-tree! tx {:tree-id "T" :artifact-id aid :root-node-id "F1"
                               :root-node-type "File"})
      (doseq [[id typ parent state] [["B1" "Batch" "F1" "BATCH_PROCESSING"]
                                     ["TX1" "Transaction" "B1" "TX_PAYING"]
                                     ["A1" "Attempt" "TX1" "ATTEMPT_NEW"]]]
        (node/insert! tx {:tree-id "T" :node-id id :parent-node-id parent
                          :node-type typ :local-state state
                          :local-data {:child-count 0 :counters {}}})))))

(defn- submit! [event-id node-id event-type]
  (db/in-tx [tx *ds*]
    (kernel/submit-event! tx {:event-id event-id :tree-id "T"
                              :target-node-id node-id :event-type event-type})))

(defn- state-of [node-id]
  (:local-state (db/in-tx [tx *ds*] (node/fetch tx "T" node-id))))

(defn- process-with-lock-timeout [event-id]
  (try
    (db/in-tx [tx *ds*]
      (db/execute-one! tx ["SET LOCAL lock_timeout = '750ms'"])
      (kernel/process-event! tx runtime event-id))
    (catch Exception e e)))

(deftest block-holds-the-transaction-lock-so-a-parent-cannot-move-under-the-child
  ;; The same guarantee as concurrency_test, but the pause is installed at the
  ;; `:transition/context-loaded` seam instead of hand-rolled inside a handler.
  ;; The attempt's transition locks its parent Transaction FOR SHARE at that seam
  ;; (its :parent-state constraint reads the transaction); blocking there holds
  ;; the lock, so a concurrent AttemptSucceeded that would settle the transaction
  ;; must wait.
  (given-attempt-under-paying-transaction!)
  (submit! "ev-dispatch" "A1" "AttemptCommanded")
  (submit! "ev-settle" "TX1" "AttemptSucceeded")

  (debug/block! :transition/context-loaded :release
                :match #(= "A1" (:node-id (:node %)))
                :timeout-ms 10000)

  (let [attempt (future (db/in-tx [tx *ds*] (kernel/process-event! tx runtime "ev-dispatch")))]
    (try
      (Thread/sleep 250)                ; let the attempt reach the seam and park
      (is (= "ATTEMPT_NEW" (state-of "A1")) "the attempt is parked mid-transition, uncommitted")

      (let [settle (process-with-lock-timeout "ev-settle")]
        (is (instance? Exception settle)
            "AttemptSucceeded cannot commit while the child holds the parent lock")
        (is (re-find #"lock timeout" (str (ex-message settle)))))

      (finally (debug/signal :release)))

    (is (= :transitioned (:outcome @attempt)))
    (is (= "ATTEMPT_DEBITING" (state-of "A1")))
    (is (= "TX_PAYING" (state-of "TX1")) "the blocked settle rolled back"))

  (testing "once the child has committed, the transaction settles"
    (kernel/drain! *ds* runtime)
    (is (= "TX_COMPLETED" (state-of "TX1")))))

;; --- the proof: a dispatch-time request transform ----------------------------

(defn- fresh-tree! []
  (let [aid (db/in-tx [tx *ds*]
              (let [a (compiler/compile-definition fbt/definition)]
                (artifact/insert! tx a)
                (:artifact-id a)))]
    (db/in-tx [tx *ds*]
      (kernel/create-tree! tx {:tree-id "T" :artifact-id aid :root-node-id "F1"
                               :root-node-type "File"}))
    aid))

(deftest before-dispatch-transforms-the-request-that-leaves
  ;; The production-safe effect transform: annotate a request before it reaches
  ;; the adapter (a trace id, a tenant, a redaction). It happens in the I/O zone,
  ;; outside the deterministic core, so it needs no artifact change.
  (let [aid (fresh-tree!)
        captured (atom nil)
        store (reify adapter/StoreAdapter
                (write [_ effect]
                  (reset! captured (:request-payload effect))
                  {:target "file_store" :record-key (:owner-node-id effect)
                   :payload (:request-payload effect)}))
        rt {:handlers {} :store-adapters {"file.writeReceive" store}
            :dispatcher {:batch-size 10}}]
    (db/in-tx [tx *ds*]
      (effect/insert! tx {:effect-instance-id "E1" :tree-id "T" :owner-node-id "B1"
                          :effect-kind :store :effect-name "file.writeReceive"
                          :effect-version "v1" :artifact-id aid
                          :idempotency-key "file.writeReceive:v1:T:B1" :correlation-key "B1"
                          :uncertainty-policy :retry-safe :request-payload {:batch-id "B1"}}))

    (debug/transform! :effect/before-dispatch
                      #(assoc-in % [:request-payload :trace-id] "TR-1")
                      :match #(= "store" (:effect-kind %)))
    (dispatcher/dispatch-once! *ds* rt)

    (is (= {:batch-id "B1" :trace-id "TR-1"} @captured)
        "the adapter saw the annotated request; the transform ran at dispatch time")))
