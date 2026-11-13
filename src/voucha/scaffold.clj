(ns voucha.scaffold
  "`voucha init`: a new project that runs before it is edited (design 60).

  The scaffold layers a domain definition on the standard `FileBatchTransaction`
  profile and starts from the reference handlers, overriding one so the shape of a
  handler is visible in the project rather than only in the documentation. It
  compiles, it boots under `voucha dev`, and its smoke test passes -- which is what
  makes the first edit a change rather than a leap."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]))

(defn- checkout-root
  "When `voucha init` runs from a voucha checkout, the new project can depend on
  it directly; otherwise it depends on the released artifact."
  []
  (let [dir (io/file (System/getProperty "user.dir"))]
    (when (.exists (io/file dir "src/voucha/kernel.clj"))
      (.getAbsolutePath dir))))

(defn- deps-edn
  [_project]
  (format ";; voucha is not yet published to a Maven repository. This project depends on
;; %s
{:paths [\"src\" \"resources\"]

 :deps
 {org.clojure/clojure {:mvn/version \"1.12.0\"}
  voucha/voucha %s}

 :aliases
 {:test
  {:extra-paths [\"tests\"]
   :extra-deps {io.github.cognitect-labs/test-runner
                {:git/tag \"v0.5.1\" :git/sha \"dfb30dd\"}
                io.zonky.test/embedded-postgres {:mvn/version \"2.0.7\"}
                io.zonky.test.postgres/embedded-postgres-binaries-linux-amd64
                {:mvn/version \"16.2.0\"}}
   :exec-fn cognitect.test-runner.api/test
   :exec-args {:dirs [\"tests\"]}}}}
"
          (if-let [root (checkout-root)]
            (str "the checkout at " root ".")
            "a released version; point :local/root at a checkout to use one instead.")
          (if-let [root (checkout-root)]
            (format "{:local/root \"%s\"}" root)
            "{:mvn/version \"0.1.0\"}")))

(defn- definition-clj
  [ns-base]
  (format "(ns %s.definition
  \"The domain layer (design 7, layer 4) on top of the standard profile.

  Everything the kernel enforces about this machine lives here and is compiled
  into a content-addressed artifact. Add states, transitions, effects and
  constraints to `domain`; the profile supplies the hierarchy and the default
  lifecycles.\"
  (:require [voucha.profile :as profile]
            [voucha.profiles.file-batch-transaction :as fbt]
            [%s.handlers :as handlers]))

(def domain
  \"Layered over the profile, key by key: a domain may add states, counters,
  transitions and effect permissions without restating the profile's lifecycle.\"
  {:events #{}
   :effects {}
   :node-types {}
   :transitions []})

(def definition
  \"The profile with the domain layered over it. The one transition this project
  implements in code drops the profile's plan for it; every other transition
  keeps running the profile's plan.\"
  (update (profile/derive-definition fbt/definition domain) :transitions
          (fn [ts] (mapv (fn [t] (if (= 'file/received (:handler t)) (dissoc t :plan) t)) ts))))

(def app
  \"What `voucha dev` and `voucha verify` need: a definition, the handlers that
  implement its transitions, and the adapters that reach the outside world. Any
  effect without an adapter gets a fake in dev mode.\"
  {:definition definition
   :handlers handlers/handlers
   :effect-adapters {}
   :store-adapters {}})
" ns-base ns-base))

(defn- handlers-clj
  [ns-base]
  (format "(ns %s.handlers
  \"Transition handlers. A handler is deterministic: it receives context and an
  event, and returns a plan. It never calls a database, a clock, or an external
  system -- deciding that an effect should happen is the handler's job, and making
  it happen is the dispatcher's (design 23, 58).\"
  (:require [voucha.example.batch :as reference]
            [voucha.plan :as plan]))

(defn file-received
  \"Yours to change. The event carries the file; the plan carries the decision:
  remember the batches, archive the file, expect to hear it was written. Note
  that nothing here writes a file -- the store effect is an intent a dispatcher
  performs after this transaction commits.\"
  [_ctx ev]
  (-> (plan/plan)
      (plan/state \"WRITING_RECEIVE_FILE\")
      (plan/patch (list 'assoc-in [:batches] (get-in ev [:payload :batches])))
      (plan/store \"file.writeReceive\" {:name (get-in ev [:payload :name])})
      (plan/await* \"ReceiveFileWritten\")))

(def handlers
  \"Start from the reference application's handlers and override as you go. Every
  transition the artifact declares must have one, or the kernel refuses the
  event.\"
  (assoc reference/handlers 'file/received file-received))
" ns-base))

(defn- smoke-test-clj
  [ns-base]
  (format "(ns %s.smoke-test
  \"The harness builds a tree in whatever state a test needs, sends an event, and
  asserts on the outcome (design 62). No mainframe, no queue, no clock.\"
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [voucha.db :as db]
            [voucha.harness :as harness]
            [voucha.migrate :as migrate]
            [%s.definition :as d])
  (:import (io.zonky.test.db.postgres.embedded EmbeddedPostgres)))

(def ^:dynamic *ds* nil)

(defn with-db [f]
  (with-open [pg (.start (EmbeddedPostgres/builder))]
    (let [ds (db/pool {:jdbc-url (.getJdbcUrl pg \"postgres\" \"postgres\")})]
      (try (migrate/migrate! ds)
           (binding [*ds* ds] (f))
           (finally (db/close-pool! ds))))))

(use-fixtures :once with-db)

(deftest a-file-starts-processing-when-received
  (-> (harness/for-artifact *ds* d/definition {:handlers (:handlers d/app)})
      (harness/given-tree [{:id \"F1\" :type \"File\" :state \"FILE_NEW\"}])
      (harness/when-event \"F1\" \"FileReceived\" {:name \"pain.001\" :batches []})
      (harness/expect-transitioned)
      (harness/expect-state \"F1\" \"WRITING_RECEIVE_FILE\")
      (harness/expect-effect \"F1\" \"file.writeReceive:v1\"))
  (is true \"the harness throws on an unmet expectation\"))
" ns-base ns-base))

(defn- readme-md
  [project ns-base]
  (format "# %s

A voucha process definition on the standard `FileBatchTransaction` profile.

    clojure -X:test                        # smoke test, against an embedded database
    voucha compile %s.definition/app         # compile and store the artifact
    voucha dev %s.definition/app             # embedded database, fakes, admin API

`voucha dev` runs the kernel and the dispatchers in one process, fakes every effect
that has no adapter, and serves an admin API on `127.0.0.1:8710`:

    curl -XPOST localhost:8710/tree  -d '{\"tree-id\":\"t1\",\"root-node-id\":\"F1\",\"root-node-type\":\"File\"}'
    curl -XPOST localhost:8710/event -d '{\"tree-id\":\"t1\",\"node-id\":\"F1\",\"event-type\":\"FileReceived\",\"payload\":{\"name\":\"payments.csv\",\"batches\":[]}}'
    curl localhost:8710/why/t1/F1
    curl -XPOST localhost:8710/simulate/callback -d '{\"external-request-id\":\"...\",\"outcome\":\"completed\"}'
    curl -XPOST localhost:8710/simulate/timeouts -d '{}'

It also prints a `VOUCHA_DB_URL` to export; with that exported, `voucha inspect`,
`voucha why`, `voucha history` and `voucha chain` talk to the running instance.
" project ns-base ns-base))

(defn- ns-base
  "Clojure namespaces use dashes; directories use underscores."
  [project]
  (-> project str/lower-case (str/replace #"[^a-z0-9-]+" "-") (str/replace #"^-+|-+$" "")))

(defn- write!
  [root path contents]
  (let [f (io/file root path)]
    (io/make-parents f)
    (when (.exists f)
      (throw (ex-info (format "Refusing to overwrite %s" (.getPath f))
                      {:type ::file-exists :path (.getPath f)})))
    (spit f contents)
    (.getPath f)))

(defn init!
  "Scaffold a project into `root`. Returns the paths written.

  Never overwrites: an `init` into a directory that already holds a project is a
  mistake, and a silently clobbered definition is an expensive one."
  [root project]
  (let [base (ns-base project)
        dir (str/replace base "-" "_")]
    (mapv (fn [[path contents]] (write! root path contents))
          [["deps.edn" (deps-edn project)]
           ["README.md" (readme-md project base)]
           [(str "src/" dir "/definition.clj") (definition-clj base)]
           [(str "src/" dir "/handlers.clj") (handlers-clj base)]
           [(str "tests/" dir "/smoke_test.clj") (smoke-test-clj base)]])))
