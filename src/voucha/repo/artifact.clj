(ns voucha.repo.artifact
  "Persistence for compiled, content-addressed execution artifacts (design 34)."
  (:require [voucha.codec :as codec]
            [voucha.db :as db]))

(defn catalog
  "What the SQL interface's functions need to know of an artifact, as plain JSON
  (docs/design/sql-interface.md §5): the event names, each node type's initial
  state, and each effect's participant and outcome events. The specs themselves
  stay EDN, which the worker reads and SQL cannot."
  [specs]
  {:events (vec (sort (:events specs)))
   :node-types (into {} (map (fn [[nt spec]] [nt {:initial-state (:initial-state spec)}])) (:node-types specs))
   :effects (into {} (map (fn [[n spec]]
                            [n (cond-> {:completion-event (:completion-event spec)
                                        :failure-event (:failure-event spec)}
                                 (:participant spec) (assoc :participant (name (:participant spec))))]))
                  (:effects specs))})

(defn insert!
  "Store a compiled artifact. Idempotent: the artifact_id is its content hash,
  so re-storing identical specs is a no-op, except that an artifact stored
  before the catalog column existed receives its catalog."
  [conn {:keys [artifact-id profile-name profile-version specs]}]
  (db/execute-one!
   conn
   ["INSERT INTO execution_artifact (artifact_id, profile_name, profile_version, specs, catalog)
     VALUES (?, ?, ?, ?, ?)
     ON CONFLICT (artifact_id) DO UPDATE SET catalog = EXCLUDED.catalog
     WHERE execution_artifact.catalog IS NULL"
    artifact-id profile-name profile-version (codec/canonical-edn specs)
    (codec/->jsonb (catalog specs))])
  artifact-id)

;; Fetched artifacts by id. An artifact is immutable and content-addressed -- its id
;; is the hash of its specs -- so once fetched and decoded it can be kept for the
;; life of the process, and a cached id needs no database round trip at all.
;; Without this the kernel transferred and re-parsed the whole profile's EDN on
;; every event (T6 benchmark: half the cost of processing an event).
(defonce ^:private cache (atom {}))

(defn fetch
  [conn artifact-id]
  (or (get @cache artifact-id)
      (when-let [row (db/execute-one!
                      conn
                      ["SELECT artifact_id, profile_name, profile_version, specs
                        FROM execution_artifact WHERE artifact_id = ?" artifact-id])]
        (let [a {:artifact-id (:artifact_id row)
                 :profile-name (:profile_name row)
                 :profile-version (:profile_version row)
                 :specs (codec/read-edn (:specs row))}]
          (swap! cache assoc artifact-id a)
          a))))
