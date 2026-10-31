(ns voucha.workload
  "Seeded, reproducible workloads over the SEPA instant payment example
  (test plan T0 §6.3).

  A workload is data: the files to submit, a *script* telling the fakes how the
  outside world will behave (which batches are rejected, which transactions are
  invalid, which screenings fail transiently, which rail attempts fail once or
  for good), and the *expected* terminal state of every node that follows from
  it. The same seed always yields the same workload, so a failing chaos or soak
  run is reproducible from its seed alone (§8 of the plan: a result without a
  seed is not a result).

  The expected states are an oracle in their own right -- a hand-derivable model
  of what the profile must do -- and `verify` checks them alongside the invariant
  oracle. Lives under dev/ with the rest of the test infrastructure."
  (:refer-clojure :exclude [run!])
  (:require [voucha.adapter :as adapter]
            [voucha.compiler :as compiler]
            [voucha.db :as db]
            [voucha.dispatcher :as dispatcher]
            [voucha.example.batch :as app]
            [voucha.fake :as fake]
            [voucha.invariants :as inv]
            [voucha.kernel :as kernel]
            [voucha.profiles.file-batch-transaction :as fbt]
            [voucha.repo.artifact :as artifact]
            [voucha.runtime :as runtime])
  (:import (java.util Random)))

;; The handlers give up after this many retries (voucha.example.batch/max-retries),
;; so a permanently failing rail sees attempts A0..A3.
(def ^:private attempts-before-giving-up 4)

(def default-options
  {:files 1
   :batches [1 3]                 ; per file, inclusive range
   :transactions [1 4]            ; per batch
   :amount [1 10000]
   :rails {"INST" 0.5 "SCT" 0.5}
   :p-invalid 0.10                ; amount 0: fails validation, never pays
   :p-rejected 0.10               ; control sum mismatch: batch rejected without paying
   :p-screening-transient 0.10    ; screening fails once, then clears
   :p-rail-fail-once 0.10         ; first attempt is rejected, second succeeds
   :p-rail-permanent 0.05})       ; every attempt is rejected: the transaction fails

;; --- generation ---------------------------------------------------------------------

(defn- pick [^Random r [lo hi]] (+ lo (.nextInt r (inc (- hi lo)))))
(defn- chance? [^Random r p] (< (.nextDouble r) p))
(defn- weighted [^Random r m]
  (let [x (* (.nextDouble r) (reduce + (vals m)))]
    (loop [[[k w] & more] (seq m) acc 0.0]
      (if (or (nil? more) (< x (+ acc w))) k (recur more (+ acc w))))))

