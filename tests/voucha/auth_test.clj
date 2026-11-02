(ns voucha.auth-test
  "Level-1 edge authentication (design: testing-api.md): an authenticator at the
  ingress seam resolves a transient credential into durable actor + claims frozen
  onto the event, or rejects it. Core owns only the contract; this exercises it
  with a toy backend."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [voucha.auth :as auth]
            [voucha.compiler :as compiler]
            [voucha.db :as db]
            [voucha.intercept :as intercept]
            [voucha.kernel :as kernel]
            [voucha.profiles.file-batch-transaction :as fbt]
            [voucha.repo.artifact :as artifact]
            [voucha.repo.event :as event]
            [voucha.test-db :as tdb :refer [*ds*]]))

(use-fixtures :once tdb/with-db)
(use-fixtures :each (fn [f] (tdb/clean-db #(try (f) (finally (intercept/reset!))))))

(defn- fresh-tree! []
  (let [aid (db/in-tx [tx *ds*]
              (let [a (compiler/compile-definition fbt/definition)]
                (artifact/insert! tx a)
                (:artifact-id a)))]
    (db/in-tx [tx *ds*]
      (kernel/create-tree! tx {:tree-id "T" :artifact-id aid :root-node-id "F1"
                               :root-node-type "File"}))
    aid))

(defn- event-count []
  (:n (db/in-tx [tx *ds*] (db/execute-one! tx ["SELECT COUNT(*) AS n FROM event_log"]))))

(defn- only-event []
  (db/in-tx [tx *ds*]
    (event/fetch tx (:event_id (db/execute-one! tx ["SELECT event_id FROM event_log LIMIT 1"])))))

(defn- submit! [attrs]
  (db/in-tx [tx *ds*]
    (kernel/submit-event! tx (merge {:tree-id "T" :target-node-id "F1"
                                     :event-type "FileReceived" :payload {:name "f" :batches []}}
                                    attrs))))

(deftest an-authenticator-freezes-claims-and-rejects-without-a-credential
  (fresh-tree!)
  (auth/install-authenticator!
   (fn [event]
     ;; A stand-in for a JWT/LDAP backend: the real thing does I/O here, which is
     ;; allowed because this is the ingress (I/O) zone, not a transition.
     (if-let [token (get-in event [:credentials :token])]
       {:actor (str "user:" token) :claims {:roles ["submitter"] :tenant "acme"}}
       (auth/reject! "no credential presented"))))

  (testing "no credential: submission is rejected and nothing is persisted"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"no credential"
                          (submit! {})))
    (is (zero? (event-count)) "a rejected event never reaches the log"))

  (testing "a rejection names the HTTP status an ingress should return"
    (let [ex (try (submit! {}) (catch clojure.lang.ExceptionInfo e e))]
      (is (= 401 (:http-status (ex-data ex))))
      (is (= :voucha.auth/unauthorized (:type (ex-data ex))))))

  (testing "with a credential: actor and claims are frozen onto the event"
    (submit! {:credentials {:token "alice"}})
    (let [ev (only-event)]
      (is (= "user:alice" (:actor ev)))
      (is (= {:roles ["submitter"] :tenant "acme"} (:claims ev))
          "the claims a Level-2 policy will read, recorded and replayable")
      (is (not (contains? ev :credentials))
          "the raw token resolved to claims and was never stored"))))

(deftest without-an-authenticator-submission-is-unchanged
  ;; The seam is inert when nobody installs an authenticator: existing behaviour
  ;; is preserved, and actor/claims are simply nil.
  (fresh-tree!)
  (submit! {})
  (let [ev (only-event)]
    (is (= 1 (event-count)))
    (is (nil? (:actor ev)))
    (is (nil? (:claims ev)))))
