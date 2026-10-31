(ns voucha.cli
  "The `voucha` command line (design 60).

    voucha init       scaffold a new project
    voucha compile    compile a definition to an artifact
    voucha dev        run kernel, dispatchers, fakes and admin API in one process
    voucha profiles   list profiles in stored artifacts
    voucha inspect    node and tree state, with blocked transitions
    voucha history    transition log for a node
    voucha chain      profile-defined chain view over a tree
    voucha graph      state diagram of a definition: Graphviz, Mermaid or PlantUML
    voucha send-event submit an event to a node
    voucha replay     reconstruct or verify past execution
    voucha check      run the invariant oracle over the whole database
    voucha model      write a P model of a definition, and check it with the P checker
    voucha prove      write a TLA+ specification of a definition, and check it exhaustively with TLC
    voucha simulate   explore every interleaving of a tree in memory, checking the invariants

  Commands that read a tree read its *pinned* artifact, so `inspect` on an old
  tree explains it under the rules that actually governed it."
  (:require [clojure.edn]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [voucha.codec :as codec]
            [voucha.compiler :as compiler]
            [voucha.config :as config]
            [voucha.db :as db]
            [voucha.graph :as graph]
            [voucha.handler :as handler]
            [voucha.inspect :as inspect]
            [voucha.invariants :as invariants]
            [voucha.kernel :as kernel]
            [voucha.model-p :as model-p]
            [voucha.model-apalache :as model-apalache]
            [voucha.model-tla :as model-tla]
            [voucha.replay :as replay]
            [voucha.repo.artifact :as artifact]
            [voucha.repo.tree :as tree]
            [voucha.scaffold :as scaffold])
  (:gen-class))

(def ^:private step-marks
  {:complete "[x]" :active "[~]" :failed "[!]" :pending "[ ]"})

(defn- load-var
  "Resolve a var, e.g. voucha.profiles.file-batch-transaction/definition."
  [sym-str]
  (let [sym (symbol sym-str)]
    (require (symbol (namespace sym)))
    @(resolve sym)))

(defn- load-definition
  "Accept either a bare definition var or an app var carrying one, so a project
  can name the same var to `compile`, `graph` and `dev`."
  [sym-str]
  (let [v (load-var sym-str)]
    (or (:definition v) v)))

(defn- root-cause [^Throwable e]
  (if-let [c (.getCause e)] (recur c) e))

(defn- with-db [f]
  (let [cfg (config/load-config)
        url (get-in cfg [:db :jdbc-url])
        ds (try (db/pool (:db cfg))
                (catch Exception e
                  (if (instance? java.net.ConnectException (root-cause e))
                    (throw (ex-info (format (str "cannot reach the database at %s (connection refused).\n"
                                                 "Export VOUCHA_DB_URL -- `voucha dev` prints it -- or set :db in voucha.edn.")
                                            url)
                                    {:type ::no-database}))
                    (throw e))))]
    (try (db/in-tx [tx ds] (f tx))
         (finally (db/close-pool! ds)))))

;; --- commands ------------------------------------------------------------------

(defn cmd-compile
  [[definition-sym]]
  (let [d (load-definition definition-sym)
        {:keys [artifact-id specs warnings] :as a} (compiler/compile-definition d)]
    (with-db #(artifact/insert! % a))
    (println "artifact-id:" artifact-id)
    (println "profile:" (get-in specs [:manifest :profile]))
    (println "node types:" (str/join ", " (keys (:node-types specs))))
    (println "transitions:" (count (:transitions specs)))
    (println "effects:" (str/join ", " (keys (:effects specs))))
    (when-let [invs (seq (:invariants specs))]
      (println "invariants:" (str/join ", " (map (fn [[n {:keys [class]}]] (str (name n) " (" (name class) ")")) (sort-by key invs)))))
    (doseq [w warnings]
      (binding [*out* *err*]
        (println (format "warning [%s] %s" (name (:code w)) (:message w)))))))

(defn cmd-profiles
  [_]
  (doseq [p (with-db inspect/profiles)]
    (println (format "%-28s %-6s %s"
                     (or (:profile-name p) "-")
                     (or (:profile-version p) "-")
                     (subs (:artifact-id p) 0 16)))))

(defn cmd-inspect
  [[tree-id node-id]]
  (let [v (with-db #(if node-id
                      (inspect/node-view % tree-id node-id)
                      (inspect/tree-view % tree-id)))]
    (pp/pprint v)))

