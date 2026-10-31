(ns voucha.chaos
  "The chaos harness controller (test plan T0 §6.4): runs the reference workload
  across kernel, dispatcher and rail *processes* against one PostgreSQL, kills
  them, respawns them, and checks the invariant oracle throughout.

  What is deterministic from the seed: the workload (`voucha.workload`) and the
  kill plan (which step, which role). What is not: the exact interleaving --
  processes and the OS scheduler see to that. So a result records the seed, the
  plan, and every kill and respawn that actually happened with the step it
  happened at; reproduction is the seed plus the recorded schedule (plan §8).

  Against the test harness's embedded PostgreSQL the database dies with the test
  JVM. Run the same controller against an external server (any `:jdbc-url`) and a
  failing run leaves its database intact for inspection."
  (:refer-clojure :exclude [run!])
  (:require [clojure.java.io :as io]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [voucha.db :as db]
            [voucha.example.batch :as app]
            [voucha.invariants :as inv]
            [voucha.workload :as workload])
  (:import (java.util Random)
           (java.util.concurrent TimeUnit)))

;; One controller step: sleep this long, then look at the world.
(def default-step-ms 200)
;; Check the invariant oracle every this many steps while running.
(def default-check-every 5)
;; A worker killed or dead is restarted after this many steps.
(def default-respawn-after-steps 2)
;; Give a killed process this long to actually exit.
(def ^:private destroy-wait-ms 5000)
;; Mid-run invariant bounds: an effect may sit in DISPATCHING for the lease plus
;; this grace (the reclaim needs a dispatcher alive); an event may wait while a
;; killed worker restarts.
(def ^:private dispatching-grace-ms 30000)
(def ^:private received-bound-ms 60000)

;; --- processes ----------------------------------------------------------------------

(defn- spawn-worker
  [{:keys [jdbc-url spec-file log-dir worker-flags]} role id {:keys [point on-hit] :as kill}]
  (let [java (str (System/getProperty "java.home") "/bin/java")
        cp (System/getProperty "java.class.path")
        args (cond-> [java "-cp" cp "clojure.main" "-m" "voucha.chaos-worker" jdbc-url role (str id) spec-file]
               kill (into ["--kill-at" (str point) "--kill-on-hit" (str (or on-hit 1))])
               true (into (get worker-flags role []))
               true (into (get worker-flags [role id] [])))
        log (io/file log-dir (format "%s-%s-%d.log" role id (System/nanoTime)))
        proc (.start (doto (ProcessBuilder. ^java.util.List args)
                       (.redirectErrorStream true)
                       (.redirectOutput log)))]
    {:role role :id id :process proc :log log :armed kill}))

(defn- alive? [w] (.isAlive ^Process (:process w)))

(def ^:private ready-timeout-ms 90000)

