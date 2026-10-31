(ns voucha.runtime
  "Assembling a runtime, and the local dev mode (design 61).

  `dev` mode starts the kernel against an embedded database with fake effect and
  store dispatchers, so the whole lifecycle -- acknowledgement, completion,
  failure, timeout, manual intervention -- can be simulated without a network."
  (:require [voucha.db :as db]
            [voucha.dispatcher :as dispatcher]
            [voucha.intercept :as intercept]
            [voucha.kernel :as kernel]))

(defn runtime
  [{:keys [handlers effect-adapters store-adapters telemetry-sink config]}]
  {:handlers handlers
   :effect-adapters (or effect-adapters {})
   :store-adapters (or store-adapters {})
   ;; Optional Observe-tier sink: the dispatcher hands it each committed
   ;; transition's telemetry after commit (see voucha.telemetry).
   :telemetry-sink telemetry-sink
   :event (or (:event config) {:max-attempts 5 :backoff-ms 500})
   :dispatcher (or (:dispatcher config) {:batch-size 50 :backoff-ms 500 :max-attempts 5})})

(defn step!
  "One full turn: process pending events, then let the outside world act, then
  process whatever that produced. Returns what happened."
  [ds rt]
  (let [before (kernel/drain! ds rt)
        world (dispatcher/run-once! ds rt)
        after (kernel/drain! ds rt)]
    {:events (into before after)
     :effects (:dispatched world)
     :reports (:reports world)
     :timeouts (:timeouts world)}))

(defn settle!
  "Step until nothing changes. Useful in dev and tests; a real deployment runs
  the kernel and dispatchers as separate long-lived workers."
  ([ds rt] (settle! ds rt 100))
  ([ds rt max-steps]
   (loop [n 0 acc []]
     (if (>= n max-steps)
       (throw (ex-info "Runtime did not settle" {:type ::no-convergence :steps n}))
       (let [r (step! ds rt)]
         (if (and (empty? (:events r)) (empty? (:effects r)) (zero? (:timeouts r)) (zero? (:reports r 0)))
           acc
           (recur (inc n) (conj acc r))))))))

(defn start-workers!
  "Long-lived kernel and dispatcher loops. Returns a stop function.

  Runtime nodes may be scaled horizontally: both loops claim work through the
  database with FOR UPDATE SKIP LOCKED (design 64)."
  [ds rt]
  (let [running (atom true)
        interval (get-in rt [:dispatcher :poll-interval-ms] 200)
        spawn (fn [f name*]
                (doto (Thread. ^Runnable
                               (fn [] (while @running
                                        (intercept/hit! :worker/tick {:worker name*})
                                        (try (f)
                                             (catch Exception e
                                               (println "worker error:" name* (ex-message e))))
                                        (Thread/sleep interval)))
                               ^String name*)
                  (.setDaemon true)
                  (.start)))]
    (spawn #(kernel/drain! ds rt) "voucha-kernel")
    (spawn #(dispatcher/run-once! ds rt) "voucha-dispatcher")
    (fn stop! [] (reset! running false))))

(defn with-runtime
  [config f]
  (let [ds (db/pool (:db config))]
    (try (f ds) (finally (db/close-pool! ds)))))
