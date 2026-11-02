(ns voucha.effect
  "Production dispatch-time effect transforms (design: testing-api.md).

  These install at `:effect/before-dispatch`, in the I/O zone, outside the
  deterministic core — so they need no artifact change. They rewrite the
  *request* an effect sends, never the machine's decision to send it, which is
  what makes them the safe, everyday home for cross-cutting concerns: trace
  propagation, tenant tagging, PII redaction, header injection.

  Two constraints they are built to respect:

    * A transform must be idempotent. A retry-safe effect re-dispatches through
      the same transform, under the same idempotency key, so a non-idempotent
      rewrite would send a different request under a key that promises sameness.
    * The rewrite changes what leaves the process, not `effect_instance`'s
      recorded request. Keep annotations to fields the operation's *identity*
      does not depend on, or the audit record and the wire diverge.

  Fabricating outcomes or injecting faults is a different matter — that is
  testing, and lives in `voucha.debug`."
  (:require [voucha.intercept :as intercept]))

(defn- with-match
  [interceptor match]
  (cond-> interceptor match (assoc :match match)))

(defn annotate-requests!
  "Merge fields into every dispatched effect's request payload. `fields` is a map,
  or a function of the effect returning a map (for a per-request value like a
  span id). Deterministic and idempotent by contract. `:match` scopes it — e.g.
  `#(= \"external\" (:effect-kind %))` or by `:effect-name`. Returns an id."
  [fields & {:keys [match]}]
  (let [f (if (fn? fields) fields (constantly fields))]
    (intercept/install!
     (with-match {:point :effect/before-dispatch
                  :tier :transform
                  :action (fn [effect] (update effect :request-payload merge (f effect)))}
                 match))))

(defn redact-requests!
  "Remove `ks` from every dispatched effect's request payload before it leaves —
  secrets, PII the external system must not receive. Returns an id."
  [ks & {:keys [match]}]
  (intercept/install!
   (with-match {:point :effect/before-dispatch
                :tier :transform
                :action (fn [effect] (update effect :request-payload #(apply dissoc % ks)))}
               match)))
