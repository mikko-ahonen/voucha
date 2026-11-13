(ns voucha.intercept
  "Named interception points in the execution — the mechanism only.

  See docs/design/testing-api.md. A *point* is a named seam the kernel and
  dispatcher already pass through; `hit!` runs whatever interceptors are
  installed at a point and threads the data through them, returning the
  possibly-transformed data. That is all this namespace does.

  It ships no actions of its own. The Fault-tier actions (crash / sleep / block)
  live in `voucha.debug` under dev/, which a production classpath cannot load;
  Observe- and Decide-tier interceptors are installed by the runtime. Keeping the
  mechanism here and the fault actions there is what makes the facility a
  first-class part of the platform without being reachable in production.

  `hit!` is a no-op when nothing is installed at a point — one map lookup and a
  nil check — which is the production case."
  (:refer-clojure :exclude [reset!]))

;; point -> vector of interceptor maps, in install order.
(defonce ^:private registry (atom {}))

;; signal-name -> promise, the rendezvous behind the block/until-signalled action.
(defonce ^:private signals (atom {}))

;; When bound to an atom, a Fault-tier action that *changes* the data flowing
;; through a point records its footprint here. The kernel binds this around
;; processing one event and writes whatever accumulated into the transition log's
;; `debug_provenance`, so a debug-injected value can never masquerade as a real
;; one (design: testing-api.md, capability gating).
(def ^:dynamic *provenance* nil)

;; An interceptor:
;;   {:id kw :point kw :tier kw
;;    :match (fn [data] boolean)   ; optional — scope to a node/effect/event
;;    :on-hit int                  ; optional — fire only on the Nth matching hit
;;    :action (fn [data] data')    ; data -> data (identity for pure side effects)
;;    :counter (atom 0)}

(defn install!
  "Install an interceptor at its `:point`. Returns an id for `remove!`.

  `:action` is a function of data to data: a transform returns the rewritten
  value; a pure side effect (sleep, crash, observe) returns its input. An action
  returning nil is read as 'unchanged', since no point carries nil as a value."
  [{:keys [point action] :as interceptor}]
  (assert (keyword? point) "interceptor needs a :point keyword")
  (assert (fn? action) "interceptor needs an :action fn of data->data")
  (let [id (or (:id interceptor) (keyword "icept" (name (gensym))))
        interceptor (assoc interceptor :id id :counter (atom 0))]
    (swap! registry update point (fnil conj []) interceptor)
    id))

(defn remove!
  "Uninstall the interceptor with `id`."
  [id]
  (swap! registry
         (fn [m]
           (into {} (map (fn [[p is]] [p (filterv #(not= id (:id %)) is)])) m)))
  nil)

(defn reset!
  "Drop every interceptor and pending signal. Call between tests."
  []
  (clojure.core/reset! registry {})
  (clojure.core/reset! signals {})
  nil)

(defn hits
  "How many times the interceptor `id` has fired its action."
  [id]
  (some (fn [is] (some (fn [i] (when (= id (:id i)) @(:counter i))) is))
        (vals @registry)))

(defn installed
  "Every installed interceptor as {:id :point :tier :hits} — the readout behind a
  `GET /debug/hits`."
  []
  (vec (for [[point is] @registry, i is]
         {:id (:id i) :point point :tier (:tier i) :hits @(:counter i)})))

(defn- run-one
  [data {:keys [match on-hit action counter tier point id]}]
  (if (and match (not (match data)))
    data
    (let [n (swap! counter inc)]
      (if (and on-hit (not= (long on-hit) n))
        data
        (let [r (action data)
              r (if (nil? r) data r)]
          ;; A Fault-tier transform that actually rewrote the data leaves a mark:
          ;; the log must never present an injected value as genuine. Stored as
          ;; strings so the footprint survives JSONB round-tripping unambiguously.
          (when (and *provenance* (= :fault tier) (not= r data))
            (swap! *provenance* conj {:point (subs (str point) 1)
                                      :interceptor (subs (str id) 1)}))
          r)))))

(defn provenance
  "The Fault-tier transforms that have fired since `*provenance*` was bound, or nil
  when nothing is collecting. The kernel reads this when writing a checkpoint."
  []
  (some-> *provenance* deref seq vec))

(defn hit!
  "Run the interceptors installed at `point`, threading `data` through each in
  install order. Returns the final data. A no-op returning `data` unchanged when
  nothing is installed — the production path."
  [point data]
  (if-let [interceptors (get @registry point)]
    (reduce run-one data interceptors)
    data))

;; --- signal rendezvous -------------------------------------------------------
;; The block action parks a thread here; `signal!` releases it. This is how one
;; runtime pauses mid-transition while a test — in-process or over HTTP — decides
;; when it may proceed.

(defn- signal-promise
  [sig]
  (or (get @signals sig)
      (get (swap! signals update sig #(or % (promise))) sig)))

(defn await-signal!
  "Park the current thread until `sig` is delivered, or `timeout-ms` elapses.
  Returns the delivered value, or `:intercept/timeout`."
  [sig timeout-ms]
  (deref (signal-promise sig) timeout-ms :intercept/timeout))

(defn signal!
  "Release whatever is parked on `sig` (and anything that parks on it later)."
  ([sig] (signal! sig true))
  ([sig val]
   (let [p (signal-promise sig)]
     (when-not (realized? p) (deliver p val)))
   val))
