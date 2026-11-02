(ns voucha.policy-test
  "Phase C (design: testing-api.md): the Level-2 Decide tier.

  A deterministic authorization policy, declared and version-pinned in the
  artifact, runs at `:transition/constraints-checked` over the claims Level-1
  froze onto the event. A denial is recorded on the event and a grant leaves its
  evidence in the checkpoint, so an authorization verdict is as auditable and
  replayable as any hierarchical constraint. Core owns the mechanism; the guard
  function is registered by the application (here, the test)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [voucha.compiler :as compiler]
            [voucha.db :as db]
            [voucha.example.batch :as app]
            [voucha.inspect :as inspect]
            [voucha.intercept :as intercept]
            [voucha.kernel :as kernel]
            [voucha.policy :as policy]
            [voucha.profiles.file-batch-transaction :as fbt]
            [voucha.repo.artifact :as artifact]
            [voucha.repo.event :as event]
            [voucha.repo.node :as node]
            [voucha.repo.transition-log :as tlog]
            [voucha.test-db :as tdb :refer [*ds*]]))

(def ^:private guard-name "actor-in-role")

(use-fixtures :once tdb/with-db)
(use-fixtures :each
  (fn [f]
    (tdb/clean-db
     #(try
        ;; The guard the profile pins to. Deterministic: a pure function of the
        ;; frozen claims. Its read-set is the evidence that lands in the checkpoint.
        (policy/register! guard-name "v1"
                          (fn [{:keys [event params]}]
                            (let [roles (set (:roles (:claims event)))
                                  need (:role params)]
                              (if (contains? roles need)
                                {:ok? true :read-set {:actor (:actor event)
                                                      :roles (vec (sort roles))}}
                                {:ok? false
                                 :reason (format "actor %s lacks role %s"
                                                 (or (:actor event) "<anonymous>") need)
                                 :read-set {:actor (:actor event)
                                            :roles (vec (sort roles))}}))))
        (f)
        (finally
          (intercept/reset!)
          (policy/unregister! guard-name "v1"))))))

;; --- a profile whose File-parse transition is guarded by a Level-2 policy ------

(defn- with-policy-on
  "Attach `policy-names` to the transition matching (node-type, on)."
  [d node-type on policy-names]
  (update d :transitions
          (fn [ts] (mapv (fn [t] (if (and (= node-type (:node-type t)) (= on (:on t)))
                                   (assoc t :policies policy-names)
                                   t))
                         ts))))

(defn- guarded-definition [version]
  (-> fbt/definition
      (assoc :policies {"approver-only" {:version version :guard guard-name
                                         :params {:role "approver"}}})
      (with-policy-on "File" "FileReceived" ["approver-only"])))

(defn- new-tree! [definition]
  (let [{:keys [artifact-id] :as compiled} (compiler/compile-definition definition)]
    (db/in-tx [tx *ds*]
      (artifact/insert! tx compiled)
      (kernel/create-tree! tx {:tree-id "T" :artifact-id artifact-id :root-node-id "F1"
                               :root-node-type "File"}))
    artifact-id))

(defn- submit! [rt {:keys [actor claims]}]
  (db/in-tx [tx *ds*]
    (kernel/submit-event! tx {:event-id (str actor ":parse") :tree-id "T"
                              :target-node-id "F1" :event-type "FileReceived"
                              :payload {:name "f" :batches []}
                              :actor actor :claims claims}))
  (kernel/drain! *ds* rt))

(defn- state-of [node-id]
  (:local-state (db/in-tx [tx *ds*] (node/fetch tx "T" node-id))))

(defn- file-history []
  (db/in-tx [tx *ds*] (tlog/history tx "T" "F1")))

(def ^:private approver {:actor "alice" :claims {:roles ["approver" "clerk"]}})
(def ^:private clerk {:actor "bob" :claims {:roles ["clerk"]}})

;; --- the decision drives the kernel, and is recorded --------------------------

(deftest an-authorized-actor-passes-and-the-grant-is-checkpointed
  (new-tree! (guarded-definition "v1"))
  (policy/install-enforcer!)
  (let [rt {:handlers app/handlers :event {:max-attempts 3}}
        [outcome] (submit! rt approver)]
    (is (= :transitioned (:outcome outcome)))
    (is (= "WRITING_RECEIVE_FILE" (state-of "F1")) "the policy allowed the transition")

    (testing "the policy's evidence is in the transition's checkpointed read-set"
      (let [read-set (:context-read-set (last (file-history)))
            evidence (get-in read-set [:policies :approver-only])]
        (is (= "allow" (:decision evidence)))
        (is (= "alice" (:actor evidence)))
        (is (= ["approver" "clerk"] (:roles evidence))
            "an auditor can see exactly what the policy read to allow this")))))

