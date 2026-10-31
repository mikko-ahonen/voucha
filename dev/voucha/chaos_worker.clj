(ns voucha.chaos-worker
  "One worker process of the chaos harness (test plan T0 §6.4).

  A role, a worker id, a database URL and a spec file holding the workload and
  runtime config. Runs its role's loop forever, until killed. Roles:

    kernel      -- `kernel/drain!`: processes events
    dispatcher  -- `dispatcher/run-once!`: effects, reclaim, timeouts, outbox
    rail        -- the simulated outside world: answers IP sends per the script

  The controller kills workers two ways. `--kill-at <point> [--kill-on-hit n]`
  arms a Fault-tier `kill!` (Runtime.halt) at a named seam, for deterministic
  crash-at-a-point drills; a plain destroyForcibly from the controller is the
  random-time kill. Either way the process dies without any cleanup path --
  exactly a real crash.

  Database faults (test plan T3) are staged with more flags. Every worker
  connects with `application_name = voucha-<role>-<id>`, so the controller can
  `pg_terminate_backend` one role's connections. `--sleep-at <point> --sleep-ms n`
  holds a transaction open at a seam, so a termination lands mid-transaction.
  `--lock-timeout-ms n` / `--statement-timeout-ms n` set the session defaults, so
  contention surfaces as errors instead of waits. `--pool-size n` and
  `--connection-timeout-ms n` starve the connection pool.

  Run as `java -cp <classpath> clojure.main -m voucha.chaos-worker
  <jdbc-url> <role> <id> <spec-file> [--flag value ...]`."
  (:require [clojure.edn :as edn]
            [voucha.db :as db]
            [voucha.debug :as debug]
            [voucha.dispatcher :as dispatcher]
            [voucha.kernel :as kernel]
            [voucha.workload :as workload]))

(def ^:private default-poll-ms 50)
(def ^:private default-pool-size 4)

(defn- parse-long* [s] (when s (Long/parseLong s)))
(defn- point [s] (keyword (subs s 1)))

(defn- arm-faults! [{:strs [--kill-at --kill-on-hit --sleep-at --sleep-ms]}]
  (when --kill-at (debug/kill! (point --kill-at) :on-hit (parse-long* --kill-on-hit)))
  (when --sleep-at (debug/sleep! (point --sleep-at) (or (parse-long* --sleep-ms) 500))))

(defn- connection-url
  "The worker's URL: named after its role, with any session timeouts as server
  options (PgJDBC's `options` parameter, URL-encoded)."
  [jdbc-url role worker-id {:strs [--lock-timeout-ms --statement-timeout-ms]}]
  (let [opts (cond-> []
               --lock-timeout-ms (conj (str "-c lock_timeout=" --lock-timeout-ms "ms"))
               --statement-timeout-ms (conj (str "-c statement_timeout=" --statement-timeout-ms "ms")))
        sep (if (.contains ^String jdbc-url "?") "&" "?")]
    (cond-> (str jdbc-url sep "ApplicationName=voucha-" role "-" worker-id)
      (seq opts) (str "&options=" (java.net.URLEncoder/encode (clojure.string/join " " opts) "UTF-8")))))

(defn -main
  [& [jdbc-url role worker-id spec-file & flag-args]]
  (let [flags (apply hash-map flag-args)
        {:keys [workload config]} (edn/read-string (slurp spec-file))
        ds (db/pool {:jdbc-url (connection-url jdbc-url role worker-id flags)
                     :max-pool-size (or (parse-long* (get flags "--pool-size")) default-pool-size)
                     :connection-timeout-ms (parse-long* (get flags "--connection-timeout-ms"))})
        rt (workload/runtime workload config)
        poll (get-in rt [:dispatcher :poll-interval-ms] default-poll-ms)
        step (case role
               "kernel" #(kernel/drain! ds rt)
               "dispatcher" #(dispatcher/run-once! ds rt)
               "rail" #(workload/deliver-inst-callbacks! ds workload)
               (throw (ex-info (str "unknown role " role) {})))]
    (arm-faults! flags)
    (println "READY" role worker-id) (flush)
    (while true
      (try (step)
           (catch Exception e
             (binding [*out* *err*] (println "worker error:" role worker-id (ex-message e)))))
      (Thread/sleep (long poll)))))
