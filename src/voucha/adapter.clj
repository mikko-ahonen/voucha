(ns voucha.adapter
  "The boundary between the platform and the outside world (design 26).

  Adapters are the only code allowed to perform external I/O, and they run
  strictly after the kernel transaction that created the effect has committed.
  An adapter never touches state_node; it reports an outcome, and the kernel
  turns that outcome into an event, which drives a transition like any other.

  `dispatch` may report three outcomes:

    :acknowledged  the external system accepted the request and returned a
                   durable identifier, but has not finished the work (design 27)
    :completed     the work is done
    :failed        terminal failure

  Throwing means the outcome is *unknown* -- the request may or may not have
  been received. That is not the same as failure, and the effect's declared
  uncertainty policy decides what happens next (decision D8, design 29)."
  )

(defprotocol EffectAdapter
  (dispatch [this effect]
    "Perform the external call. Returns
     {:outcome :acknowledged|:completed|:failed
      :external-request-id str?   ; required for :acknowledged
      :payload map?}")

  (query-status [this effect]
    "Resolve an uncertain outcome by asking the external system, keyed by the
     effect's external_request_id or idempotency key. Returns the same shape as
     `dispatch`, or {:outcome :unknown} if the external system cannot say yet.

     Required for effects declaring :uncertainty-policy :query."))

(defprotocol StoreAdapter
  (write [this effect]
    "Perform the database write for a store effect. Returns
     {:target str :record-key str :payload map?}.

     The kernel records the write in effect_write_log under a unique constraint,
     so a repeated write of the same (effect, target, record-key) is a no-op."))

(defn adapter-for
  [runtime effect]
  (let [k (:effect-name effect)
        registry (if (= "store" (:effect-kind effect))
                   (:store-adapters runtime)
                   (:effect-adapters runtime))]
    (or (get registry k)
        (throw (ex-info (format "No adapter registered for effect %s" k)
                        {:type ::missing-adapter :effect-name k})))))
