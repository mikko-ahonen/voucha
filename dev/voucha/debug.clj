(ns voucha.debug
  "Fault-tier interception actions and test ergonomics.

  Lives under dev/ so a production classpath cannot load it: the crash, sleep and
  block primitives from a database server's debug mode, purpose-built for
  voucha's seams (see docs/design/testing-api.md). Everything here builds an
  action — a function of data to data — and installs it into `voucha.intercept`.
  The mechanism is production code; these actions are not.

  A test installs actions at points, drives the runtime, and asserts. `reset!`
  between tests clears them."
  (:refer-clojure :exclude [reset!])
  (:require [voucha.intercept :as intercept]))

(defn observe!
  "Record every hit at `point` (optionally only those matching `match`). Returns
  an atom holding the data seen at the point, newest last — the basis of a
  'was this reached, and with what?' assertion."
  [point & {:keys [match]}]
  (let [seen (atom [])]
    (intercept/install! {:point point :tier :fault :match match
                         :action (fn [data] (swap! seen conj data) data)})
    seen))

(defn transform!
  "Rewrite the data flowing through `point` with `f` (data -> data'). At a before
  point this rewrites the input; at an after point, the output."
  [point f & {:keys [match on-hit]}]
  (intercept/install! {:point point :tier :fault :match match :on-hit on-hit
                       :action f}))

(defn sleep!
  "Pause `ms` at `point` — widen a race window without a real slow dependency."
  [point ms & {:keys [match on-hit]}]
  (intercept/install! {:point point :tier :fault :match match :on-hit on-hit
                       :action (fn [data] (Thread/sleep (long ms)) data)}))

(defn block!
  "Park at `point` until `sig` is signalled (or `timeout-ms` elapses). Returns the
  interceptor id. This is 'stop execution until another command is passed' — the
  primitive that makes a race deterministic, replacing hand-rolled promises and
  latches inside handlers."
  [point sig & {:keys [match on-hit timeout-ms] :or {timeout-ms 10000}}]
  (intercept/install! {:point point :tier :fault :match match :on-hit on-hit
                       :action (fn [data] (intercept/await-signal! sig timeout-ms) data)}))

(defn crash!
  "Throw at `point`, aborting an in-flight kernel transaction. With `:on-hit n`,
  only on the nth matching hit — 'crash after the debug point is reached n
  times'. Rolls back like any real crash."
  [point & {:keys [match on-hit msg] :or {msg "debug crash"}}]
  (intercept/install! {:point point :tier :fault :match match :on-hit on-hit
                       :action (fn [_] (throw (ex-info msg {:type ::debug-crash :point point})))}))

(defn rewrite-outcome!
  "Rewrite an adapter's outcome before it is recorded, at `:effect/before-apply`.
  Outcome fabrication: make the external world appear to have said anything, to
  exercise a completion or failure path without a misbehaving adapter. `f` takes
  the outcome map ({:outcome :completed :payload ..}) and returns a new one."
  [f & {:keys [match on-hit]}]
  (intercept/install! {:point :effect/before-apply :tier :fault :match match
                       :on-hit on-hit :action f}))

(defn fail-effect!
  "Force an effect's recorded outcome to `:failed` at `:effect/before-apply` — the
  `:fail-with` action. Drives the owner node down its failure transition without a
  failing adapter. `:match`/`:on-hit` scope it; `:error` sets the payload message."
  [& {:keys [match on-hit error] :or {error "debug-injected failure"}}]
  (rewrite-outcome! (fn [outcome] (assoc outcome :outcome :failed :payload {:error error}))
                    :match match :on-hit on-hit))

(defn kill!
  "Hard-kill the JVM at `point` — `Runtime.halt`, not an exception and not
  `System/exit`: no shutdown hooks, no `finally`, no rollback path in the app. The
  in-flight database transaction is aborted by the server when the connection
  dies, exactly as in a real crash. Only meaningful in a subprocess (it takes the
  whole JVM down); the restart/recovery drill drives it there. `:on-hit n` kills
  on the nth matching hit."
  [point & {:keys [match on-hit code] :or {code 137}}]
  (intercept/install! {:point point :tier :fault :match match :on-hit on-hit
                       :action (fn [_] (.halt (Runtime/getRuntime) (int code)))}))

(def signal
  "Release a `block!`ed point. See `voucha.intercept/signal!`."
  intercept/signal!)

(def reset!
  "Clear all installed points and signals. See `voucha.intercept/reset!`."
  intercept/reset!)

;; --- HTTP-driven installation ------------------------------------------------
;; The admin API cannot ship a function over the wire, so it sends a declarative
;; action spec and this dev-only namespace turns it into an action. `:transform`
;; is deliberately absent here -- an arbitrary rewrite is a function, so it stays
;; in-process. The fault actions (block/sleep/crash) and observe are data.

(defn- match-fn
  "Build a predicate from a JSON-describable match: a list of
  {:path [\"node\" \"node-id\"] :equals \"A1\"} clauses, all of which must hold."
  [clauses]
  (when (seq clauses)
    (fn [data]
      (every? (fn [{:keys [path equals]}]
                (= equals (get-in data (mapv keyword path))))
              clauses))))

(defn- spec->action
  [{:keys [action sig ms msg timeout-ms] :or {timeout-ms 60000}}]
  (case action
    "observe" (fn [data] data)   ; counting only; the hit shows in `installed`
    "block"   (let [s (keyword sig)] (fn [data] (intercept/await-signal! s timeout-ms) data))
    "sleep"   (fn [data] (Thread/sleep (long ms)) data)
    "crash"   (fn [_] (throw (ex-info (or msg "debug crash") {:type ::debug-crash})))
    (throw (ex-info (str "unknown debug action: " (pr-str action))
                    {:type ::bad-action :action action}))))

(defn install-from-spec!
  "Install a Fault interceptor from a declarative spec (the shape a `POST
  /debug/install` carries): {:point .. :action .. + action params, :on-hit ..,
  :match [..]}. Returns the interceptor id."
  [{:keys [point on-hit match] :as spec}]
  (intercept/install!
   (cond-> {:point (keyword point)
            :tier :fault
            :action (spec->action spec)}
     on-hit (assoc :on-hit on-hit)
     (seq match) (assoc :match (match-fn match)))))
