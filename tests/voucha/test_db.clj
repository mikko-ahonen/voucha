(ns voucha.test-db
  "Test fixtures backed by a real PostgreSQL. The kernel's correctness claims
  (optimistic locking, atomic transitions) are claims about a real database, so
  the tests use one rather than an in-memory substitute."
  (:require [voucha.db :as db]
            [voucha.migrate :as migrate])
  (:import (io.zonky.test.db.postgres.embedded EmbeddedPostgres)))

(def ^:dynamic *ds* nil)

(defonce ^:private pg (delay (.start (EmbeddedPostgres/builder))))

;; The pool production uses, over the embedded server. Its own datasource opens a
;; connection per request, 3 ms each, which stood in for the kernel's cost in every
;; measurement until the perf scenarios found it (docs/design/performance.md).
(defonce ^:private pool
  (delay (db/pool {:jdbc-url (.getJdbcUrl ^EmbeddedPostgres @pg "postgres" "postgres") :max-pool-size 10})))

(def ^:private tables
  ["effect_write_log" "runtime_record" "timeout_schedule" "outbox"
   "effect_report" "effect_attempt" "effect_instance" "event_log" "node_transition_log"
   "state_node" "execution_tree" "execution_artifact"])

(defn jdbc-url
  "The embedded server's JDBC URL. A separate process (the kill/restart drill)
  connects to the same real PostgreSQL over TCP through this."
  []
  (.getJdbcUrl ^EmbeddedPostgres @pg "postgres" "postgres"))

(defn with-db
  "Once-per-namespace fixture: start PostgreSQL and migrate."
  [f]
  (let [ds @pool]
    (migrate/migrate! ds)
    (binding [*ds* ds]
      (f))))

(defn clean-db
  "Per-test fixture: truncate all kernel tables."
  [f]
  (db/execute! *ds* [(str "TRUNCATE " (clojure.string/join ", " tables) " CASCADE")])
  (f))

;; --- convenience builders ----------------------------------------------------

(defn seed-artifact!
  ([conn] (seed-artifact! conn "artifact-1"))
  ([conn artifact-id]
   (db/execute-one! conn
                    ["INSERT INTO execution_artifact (artifact_id, specs) VALUES (?, ?)
                      ON CONFLICT DO NOTHING" artifact-id "{}"])
   artifact-id))