(defn- gen-transaction
  [^Random r {:keys [amount rails p-invalid p-screening-transient p-rail-fail-once p-rail-permanent]} tx-id]
  (let [invalid? (chance? r p-invalid)
        rail (weighted r rails)
        screening-transient? (and (not invalid?) (chance? r p-screening-transient))
        rail-fate (cond invalid? :none
                        (chance? r p-rail-permanent) :permanent
                        (chance? r p-rail-fail-once) :fail-once
                        :else :success)
        attempt #(str tx-id "-A" %)]
    {:record {:tx-id tx-id :amount (if invalid? 0 (pick r amount)) :payment-type rail}
     :script {:screening-transient (when screening-transient? #{tx-id})
              :rail (case rail-fate
                      :fail-once {(attempt 0) :failed (attempt 1) :completed}
                      :permanent (into {} (map (fn [n] [(attempt n) :failed])) (range attempts-before-giving-up))
                      {})}
     :expected (merge {tx-id (if (or invalid? (= :permanent rail-fate)) "TX_FAILED" "TX_COMPLETED")}
                      (case rail-fate
                        :none {}
                        :success {(attempt 0) "ATTEMPT_SUCCEEDED"}
                        :fail-once {(attempt 0) "ATTEMPT_FAILED" (attempt 1) "ATTEMPT_SUCCEEDED"}
                        :permanent (into {} (map (fn [n] [(attempt n) "ATTEMPT_FAILED"])) (range attempts-before-giving-up))))}))

(defn- gen-batch
  [^Random r {:keys [transactions p-rejected] :as opts} batch-id]
  (let [rejected? (chance? r p-rejected)
        txs (mapv #(gen-transaction r opts (str batch-id "-T" %)) (range (pick r transactions)))
        records (mapv :record txs)
        control-sum (reduce + 0 (map :amount records))]
    ;; A rejected batch declares a control sum its records do not add up to.
    {:batch {:batch-id batch-id :transactions records
             :control-sum (if rejected? (inc control-sum) control-sum) :count (count records)}
     :script {:rejected (when rejected? #{batch-id})
              :screening-transient (into #{} (mapcat (comp :screening-transient :script)) txs)
              :rail (into {} (map (comp :rail :script)) txs)}
     ;; A rejected batch creates no transaction nodes: their ids must be absent.
     :expected (if rejected?
                 {batch-id "BATCH_REJECTED"}
                 (into {batch-id "BATCH_COMPLETED"} (map :expected) txs))
     :absent (if rejected? (into #{} (mapcat (comp keys :expected)) txs) #{})}))

(defn- merge-scripts [scripts]
  {:rejected (into #{} (mapcat :rejected) scripts)
   :screening-transient (into #{} (mapcat :screening-transient) scripts)
   :rail (into {} (map :rail) scripts)})

(defn generate
  "A reproducible workload for `seed`. Options override `default-options`."
  ([seed] (generate seed {}))
  ([seed options]
   (let [opts (merge default-options options)
         r (Random. (long seed))
         files (vec (for [i (range (:files opts))
                          :let [tree-id (str "W" seed "-F" i)
                                batches (mapv #(gen-batch r opts (str tree-id "-B" %)) (range (pick r (:batches opts))))]]
                      {:tree-id tree-id
                       :root-node-id tree-id
                       :payload {:name (str tree-id ".pain") :batches (mapv :batch batches)}
                       :script (merge-scripts (map :script batches))
                       :expected (into {tree-id "FILE_COMPLETED"} (map :expected) batches)
                       :absent (into #{} (mapcat :absent) batches)}))]
     {:seed seed
      :options opts
      :files files
      :script (merge-scripts (map :script files))
      :expected (into {} (map :expected) files)
      :absent (into #{} (mapcat :absent) files)})))

;; --- adapters that follow the script -------------------------------------------------

(defrecord ScriptedAdapter [outcome-fn calls]
  adapter/EffectAdapter
  (dispatch [_ e]
    (swap! calls conj {:op :dispatch :effect-instance-id (:effect-instance-id e)
                       :owner (:owner-node-id e)})
    (outcome-fn e))
  (query-status [_ e]
    (swap! calls conj {:op :query-status :effect-instance-id (:effect-instance-id e)})
    ;; Asked after an uncertain send: the fake world always has an answer.
    (let [o (outcome-fn e)] (if (= :dispatched (:outcome o)) {:outcome :completed} o))))

(defn- scripted [f] (->ScriptedAdapter f (atom [])))
(defn calls [adapter*] @(:calls adapter*))

(defn rail-outcome
  "What the script says this attempt's rail call does: :completed unless scripted."
  [script attempt-id]
  (get-in script [:rail attempt-id] :completed))

(defn adapters
  "Effect and store adapters for a workload. Returns {:effect-adapters .. :store-adapters ..}."
  [{:keys [script]}]
  (let [ok (constantly {:outcome :completed})]
    {:effect-adapters
     {"screening.check"
      (scripted (fn [e] (if (and (contains? (:screening-transient script) (:owner-node-id e))
                                 (zero? (get-in e [:request-payload :attempt] 0)))
                          {:outcome :failed :payload {:error "screening service busy"}}
                          {:outcome :completed})))
      "account.debit" (scripted ok)
      "account.reverse" (scripted ok)
      ;; SCT Inst is asynchronous: the send leaves; `deliver-inst-callbacks!` answers per script.
      "rail.inst.send" (scripted (constantly {:outcome :dispatched}))
      "rail.sct.submit"
      (scripted (fn [e] (if (= :failed (rail-outcome script (:owner-node-id e)))
                          {:outcome :failed :payload {:error "rail rejected"}}
                          {:outcome :completed :external-request-id (str "SCT-" (:owner-node-id e))})))}
     :store-adapters {"file.writeReceive" (fake/store-adapter)
                      "file.writeResult" (fake/store-adapter)}}))

(def default-config
  {:dispatcher {:batch-size 50 :backoff-ms 20 :max-attempts 3 :lease-ms (* 60 1000)}
   :event {:max-attempts 3}})

(defn runtime
  "A runtime for the workload: the reference handlers plus scripted adapters."
  ([workload] (runtime workload {}))
  ([workload config]
   (runtime/runtime (merge {:handlers app/handlers :config (merge default-config config)}
                           (adapters workload)))))

;; --- driving ------------------------------------------------------------------------

(defn submit!
  "Compile and store the artifact once, create every tree, submit every file."
  [ds {:keys [files]}]
  (let [aid (db/in-tx [tx ds]
              (let [a (compiler/compile-definition fbt/definition)]
                (artifact/insert! tx a) (:artifact-id a)))]
    (doseq [{:keys [tree-id root-node-id payload]} files]
      (db/in-tx [tx ds]
        (kernel/create-tree! tx {:tree-id tree-id :artifact-id aid :root-node-id root-node-id
                                 :root-node-type "File" :profile-name fbt/profile-name
                                 :profile-version fbt/profile-version})
        (kernel/submit-event! tx {:event-id (str tree-id ":received") :tree-id tree-id
                                  :target-node-id root-node-id :event-type "FileReceived"
                                  :payload payload})))
    aid))

(defn deliver-inst-callbacks!
  "The instant rail answers out of band. Every dispatched send gets the callback
  the script assigns to its attempt."
  [ds {:keys [script]}]
  (let [pending (db/in-tx [tx ds]
                  (db/execute! tx ["SELECT effect_instance_id, owner_node_id FROM effect_instance
                                    WHERE effect_name = 'rail.inst.send' AND status = 'DISPATCHED'"]))]
    (doseq [{:keys [effect_instance_id owner_node_id]} pending]
      (dispatcher/deliver-callback! ds {:effect-instance-id effect_instance_id
                                        :outcome (rail-outcome script owner_node_id)
                                        :payload (if (= :failed (rail-outcome script owner_node_id))
                                                   {:error "rail rejected"}
                                                   {:rail-ref (str "INST-" owner_node_id)})}))
    (count pending)))

(defn file-states
  "Current state of every file's root node: {tree-id state}."
  [ds {:keys [files]}]
  (into {} (for [{:keys [tree-id root-node-id]} files]
             [tree-id (:local_state (db/in-tx [tx ds]
                                      (db/execute-one! tx ["SELECT local_state FROM state_node WHERE tree_id = ? AND node_id = ?"
                                                           tree-id root-node-id])))])))

(def file-terminal #{"FILE_COMPLETED" "FILE_PERMANENT_FAILURE"})

(defn drive!
  "Run the world forward -- process events, run dispatchers, deliver instant-rail callbacks --
  until every file is terminal or `max-steps` passes. Returns
  {:settled? bool :steps n :files {tree-id state}}."
  [ds rt workload & {:keys [max-steps pause-ms] :or {max-steps 400 pause-ms 15}}]
  (loop [n 1]
    (kernel/drain! ds rt)
    (dispatcher/run-once! ds rt)
    (deliver-inst-callbacks! ds workload)
    (kernel/drain! ds rt)
    (let [states (file-states ds workload)]
      (cond
        (every? file-terminal (vals states)) {:settled? true :steps n :files states}
        (>= n max-steps) {:settled? false :steps n :files states}
        :else (do (Thread/sleep (long pause-ms)) (recur (inc n)))))))

;; --- verification --------------------------------------------------------------------

(defn verify
  "Compare every node's actual state with the workload's expectation, check that
  nodes a rejected batch never created are absent, and run the invariant oracle.
  Returns {:ok? bool :mismatches [..] :present-but-expected-absent [..] :invariants ..}."
  [ds {:keys [expected absent]} & {:keys [handlers] :or {handlers app/handlers}}]
  (db/in-tx [tx ds]
    (let [actual (into {} (map (juxt :node_id :local_state))
                       (db/execute! tx ["SELECT node_id, local_state FROM state_node"]))
          mismatches (vec (for [[nid want] (sort expected)
                                :let [have (get actual nid)]
                                :when (not= want have)]
                            {:node-id nid :expected want :actual have}))
          ghosts (vec (filter #(contains? actual %) (sort absent)))
          invariants (inv/check-all tx :handlers handlers)]
      {:ok? (and (empty? mismatches) (empty? ghosts) (:ok? invariants))
       :mismatches mismatches
       :present-but-expected-absent ghosts
       :invariants invariants})))

(defn run!
  "Submit, drive, verify. Returns the drive and verify results together."
  ([ds workload] (run! ds workload {}))
  ([ds workload config]
   (let [rt (runtime workload config)]
     (submit! ds workload)
     (let [driven (drive! ds rt workload)
           verified (verify ds workload)]
       (assoc verified :settled? (:settled? driven) :steps (:steps driven) :file-states (:files driven)
              :ok? (and (:settled? driven) (:ok? verified)))))))