(deftest an-unauthorized-actor-is-denied-and-the-event-records-why
  (new-tree! (guarded-definition "v1"))
  (policy/install-enforcer!)
  (let [rt {:handlers app/handlers :event {:max-attempts 3}}
        [outcome] (submit! rt clerk)]
    (is (= :rejected (:outcome outcome)))
    (is (= "FILE_NEW" (state-of "F1")) "the node did not move")

    (testing "the rejected event names the policy that blocked it"
      (let [ev (db/in-tx [tx *ds*] (event/fetch tx "bob:parse"))]
        (is (= "REJECTED" (:status ev)))
        (is (re-find #"lacks role approver" (:last-error ev))
            "reconstructing 'why was this denied?' needs no live re-evaluation")))

    (testing "no checkpoint was written for a denied transition"
      (is (empty? (file-history))))))

(deftest why-stuck-explains-a-policy-denial-for-a-given-actor
  (new-tree! (guarded-definition "v1"))
  (policy/install-enforcer!)
  (db/in-tx [tx *ds*]
    (testing "the same guard the kernel runs explains the block, per actor"
      (let [stuck (inspect/why-stuck tx "T" "F1" clerk)]
        (is (not (contains? (set (:allowed-next-events stuck)) "FileReceived")))
        (is (some #(re-find #"lacks role approver" %)
                  (mapcat :blocked-by (:blocked-transitions stuck)))))
      (let [ok (inspect/why-stuck tx "T" "F1" approver)]
        (is (contains? (set (:allowed-next-events ok)) "FileReceived"))))
    (testing "with no actor context, a guarded transition is flagged, not judged"
      (let [ts (inspect/transitions-from tx "T" "F1")
            parse (first (filter #(= "FileReceived" (:event %)) ts))]
        (is (:requires-actor parse))))))

;; --- the version pin ----------------------------------------------------------

(deftest a-pinned-guard-version-that-is-not-registered-fails-closed
  ;; The artifact pins v2; only v1 is registered. Even an actor who would pass v1
  ;; is denied, because the pinned computation cannot be reproduced with the guard
  ;; that is loaded. This is what makes a version-pinned replay honest.
  (new-tree! (guarded-definition "v2"))
  (policy/install-enforcer!)
  (let [rt {:handlers app/handlers :event {:max-attempts 3}}
        [outcome] (submit! rt approver)]
    (is (= :rejected (:outcome outcome)))
    (is (= "FILE_NEW" (state-of "F1")))
    (let [ev (db/in-tx [tx *ds*] (event/fetch tx "alice:parse"))]
      (is (re-find #"actor-in-role@v2, which is not registered" (:last-error ev))))))

(deftest the-verdict-is-a-pure-function-of-frozen-claims-and-artifact
  ;; Determinism is the property that makes the decision replayable: the same
  ;; frozen claims against the same pinned policy yield the same verdict, every
  ;; time, with no reference to the outside world.
  (let [{:keys [specs]} (compiler/compile-definition (guarded-definition "v1"))
        t (first (filter #(= "FileReceived" (:on %)) (:transitions specs)))
        decide (fn [ctx] (policy/evaluate-policies specs t {:event ctx}))]
    (is (= (decide approver) (decide approver)))
    (is (:ok? (decide approver)))
    (is (not (:ok? (decide clerk))))
    (is (not= (decide approver) (decide clerk)))))

(deftest an-unguarded-transition-is-untouched-by-the-enforcer
  ;; The enforcer is installed, but a transition that declares no policy is
  ;; evaluated exactly as before -- any actor, no claims, still passes.
  (new-tree! (guarded-definition "v1"))
  (policy/install-enforcer!)
  (let [rt {:handlers app/handlers :event {:max-attempts 3}}]
    (submit! rt approver)
    (is (= "WRITING_RECEIVE_FILE" (state-of "F1")))
    ;; File WRITING_RECEIVE_FILE --ReceiveFileWritten--> FILE_PROCESS_BATCH declares no policy.
    (db/in-tx [tx *ds*]
      (kernel/submit-event! tx {:event-id "anon:written" :tree-id "T" :target-node-id "F1"
                                :event-type "ReceiveFileWritten"}))
    (kernel/drain! *ds* rt)
    (is (= "FILE_BATCHES_PROCESSED" (state-of "F1"))
        "an unguarded transition needs no actor (the empty file then moves itself on)")))

;; --- the compiler pins and validates the declaration --------------------------

(deftest the-compiler-requires-a-policy-to-be-declared-and-versioned
  (testing "a transition may not guard on an undeclared policy"
    (let [bad (with-policy-on fbt/definition "File" "FileReceived" ["ghost"])]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"not declared in top-level :policies"
                            (compiler/compile-definition bad)))))
  (testing "a declared policy must carry a version"
    (let [bad (assoc fbt/definition :policies {"approver-only" {:guard guard-name}})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"declares no :version"
                            (compiler/compile-definition bad)))))
  (testing "a declared policy must name a guard"
    (let [bad (assoc fbt/definition :policies {"approver-only" {:version "v1"}})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"declares no :guard"
                            (compiler/compile-definition bad))))))

(deftest the-policy-declaration-changes-the-artifact-identity
  ;; Pinning means the policy is part of the content-addressed artifact: change
  ;; the policy and you get a different artifact, so a tree's governing policy
  ;; travels with its pinned artifact id.
  (let [plain (:artifact-id (compiler/compile-definition fbt/definition))
        guarded (:artifact-id (compiler/compile-definition (guarded-definition "v1")))
        v2 (:artifact-id (compiler/compile-definition (guarded-definition "v2")))]
    (is (not= plain guarded) "adding a policy changes the artifact")
    (is (not= guarded v2) "bumping the pinned version changes the artifact")))
