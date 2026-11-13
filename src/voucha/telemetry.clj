(ns voucha.telemetry
  "The Observe tier: emit audit / trace records as post-commit effects.

  An audit observer installed at `:transition/before-commit` enqueues a telemetry
  row on the transition's *own* connection, so the record is written in the same
  transaction as the transition — atomic with it, and rolled back if the
  transition rolls back. The telemetry dispatcher (in `drain-outbox!`) then ships
  it to the configured sink only after commit. No external I/O ever happens inside
  the kernel transaction (design: testing-api.md, §52).

  This is the production-safe, always-on cross-cutting path: logging, tracing and
  audit are observation that becomes an effect, not code that reaches out from
  inside a transition."
  (:require [voucha.codec :as codec]
            [voucha.intercept :as intercept]
            [voucha.repo.outbox :as outbox]))

(def outbox-type "telemetry")

(defn- transition->record
  [{:keys [node plan result]}]
  {:tree-id (:tree-id node)
   :node-id (:node-id node)
   :node-type (:node-type node)
   :from (:from-state result)
   :to (:to-state result)
   :handler (str (:handler plan))
   :transition-id (:transition-id result)})

(defn install-audit!
  "Install an Observe hook that records every committed transition as a telemetry
  outbox row. Returns an interceptor id. Pair it with a `:telemetry-sink` on the
  runtime; the dispatcher delivers records to that sink after commit."
  []
  (intercept/install!
   {:point :transition/before-commit
    :tier :observe
    :action (fn [{:keys [conn] :as data}]
              (let [rec (transition->record data)]
                (outbox/enqueue! conn {:outbox-id (codec/sha256 (str "telemetry:" (:transition-id rec)))
                                       :tree-id (:tree-id rec)
                                       :node-id (:node-id rec)
                                       :type outbox-type
                                       :payload rec}))
              data)}))