(defn- await-ready!
  "Block until every worker has printed READY (or died), so faults and kills land
  on live workers rather than on JVMs still booting. Returns the ms it took."
  [workers]
  (let [t0 (System/currentTimeMillis)]
    (loop []
      (let [ready? (fn [w] (or (not (alive? w))
                               (and (.exists ^java.io.File (:log w))
                                    (.contains ^String (slurp (:log w)) "READY"))))]
        (cond (every? ready? workers) (- (System/currentTimeMillis) t0)
              (> (- (System/currentTimeMillis) t0) ready-timeout-ms)
              (throw (ex-info "chaos workers did not become ready" {:workers (mapv #(select-keys % [:role :id]) workers)}))
              :else (do (Thread/sleep 100) (recur)))))))

(defn- kill-worker! [w]
  (let [^Process p (:process w)]
    (.destroyForcibly p)
    (.waitFor p destroy-wait-ms TimeUnit/MILLISECONDS)))

(defn- initial-workers
  [{:keys [kernels dispatchers rails armed] :or {kernels 1 dispatchers 1 rails 1 armed {}} :as opts}]
  (vec (for [[role n] [["kernel" kernels] ["dispatcher" dispatchers] ["rail" rails]]
             id (range n)]
         (spawn-worker opts role id (get armed [role id])))))

;; --- kill plans -----------------------------------------------------------------------

(defn random-kill-plan
  "`count` kills at steps spaced `every` apart, each on a seeded random live role.
  Deterministic from the seed."
  [seed {:keys [count every roles] :or {count 3 every 15 roles ["kernel" "dispatcher" "rail"]}}]
  (let [r (Random. (long seed))]
    (vec (for [i (range count)]
           {:step (* every (inc i)) :role (nth roles (.nextInt r (clojure.core/count roles)))}))))

;; --- database faults (T3) ----------------------------------------------------------------
;; Server-side, no proxy: workers name their connections `voucha-<role>-<id>`, so one
;; role's backends can be terminated -- mid-statement, mid-transaction -- from here.
;; Disk-full needs a real server and is out of scope for the embedded database.

(defn terminate-backends!
  "Kill every live backend of `role` (all roles when nil) except our own. Returns
  how many were terminated."
  [ds role]
  (let [pattern (str "voucha-" (or role "%") "-%")]
    (:n (db/in-tx [tx ds]
          (db/execute-one! tx ["SELECT COUNT(pg_terminate_backend(pid)) AS n FROM pg_stat_activity
                                 WHERE application_name LIKE ? AND pid <> pg_backend_pid()
                                   AND state <> 'idle'" pattern])))))

(defn- apply-faults!
  "Run every fault due at `step`. A fault: {:fault :terminate-backends :role r
  :every n :from-step a :until-step b}."
  [ds step faults note!]
  (doseq [{:keys [fault role every from-step until-step] :or {every 1 from-step 1 until-step Long/MAX_VALUE}} faults
          :when (and (<= from-step step until-step) (zero? (mod (- step from-step) every)))]
    (case fault
      :terminate-backends
      (let [n (terminate-backends! ds role)]
        (note! {:step step :event :fault :fault fault :role role :count n})))))

;; --- the run ----------------------------------------------------------------------------

(defn- check-invariants [ds config]
  (inv/check-all ds
                 :dispatching-bound-ms (+ (get-in config [:dispatcher :lease-ms] (* 5 60 1000))
                                          dispatching-grace-ms)
                 :received-bound-ms received-bound-ms))

(defn run!
  "Run `workload` to quiescence under chaos. Options:

    :jdbc-url  (required)  the shared database
    :kernels :dispatchers :rails   worker counts (default 1 each)
    :armed     {[role id] {:point kw :on-hit n}}   deterministic kills at a seam
    :kill-plan [{:step n :role r (:id i)}]          controller kills by step
    :respawn?  restart killed/dead workers (default true)
    :worker-flags {role-or-[role id] [flag ...]}  extra chaos-worker flags (sleep-at,
               lock-timeout-ms, pool-size, ...), also applied to respawns
    :faults    [{:fault :terminate-backends :role r :every n :from-step a :until-step b}]
    :config    runtime config for the workers (lease, backoff, poll interval)
    :max-steps :step-ms :check-every :dir (for spec, logs and the result)

  Returns {:ok? :seed :settled? :steps :schedule :violations :verify ...}."
  [ds workload {:keys [jdbc-url kill-plan faults respawn? config max-steps step-ms check-every dir]
                :or {kill-plan [] faults [] respawn? true config {} max-steps 300
                     step-ms default-step-ms check-every default-check-every}
                :as opts}]
  (let [dir (io/file (or dir (str (System/getProperty "java.io.tmpdir") "/voucha-chaos")))
        _ (.mkdirs dir)
        spec-file (io/file dir (format "spec-%s.edn" (:seed workload)))
        _ (spit spec-file (pr-str {:workload workload :config config}))
        opts (assoc opts :spec-file (str spec-file) :log-dir dir)
        plan-by-step (group-by :step kill-plan)
        schedule (atom [])
        note! (fn [m] (swap! schedule conj m))]
    (workload/submit! ds workload)
    (let [workers (initial-workers opts)
          boot-ms (await-ready! workers)]
      (note! {:step 0 :event :ready :count (count workers) :ms boot-ms})
    (loop [workers workers step 1 violations [] deferred []]
      (Thread/sleep (long step-ms))
      (let [;; 1. workers that died on their own (an armed kill fired)
            workers (mapv (fn [w] (if (and (not (alive? w)) (not (:dead w)))
                                    (do (note! {:step step :event :died :role (:role w) :id (:id w)})
                                        (assoc w :dead step))
                                    w))
                          workers)
            ;; 2. planned kills for this step, plus any deferred from earlier steps. A
            ;;    kill whose victim is not alive right now (still dead from an earlier
            ;;    kill, not yet respawned) is carried forward, never dropped: the
            ;;    recorded plan must be what actually happened.
            [workers deferred]
            (reduce (fn [[ws pending] {:keys [role id] :as kill}]
                      (let [victim (first (filter #(and (alive? %) (= role (:role %))
                                                        (or (nil? id) (= id (:id %))))
                                                  ws))]
                        (if victim
                          (do (kill-worker! victim)
                              (note! {:step step :event :killed :role role :id (:id victim)})
                              [(mapv #(if (= % victim) (assoc % :dead step) %) ws) pending])
                          [ws (conj pending kill)])))
                    [workers []]
                    (into deferred (get plan-by-step step)))
            ;; 3. respawns, a couple of steps after death
            workers (mapv (fn [w] (if (and (:dead w) respawn? (>= (- step (:dead w)) default-respawn-after-steps))
                                    (do (note! {:step step :event :respawned :role (:role w) :id (:id w)})
                                        (spawn-worker opts (:role w) (:id w) nil))
                                    w))
                          workers)
            ;; 4. database faults due this step
            _ (apply-faults! ds step faults note!)
            ;; 5. the oracle, periodically
            violations (if (zero? (mod step check-every))
                         (let [r (check-invariants ds config)]
                           (when-not (:ok? r) (note! {:step step :event :violations :count (count (:violations r))}))
                           (into violations (map #(assoc % :step step)) (:violations r)))
                         violations)
            states (workload/file-states ds workload)
            settled? (every? workload/file-terminal (vals states))]
        (if (or settled? (>= step max-steps))
          (do (doseq [w workers :when (alive? w)] (kill-worker! w))
              (let [verified (workload/verify ds workload :handlers app/handlers)]
                {:ok? (and settled? (empty? violations) (empty? deferred) (:ok? verified))
                 :seed (:seed workload)
                 :settled? settled?
                 :steps step
                 :file-states states
                 :kill-plan kill-plan
                 :kills-not-applied deferred
                 :faults faults
                 :schedule @schedule
                 :violations violations
                 :verify verified
                 :logs (mapv (comp str :log) workers)}))
          (recur workers (inc step) violations deferred)))))))

;; --- results -----------------------------------------------------------------------------

(defn report
  "Human-readable lines for a chaos result."
  [{:keys [ok? seed settled? steps schedule violations verify]}]
  (into [(format "chaos run seed=%s: %s after %d steps (%s)" seed (if ok? "OK" "FAILED") steps
                 (if settled? "settled" "did not settle"))
         (format "  kills/respawns: %d, faults: %d, mid-run violations: %d, final mismatches: %d, final invariants: %s"
                 (count (remove #(contains? #{:violations :fault :ready} (:event %)) schedule))
                 (count (filter #(= :fault (:event %)) schedule)) (count violations)
                 (count (:mismatches verify)) (if (:ok? (:invariants verify)) "clean" "VIOLATED"))]
        (map (fn [{:keys [step event fault role id count ms]}]
               (format "  step %3d  %s %s%s%s%s" step (name event) (if fault (str (name fault) " ") "") (or role "")
                       (cond id (str " #" id) count (str " x" count) :else "")
                       (if ms (format " in %d ms" ms) ""))))
        schedule))

(defn write-result!
  "Persist a result as markdown: configuration, seed, schedule, violations, verify."
  [dir result]
  (let [f (io/file dir (format "%s-chaos-%s.md" (.format (java.time.LocalDate/now) java.time.format.DateTimeFormatter/ISO_DATE) (:seed result)))]
    (.mkdirs (io/file dir))
    (spit f (str "# Chaos run " (:seed result) "\n\n```\n" (str/join "\n" (report result)) "\n```\n\n"
                 "## Result\n\n```clojure\n" (with-out-str (pp/pprint (dissoc result :logs))) "\n```\n"))
    (str f)))
