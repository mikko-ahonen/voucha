(ns voucha.metrics
  "Operational health of a running kernel (design 63).

  These are the numbers an operator watches: nodes that cannot advance, effects
  that will not settle, events that have parked, timeouts about to fire. Each is
  a query, not a counter kept in memory, so a freshly started process reports the
  same truth as one that has been up for a week."
  (:require [voucha.codec :as codec]
            [voucha.db :as db]
            [voucha.repo.effect :as effect]
            [voucha.repo.event :as event]))

(defn effect-backlog
  "Outstanding effects by status and name. A growing DISPATCH_FAILED count means
  an external system is down; a growing ACKNOWLEDGED count means it accepted work
  it has not finished."
  [conn]
  (mapv (fn [r] {:effect-name (:effect_name r)
                 :status (:status r)
                 :count (:n r)
                 :oldest (:oldest r)})
        (db/execute! conn
                     (into ["SELECT effect_name, status, COUNT(*) AS n, MIN(created_at) AS oldest
                              FROM effect_instance
                             WHERE status NOT IN (?,?,?,?)
                             GROUP BY effect_name, status
                             ORDER BY n DESC"]
                           (vec effect/terminal-statuses)))))

(defn uncertain-effects
  "Effects awaiting operator resolution: dispatched with an unknown outcome and a
  policy that forbids both retry and query (decision D8)."
  [conn]
  (mapv (fn [r] {:effect-instance-id (:effect_instance_id r)
                 :tree-id (:tree_id r)
                 :owner-node-id (:owner_node_id r)
                 :effect-name (:effect_name r)
                 :external-request-id (:external_request_id r)})
        (db/execute! conn
                     ["SELECT * FROM effect_instance
                        WHERE status = ? AND next_attempt_at IS NULL
                          AND failure_payload->>'requires-operator' = 'true'"
                      effect/status-dispatch-failed])))

(defn unresolved-external-work
  "Work the external system may still hold, on effects the runtime has closed.

  Cancelling an effect whose request may already have been sent settles the
  runtime's side of it and nothing more (design 16, 27). The effect is terminal,
  so it has left `effect-backlog` and no event will ever resolve it -- but a
  human must still reconcile whatever the outside world is holding."
  [conn]
  (mapv (fn [r] {:effect-instance-id (:effect_instance_id r)
                 :tree-id (:tree_id r)
                 :owner-node-id (:owner_node_id r)
                 :effect-name (:effect_name r)
                 :status (:status r)
                 :external-request-id (:external_request_id r)
                 :prior-status (get (codec/<-jsonb (:failure_payload r)) :prior-status)})
        (db/execute! conn
                     (into ["SELECT * FROM effect_instance
                              WHERE failure_payload->>'requires-operator' = 'true'
                                AND status IN (?,?,?,?)
                              ORDER BY updated_at"]
                           (vec effect/terminal-statuses)))))

(defn parked-events
  [conn]
  (mapv (fn [r] {:event-id (:event_id r)
                 :tree-id (:tree_id r)
                 :target-node-id (:target_node_id r)
                 :event-type (:event_type r)
                 :attempt-count (:attempt_count r)
                 :last-error (:last_error r)})
        (db/execute! conn ["SELECT * FROM event_log WHERE status = ? ORDER BY received_at"
                           event/status-parked])))

(defn event-backlog
  [conn]
  (:n (db/execute-one! conn ["SELECT COUNT(*) AS n FROM event_log WHERE status = ?"
                             event/status-received])))

(defn due-timeouts
  [conn]
  (:n (db/execute-one! conn ["SELECT COUNT(*) AS n FROM timeout_schedule
                              WHERE status = 'PENDING' AND fire_at <= now()"])))

(defn stalled-nodes
  "Non-terminal nodes that have not moved in `older-than-minutes`, with whatever
  they are waiting on. This is the list an operator triages."
  [conn older-than-minutes]
  (mapv (fn [r] {:tree-id (:tree_id r)
                 :node-id (:node_id r)
                 :node-type (:node_type r)
                 :local-state (:local_state r)
                 :idle-minutes (some-> (:idle_minutes r) double Math/round)
                 :outstanding-effects (:outstanding r)})
        (db/execute!
         conn
         (into ["SELECT n.tree_id, n.node_id, n.node_type, n.local_state,
                        EXTRACT(EPOCH FROM (now() - n.updated_at)) / 60 AS idle_minutes,
                        (SELECT COUNT(*) FROM effect_instance e
                          WHERE e.tree_id = n.tree_id AND e.owner_node_id = n.node_id
                            AND e.status NOT IN (?,?,?,?)) AS outstanding
                   FROM state_node n
                  WHERE n.status = 'ACTIVE'
                    AND n.updated_at < now() - (? * INTERVAL '1 minute')
                  ORDER BY n.updated_at"]
               (conj (vec effect/terminal-statuses) older-than-minutes)))))

(defn snapshot
  "One call an operator dashboard or health check can poll."
  ([conn] (snapshot conn 30))
  ([conn stall-minutes]
   {:event-backlog (event-backlog conn)
    :parked-events (count (parked-events conn))
    :due-timeouts (due-timeouts conn)
    :effect-backlog (effect-backlog conn)
    :uncertain-effects (count (uncertain-effects conn))
    :unresolved-external-work (count (unresolved-external-work conn))
    :stalled-nodes (count (stalled-nodes conn stall-minutes))}))

(defn log-line
  "A single structured line, suitable for a log scraper."
  [snap]
  (pr-str (assoc snap :metric "voucha.health")))
