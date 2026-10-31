(ns voucha.fake
  "Fake effect and store adapters for local runtime and tests (design 61, 62).

  A fake is scripted: it is told what the external world will do, including how
  it will misbehave. That is what lets a test exercise acknowledgement, delayed
  completion, dispatch failure, timeout and uncertain outcome without a network."
  (:require [voucha.adapter :as adapter]))

(defn- pop-script!
  "Take the next scripted response, or the last one forever."
  [script]
  (let [s @script]
    (if (> (count s) 1)
      (do (swap! script rest) (first s))
      (first s))))

(defrecord ScriptedEffectAdapter [script status calls]
  adapter/EffectAdapter
  (dispatch [_ effect]
    (swap! calls conj {:op :dispatch
                       :effect-instance-id (:effect-instance-id effect)
                       :idempotency-key (:idempotency-key effect)})
    (let [r (pop-script! script)]
      (if (:throw r)
        (throw (ex-info (:throw r) {:effect (:effect-instance-id effect)}))
        r)))

  (query-status [_ effect]
    (swap! calls conj {:op :query-status
                       :effect-instance-id (:effect-instance-id effect)
                       :external-request-id (:external-request-id effect)})
    (let [r @status]
      (if (:throw r) (throw (ex-info (:throw r) {})) r))))

(defn effect-adapter
  "`responses` is a sequence of dispatch outcomes; the last repeats.
  `status-response` is what `query-status` returns when uncertainty is resolved
  by asking."
  ([responses] (effect-adapter responses {:outcome :unknown}))
  ([responses status-response]
   (->ScriptedEffectAdapter (atom (vec responses)) (atom status-response) (atom []))))

(defrecord ScriptedStoreAdapter [script writes]
  adapter/StoreAdapter
  (write [_ effect]
    (let [r (pop-script! script)]
      (when (:throw r) (throw (ex-info (:throw r) {})))
      (let [w {:target (or (:target r) "enrichment_store")
               :record-key (:owner-node-id effect)
               :payload (:request-payload effect)}]
        (swap! writes conj w)
        w))))

(defn store-adapter
  ([] (store-adapter [{}]))
  ([responses] (->ScriptedStoreAdapter (atom (vec responses)) (atom []))))

(defn calls [adapter*] @(:calls adapter*))
(defn writes [adapter*] @(:writes adapter*))
(defn set-status! [adapter* response] (reset! (:status adapter*) response))