(defn cmd-why
  [[tree-id node-id]]
  (let [{:keys [local-state blocked-transitions allowed-next-events outstanding-effects
                parked-events counters]}
        (with-db #(inspect/why-stuck % tree-id node-id))]
    (println (format "%s is in %s" node-id local-state))
    (when (seq counters) (println "counters:" counters))
    (if (seq allowed-next-events)
      (println "can advance on:" (str/join ", " allowed-next-events))
      (println "cannot advance on any event"))
    (doseq [{:keys [event to blocked-by]} blocked-transitions]
      (println (format "  blocked: %s -> %s" event to))
      (doseq [r blocked-by] (println "    -" r)))
    (doseq [e outstanding-effects]
      (println (format "  outstanding effect: %s (%s%s)"
                       (:effect-name e) (:status e)
                       (if-let [r (:external-request-id e)] (str ", request " r) ""))))
    (doseq [e parked-events]
      (println (format "  parked event: %s (%s)" (:event-type e) (:last-error e))))))

(defn cmd-history
  [[tree-id node-id]]
  (doseq [h (with-db #(inspect/history % tree-id node-id))]
    (println (format "%s  %-22s %s -> %s  [%s]"
                     (:created-at h) (:handler-name h)
                     (:from-state h) (:to-state h)
                     (subs (:artifact-id h) 0 12)))
    (when (seq (:state-patch h)) (println "    patch:" (pr-str (:state-patch h))))
    (when (seq (:created-effects h)) (println "    effects:" (:created-effects h)))
    (when (:context-read-set h) (println "    context:" (:context-read-set h)))))

(defn cmd-chain
  [[tree-id view node-id]]
  (let [v (with-db #(inspect/chain-view % tree-id view node-id))]
    (println (format "%s %s (%s)" (:node-type v) (:node-id v) (:local-state v)))
    (println)
    (doseq [s (:steps v)]
      (println (format "%s %s" (step-marks (:status s)) (:name s)))
      (when-let [p (:progress s)]
        (doseq [[k n] (dissoc p :total)]
          (println (format "      %s: %s" (name k) n)))
        (println (format "      total: %s" (:total p)))))))

(defn cmd-graph
  "voucha graph <ns/var> [--mermaid | --plantuml | --hierarchy] -- Graphviz by default."
  [[definition-sym & flags]]
  (let [specs (:specs (compiler/compile-definition (load-definition definition-sym)))
        flag (set flags)]
    (println (cond (flag "--mermaid") (graph/to-mermaid specs)
                   (flag "--plantuml") (graph/to-plantuml specs)
                   (flag "--hierarchy") (graph/hierarchy-dot specs)
                   :else (graph/to-dot specs)))))

(defn cmd-send-event
  [[tree-id node-id event-type & {:strs [--operator --reason]}]]
  (with-db (fn [tx]
             (kernel/submit-event! tx {:event-id (codec/sha256 (str (random-uuid)))
                                       :tree-id tree-id :target-node-id node-id
                                       :event-type event-type
                                       :operator-id --operator
                                       :operator-reason --reason})))
  (println "submitted" event-type "to" node-id))

(defn cmd-replay
  [[tree-id node-id]]
  (let [r (with-db #(if node-id
                      (replay/audit % tree-id node-id)
                      (replay/audit-tree % tree-id)))]
    (pp/pprint r)))

(defn cmd-init
  [[project & {:strs [--dir]}]]
  (when-not project
    (throw (ex-info "usage: voucha init <project-name> [--dir <path>]" {:type ::usage})))
  (let [root (or --dir project)]
    (doseq [p (scaffold/init! root project)]
      (println "created" p))
    (println)
    (println "next:")
    (println "  cd" root "&& clojure -X:test")))

(defn cmd-dev
  "voucha dev <ns/app-var> [--port <n>] [--scenario <n> [--seed <n>]] -- `--scenario`
  runs the app's scenario, such as placing n generated orders, once the runtime is up."
  [[app-sym & {:strs [--port --scenario --seed]}]]
  (when-not app-sym
    (throw (ex-info "usage: voucha dev <ns/app-var> [--port <n>] [--scenario <n> [--seed <n>]]" {:type ::usage})))
  ;; `voucha.dev` is the only namespace that needs an embedded database, so it
  ;; lives behind the :dev alias and is resolved rather than required.
  (let [serve! (try (requiring-resolve 'voucha.dev/serve!)
                    (catch Exception _ nil))]
    (when-not serve!
      (throw (ex-info "dev mode needs the :dev alias: clojure -M:dev -m voucha.cli dev <ns/app-var>"
                      {:type ::dev-alias-missing})))
    (let [app (load-var app-sym)]
      (when-let [cs (seq (handler/conflicts (:definition app) (:handlers app)))]
        (binding [*out* *err*]
          (println "warning: functions registered for transitions that carry a plan are not called:"
                   (str/join ", " cs))))
      (serve! (cond-> {:app app}
                --port (assoc :port (parse-long --port))
                --scenario (assoc :scenario (parse-long --scenario))
                --seed (assoc :seed (parse-long --seed)))))))

(defn cmd-verify
  [[tree-id definition-sym]]
  (let [handlers (:handlers (load-var definition-sym))
        result (with-db #(replay/verify-deterministic % tree-id handlers))]
    (if (:ok? result)
      (println (format "deterministic replay OK: %d transitions reproduced"
                       (:checked result)))
      (do (println "DETERMINISTIC REPLAY FAILED")
          (doseq [d (:divergences result)] (pp/pprint d))
          (System/exit 1)))))

(defn cmd-check
  "voucha check [<ns/app-var>] -- every invariant over every tree; with an app var,
  replay fidelity (I10) too. Exit 1 on any violation."
  [[app-sym]]
  (let [handlers (when app-sym (:handlers (load-var app-sym)))
        result (with-db #(invariants/check-all % :handlers handlers))]
    (doseq [line (invariants/report result)] (println line))
    (when-not (:ok? result) (System/exit 1))))

(defn- parse-flags
  "`--key value` pairs and bare `--flag`s into a map; bare flags map to true."
  [args]
  (loop [[a & more] args acc {}]
    (cond (nil? a) acc
          (and (str/starts-with? a "--") (or (empty? more) (str/starts-with? (first more) "--")))
          (recur more (assoc acc a true))
          (str/starts-with? a "--") (recur (rest more) (assoc acc a (first more)))
          :else (recur more acc))))

(defn- read-payload
  "An EDN payload: the contents of a file if the argument names one, else the
  argument itself."
  [s]
  (when s
    (let [f (java.io.File. ^String s)]
      (clojure.edn/read-string (if (.isFile f) (slurp f) s)))))

(defn cmd-simulate
  "voucha simulate <ns/app-var> --root <Type> <id> --event <Event> [--payload <edn|file>]
     [--handlers <ns/var>] [--walks N [--steps N] [--seed N]] [--max-worlds N] [--succeed]

  Every interleaving of one tree, in memory and with no database: the root is
  created, the event submitted, and from there every order of events and of
  effect outcomes is explored (`--walks N`: N seeded random walks instead).
  Each world is checked for a rejected event, a child in a state its parent
  forbids, counters that disagree with the children, a live effect under a
  finished node, and, once nothing can move, a root that is not terminal.
  `--succeed` lets every effect only complete; by default each may also fail.
  Needs the :dev alias, which `bin/voucha` uses. Exit 1 on any violation."
  [[app-sym & args]]
  (let [opts (parse-flags args)
        root-type (get opts "--root")
        ;; --root takes two values; parse-flags kept the type, the id follows it
        root-id (let [[_ _ id] (drop-while #(not= "--root" %) args)]
                  (when (and id (not (str/starts-with? id "--"))) id))
        event (get opts "--event")]
    (when-not (and app-sym root-type root-id event)
      (throw (ex-info (str "usage: voucha simulate <ns/app-var> --root <Type> <id> --event <Event> "
                           "[--payload <edn|file>] [--handlers <ns/var>] [--walks N] [--steps N] [--max-worlds N] [--succeed]")
                      {:type ::usage})))
    (let [app (load-var app-sym)
          definition (or (:definition app) app)
          handlers (or (some-> (get opts "--handlers") load-var) (:handlers app)
                       (throw (ex-info "no handlers: give an app var with :handlers, or --handlers <ns/var>" {:type ::usage})))
          specs (:specs (compiler/compile-definition definition))
          world-from (requiring-resolve 'voucha.simulate/world-from)
          explore (requiring-resolve 'voucha.simulate/explore)
          random-walk (requiring-resolve 'voucha.simulate/random-walk)
          script (if (get opts "--succeed")
                   @(requiring-resolve 'voucha.simulate/all-succeed)
                   @(requiring-resolve 'voucha.simulate/any-outcome))
          world (world-from specs root-type root-id event (read-payload (get opts "--payload")))
          walks (some-> (get opts "--walks") parse-long)
          report (fn [violations]
                   (doseq [{:keys [property node-id detail path]} violations]
                     (println (format "%s at %s: %s" (name property) node-id detail))
                     (doseq [p path] (println "   " p))))]
      (if walks
        (let [steps (or (some-> (get opts "--steps") parse-long) 2000)
              seed0 (or (some-> (get opts "--seed") parse-long) 0)
              results (for [seed (range seed0 (+ seed0 walks))]
                        [seed (random-walk world handlers script root-id seed {:steps steps})])
              bad (filter (fn [[_ r]] (seq (:violations r))) results)
              stuck (filter (fn [[_ r]] (and (empty? (:violations r)) (not (:quiescent? r)))) results)]
          (println (format "%d random walks of up to %d steps from seed %d" walks steps seed0))
          (doseq [[seed r] bad] (println (format "seed %d:" seed)) (report (:violations r)))
          (doseq [[seed _] stuck] (println (format "seed %d did not reach quiescence; raise --steps" seed)))
          (println (if (and (empty? bad) (empty? stuck)) "no violations" "VIOLATIONS"))
          (when (seq bad) (System/exit 1)))
        (let [max-worlds (or (some-> (get opts "--max-worlds") parse-long) 20000)
              r (explore world handlers script root-id {:max-worlds max-worlds})]
          (println (format "explored %d worlds, %d quiescent%s" (:explored r) (:terminal r)
                           (if (:complete? r) ", every interleaving" (format "; stopped at --max-worlds %d" max-worlds))))
          (report (:violations r))
          (println (if (empty? (:violations r)) "no violations" "VIOLATIONS"))
          (when (seq (:violations r)) (System/exit 1)))))))

(defn cmd-model
  "voucha model <ns/app-var> --root <Type> <id> --event <Event> [--payload <edn|file>]
     [--children Batch=2,Transaction=2] [--out <dir>] [--max-repeat N] [--drop] [--check [--schedules N]]

  Write a complete P model of the definition: the kernel's semantics with the
  definition's tables, every transition that is a plan translated exactly, a
  driver that creates the root <id> of <Type> and sends it <Event> with the
  payload, and the specs. `--children` says how many children a transition on
  a code handler creates of each type (default 1). `--drop` lets effects be
  lost. With `--check`, compile and run the P checker (`p` on the PATH) over
  the schedules and report; exit 1 on a bug, with the checker's trace."
  [[app-sym & args]]
  (let [opts (parse-flags args)
        root-type (get opts "--root")
        root-id (let [[_ _ id] (drop-while #(not= "--root" %) args)]
                  (when (and id (not (str/starts-with? id "--"))) id))
        event (get opts "--event")]
    (when-not (and app-sym root-type root-id event)
      (throw (ex-info (str "usage: voucha model <ns/app-var> --root <Type> <id> --event <Event> [--payload <edn|file>] "
                           "[--children T=n,...] [--out <dir>] [--max-repeat N] [--drop] [--check [--schedules N]]")
                      {:type ::usage})))
    (let [artifact (compiler/compile-definition (load-definition app-sym))
          children (into {} (for [kv (str/split (or (get opts "--children") "") #",") :when (seq kv)
                                  :let [[t n] (str/split kv #"=")]]
                              [t (or (some-> n parse-long) 1)]))
          config {:root-type root-type :root-id root-id :first-event event
                  :first-payload (or (read-payload (get opts "--payload")) {})
                  :children children
                  :max-repeat (or (some-> (get opts "--max-repeat") parse-long) 3)
                  :allow-drop (boolean (get opts "--drop"))}
          name* (model-p/project-name (:specs artifact))
          dir (or (get opts "--out") (str "tmp/model/" name*))
          files (model-p/generate artifact config)]
      (doseq [p (model-p/write! dir files)] (println "wrote" p))
      (when (get opts "--check")
        (let [schedules (or (some-> (get opts "--schedules") parse-long) 1000)
              _ (println (format "checking %s over %d schedules ..." name* schedules))
              r (model-p/check! dir name* {:schedules schedules})]
          (cond
            (:error r) (do (println (:out r)) (println (:error r)) (System/exit 1))
            (:ok? r) (println (format "no bugs found in %d schedules" schedules))
            :else (do (println (:out r))
                      (when (:trace r)
                        (println "--- trace of the first bug ---")
                        (println (:trace r)))
                      (println (format "FOUND %s BUG(S); traces under %s/PCheckerOutput/BugFinding" (:bugs r) dir))
                      (System/exit 1))))))))

(defn cmd-prove
  "voucha prove <ns/app-var> --root <Type> <id> --event <Event> [--payload <edn|file>]
     [--children T=n,...] [--symmetric field,...] [--out <dir>] [--max-repeat N] [--drop]
     [--check [--liveness] [--workers N]] [--symbolic N] [--inductive [--lemmas <file>] [--bounds n,q,e]]

  Write a TLA+ specification of the definition -- the kernel's semantics with
  the definition's tables, every planned transition translated exactly, and
  the run's root, first event and payload -- and, with `--check`, run TLC over
  it: every reachable state of that instance, deduplicated, against the
  invariants; with `--liveness`, also that a terminal node never moves and
  that the tree finishes under weak fairness. `--symmetric tx-id` makes the
  nodes whose ids the payload's tx-id fields name interchangeable, so TLC
  identifies states that differ only by a permutation of them; their records
  must be identical apart from the id. `--symbolic N` and `--inductive` write
  the typed specification too, which needs the definition's :data and
  :payloads schemas, and run Apalache: every behaviour of up to N steps, or
  the three checks of the inductive invariant, which holds for every size
  when they pass; `--lemmas` adds the application's own TLA+ predicate to it,
  and `--bounds n,q,e` sizes the states the step check starts from (nodes,
  queued events, effects; default 6 each). Exit 1 on a violation, with the
  trace or the state."
  [[app-sym & args]]
  (let [opts (parse-flags args)
        root-type (get opts "--root")
        root-id (let [[_ _ id] (drop-while #(not= "--root" %) args)]
                  (when (and id (not (str/starts-with? id "--"))) id))
        event (get opts "--event")]
    (when-not (and app-sym root-type root-id event)
      (throw (ex-info (str "usage: voucha prove <ns/app-var> --root <Type> <id> --event <Event> [--payload <edn|file>] "
                           "[--children T=n,...] [--symmetric field,...] [--out <dir>] [--max-repeat N] [--drop] [--check [--liveness] [--workers N]]")
                      {:type ::usage})))
    (let [artifact (compiler/compile-definition (load-definition app-sym))
          children (into {} (for [kv (str/split (or (get opts "--children") "") #",") :when (seq kv)
                                  :let [[t n] (str/split kv #"=")]]
                              [t (or (some-> n parse-long) 1)]))
          config {:root-type root-type :root-id root-id :first-event event
                  :first-payload (or (read-payload (get opts "--payload")) {})
                  :children children
                  :max-repeat (or (some-> (get opts "--max-repeat") parse-long) 3)
                  :allow-drop (boolean (get opts "--drop"))
                  :liveness (boolean (get opts "--liveness"))
                  :symmetric (when-let [f (get opts "--symmetric")] (str/split f #","))
                  :bounds (when-let [b (get opts "--bounds")]
                            (zipmap [:nodes :queue :effects] (map parse-long (str/split b #","))))}
          name* (model-tla/module-name (:specs artifact))
          dir (or (get opts "--out") (str "tmp/tla/" name*))
          files (model-tla/generate artifact config)]
      (doseq [p (model-tla/write! dir files)] (println "wrote" p))
      (when (get opts "--check")
        (println (format "checking %s with TLC ..." name*))
        (let [r (model-tla/check! dir name* {:workers (or (get opts "--workers") "auto")})]
          (cond
            (:ok? r) (println (format "no violation: %d states generated, %d distinct" (:states r) (:distinct r)))
            :else (do (println (:out r))
                      (println (format "VIOLATION: %s" (:error r)))
                      (System/exit 1)))))
      ;; the typed specification, for Apalache: a bounded symbolic check, or the induction
      (when (or (get opts "--symbolic") (get opts "--inductive"))
        (let [typed (model-apalache/generate artifact (cond-> config
                                                        (get opts "--lemmas") (assoc :lemmas (slurp (get opts "--lemmas")))))
              tname (model-apalache/module-name (:specs artifact))]
          (doseq [p (model-apalache/write! dir typed)] (println "wrote" p))
          (let [tc (model-apalache/typecheck! dir tname)]
            (when-not (:ok? tc) (println (:out tc)) (println "the typed specification does not type-check") (System/exit 1)))
          (when-let [n (get opts "--symbolic")]
            (println (format "checking %s symbolically with Apalache, every behaviour of up to %s steps ..." tname n))
            (let [r (model-apalache/check! dir tname {:length (parse-long n)})]
              (cond
                (:ok? r) (println (format "no violation in any behaviour of up to %s steps" n))
                (:violated? r) (do (println (or (:counterexample r) (:out r))) (println "VIOLATION") (System/exit 1))
                :else (do (println (:out r)) (println (:error r)) (System/exit 1)))))
          (when (get opts "--inductive")
            (println (format "checking the inductive invariant of %s with Apalache ..." tname))
            (let [{:keys [init step implies proven?]} (model-apalache/induction! dir tname)
                  report (fn [label r]
                           (println (format "  %s: %s" label (cond (:ok? r) "holds" (:violated? r) "FAILS" :else (str "error: " (:error r)))))
                           (when (:violated? r) (println (or (:counterexample r) (:out r))))
                           (when (and (not (:ok? r)) (not (:violated? r))) (println (:out r))))]
              (report "the initial state satisfies the invariant" init)
              (report "one step from any state in the invariant stays in it" step)
              (report "the invariant implies the properties" implies)
              (if proven?
                (let [invs (:invariants (:specs artifact))
                      proven (sort (map name (keys (filter #(= :provable (:class (val %))) invs))))
                      checkable (sort (map name (keys (filter #(= :checkable (:class (val %))) invs))))]
                  (println "PROVEN: the properties hold in every reachable state, for every tree size the bounds admit")
                  (when (seq proven) (println (str "  with the business invariants " (str/join ", " proven))))
                  (when (seq checkable) (println (str "  not the checkable " (str/join ", " checkable) ": the simulator, the models and the oracle check those"))))
                (do (println "NOT PROVEN: strengthen the lemmas from the state shown (--lemmas <file with a TLA+ predicate>)")
                    (System/exit 1))))))))))

(def commands
  {"init" cmd-init
   "dev" cmd-dev
   "compile" cmd-compile
   "profiles" cmd-profiles
   "inspect" cmd-inspect
   "why" cmd-why
   "history" cmd-history
   "chain" cmd-chain
   "graph" cmd-graph
   "send-event" cmd-send-event
   "replay" cmd-replay
   "verify" cmd-verify
   "check" cmd-check
   "model" cmd-model
   "simulate" cmd-simulate
   "prove" cmd-prove})

(defn -main
  [& args]
  (let [[cmd & rest*] args]
    (if-let [f (get commands cmd)]
      (try (f (vec rest*))
           (catch clojure.lang.ExceptionInfo e
             (binding [*out* *err*] (println (ex-message e)))
             (System/exit 1)))
      (do (println "usage: voucha <command> [args]")
          (println "commands:" (str/join " " (sort (keys commands))))
          (System/exit 2)))))
