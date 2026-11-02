(ns voucha.effect-transform-test
  "Phase B (design: testing-api.md): the effect seams carry both production
  transforms and injected faults, and neither needs a bespoke adapter.

  `voucha.effect` is the production, I/O-zone home for dispatch-time request
  rewrites -- annotate, redact -- the everyday cross-cutting path. `voucha.debug`
  is the dev-only fault home: fabricate an outcome, or crash at the 'sent but not
  recorded' boundary and watch the uncertainty policy own the result. Both ride
  the same installation mechanism; the tier is the difference."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [voucha.compiler :as compiler]
            [voucha.db :as db]
            [voucha.debug :as debug]
            [voucha.dispatcher :as dispatcher]
            [voucha.effect :as fx]
            [voucha.example.batch :as app]
            [voucha.fake :as fake]
            [voucha.intercept :as intercept]
            [voucha.kernel :as kernel]
            [voucha.profiles.file-batch-transaction :as fbt]
            [voucha.repo.artifact :as artifact]
            [voucha.repo.effect :as effect]
            [voucha.repo.node :as node]
            [voucha.test-db :as tdb :refer [*ds*]]))

(use-fixtures :once tdb/with-db)
(use-fixtures :each (fn [f] (tdb/clean-db #(try (f) (finally (intercept/reset!))))))

(defn- runtime [store]
  {:handlers app/handlers
   :event {:max-attempts 3}
   :dispatcher {:batch-size 10 :backoff-ms 60 :max-attempts 3}
   :store-adapters {"file.writeReceive" store}})

(defn- new-tree! []
  (let [aid (db/in-tx [tx *ds*]
              (let [a (compiler/compile-definition fbt/definition)]
                (artifact/insert! tx a) (:artifact-id a)))]
    (db/in-tx [tx *ds*]
      (kernel/create-tree! tx {:tree-id "T" :artifact-id aid :root-node-id "F1"
                               :root-node-type "File"}))
    aid))

(defn- state-of [node-id]
  (:local-state (db/in-tx [tx *ds*] (node/fetch tx "T" node-id))))

(defn- the-effect [name*]
  (first (filter #(= name* (:effect-name %))
                 (db/in-tx [tx *ds*] (effect/by-owner tx "T" "F1")))))

(defn- to-store-effect!
  "Drive F1 to WRITING_RECEIVE_FILE with the file.writeReceive store effect CREATED."
  [rt]
  (db/in-tx [tx *ds*]
    (kernel/submit-event! tx {:event-id (str (gensym "ev")) :tree-id "T"
                              :target-node-id "F1" :event-type "FileReceived"
                              :payload {:name "reference-file" :batches []}}))
  (kernel/drain! *ds* rt))

(defn- request-the-adapter-saw [store]
  (:payload (first (fake/writes store))))

;; --- production transforms (voucha.effect, I/O zone) --------------------------

(deftest annotate-requests-merges-fields-into-what-the-adapter-sees
  ;; The everyday cross-cutting case: stamp a trace id onto every request without
  ;; touching a handler or the artifact. No custom adapter -- the fake records
  ;; exactly what left.
  (new-tree!)
  (let [store (fake/store-adapter)
        rt (runtime store)]
    (to-store-effect! rt)
    (fx/annotate-requests! {:trace-id "TR-9"} :match #(= "store" (:effect-kind %)))
    (dispatcher/dispatch-once! *ds* rt)

    (let [sent (request-the-adapter-saw store)]
      (is (= "TR-9" (:trace-id sent)) "the annotation reached the wire")
      (is (= "reference-file" (:name sent)) "and the original request survived the merge"))))

(deftest annotate-can-compute-a-per-request-value
  (new-tree!)
  (let [store (fake/store-adapter)
        rt (runtime store)]
    (to-store-effect! rt)
    (fx/annotate-requests! (fn [effect] {:owner (:owner-node-id effect)})
                           :match #(= "store" (:effect-kind %)))
    (dispatcher/dispatch-once! *ds* rt)
    (is (= "F1" (:owner (request-the-adapter-saw store))))))

(deftest redact-requests-removes-fields-before-they-leave
  (new-tree!)
  (let [store (fake/store-adapter)
        rt (runtime store)]
    (to-store-effect! rt)
    (fx/redact-requests! [:batch-count] :match #(= "store" (:effect-kind %)))
    (dispatcher/dispatch-once! *ds* rt)

    (let [sent (request-the-adapter-saw store)]
      (is (nil? (:batch-count sent)) "the redacted field never reached the adapter")
      (is (= "reference-file" (:name sent)) "but the rest of the request did"))))

(deftest a-non-matching-transform-leaves-the-request-untouched
  (new-tree!)
  (let [store (fake/store-adapter)
        rt (runtime store)]
    (to-store-effect! rt)
    ;; Scoped to external effects; this is a store effect, so it must not fire.
    (fx/annotate-requests! {:trace-id "NOPE"} :match #(= "external" (:effect-kind %)))
    (dispatcher/dispatch-once! *ds* rt)
    (is (nil? (:trace-id (request-the-adapter-saw store))))))

;; --- injected faults (voucha.debug, dev only) ---------------------------------

(deftest fabricating-a-failure-drives-the-node-down-its-failure-path
  ;; The adapter succeeds. `fail-effect!` rewrites the recorded outcome to :failed
  ;; at :effect/before-apply, so the failure transition is exercised with no
  ;; failing adapter -- the outcome the node acts on is the fabricated one.
  (new-tree!)
  (let [store (fake/store-adapter)
        rt (runtime store)]
    (to-store-effect! rt)
    (is (= "WRITING_RECEIVE_FILE" (state-of "F1")))
    (debug/fail-effect! :error "pretend the store rejected it")

    (dispatcher/run-once! *ds* rt)
    (is (= "FAILED" (:status (the-effect "file.writeReceive")))
        "the fabricated outcome, not the adapter's, is what was recorded")

    (kernel/drain! *ds* rt)
    (is (= "FILE_PERMANENT_FAILURE" (state-of "F1"))
        "and it drove the owner node down ReceiveFileWriteFailed")))

(deftest rewrite-outcome-can-shape-the-recorded-payload
  (new-tree!)
  (let [store (fake/store-adapter)
        rt (runtime store)]
    (to-store-effect! rt)
    (debug/rewrite-outcome! (fn [o] (assoc-in o [:payload :injected] "here")))
    (dispatcher/run-once! *ds* rt)
    (is (= "here" (get-in (the-effect "file.writeReceive") [:completion-payload :injected]))
        "the after-step seam rewrote the outcome the machine went on to record")))

(deftest a-crash-after-the-external-call-leaves-the-effect-uncertain
  ;; The highest-value fault: crash at :effect/after-call is 'sent but not
  ;; recorded'. The claim already committed DISPATCHING; the write ran; the crash
  ;; aborts before the outcome is recorded. The effect is left in the one honest
  ;; state, and -- crucially -- the node did not advance on an outcome we never saw.
  (new-tree!)
  (let [store (fake/store-adapter)
        rt (runtime store)]
    (to-store-effect! rt)
    (debug/crash! :effect/after-call)

    (is (thrown? clojure.lang.ExceptionInfo (dispatcher/dispatch-once! *ds* rt)))
    (is (= "DISPATCHING" (:status (the-effect "file.writeReceive")))
        "DISPATCHING is the honest 'we may have sent it' state")
    (is (= "WRITING_RECEIVE_FILE" (state-of "F1"))
        "no false progress: the node stayed put on an unrecorded outcome")))

(deftest the-effect-seams-are-inert-until-something-is-installed
  (new-tree!)
  (let [store (fake/store-adapter)
        rt (runtime store)]
    (to-store-effect! rt)
    (dispatcher/run-once! *ds* rt)
    (kernel/drain! *ds* rt)
    (testing "with no interceptor the store effect completes normally"
      (is (= "COMPLETED" (:status (the-effect "file.writeReceive"))))
      (is (= "reference-file" (:name (request-the-adapter-saw store)))))))
