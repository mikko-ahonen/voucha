(ns voucha.killdrill
  "The subprocess side of the hard-kill/restart drill (design: testing-api.md,
  Phase D). Dev-only, like the rest of the Fault tier.

  The parent test sets up a tree in an embedded PostgreSQL and submits the first
  event. This process connects to that same database, commits that first event,
  then arms a `Runtime.halt` at the commit boundary and drives a second event into
  it — so the JVM dies mid-transaction, exactly as a real crash would. The parent
  then asserts the committed work survived, the in-flight work rolled back, and a
  fresh runtime resumes from the checkpoint without redoing anything.

  Run as `java -cp <classpath> clojure.main -m voucha.killdrill <jdbc-url>`."
  (:require [voucha.db :as db]
            [voucha.debug :as debug]
            [voucha.example.batch :as app]
            [voucha.kernel :as kernel]))

(defn -main
  [& [jdbc-url]]
  (let [ds (db/pool {:jdbc-url jdbc-url})
        rt {:handlers app/handlers :event {:max-attempts 3}}]
    ;; 1. Process the receive event the parent left pending. This commits: File
    ;;    FILE_NEW -> WRITING_RECEIVE_FILE, one checkpoint. It is the "completed
    ;;    work" that must survive the crash and never be redone.
    (kernel/drain! ds rt)

    ;; 2. Arm a hard kill at the commit boundary of the next transition.
    (debug/kill! :transition/before-commit)

    ;; 3. Submit and drive the next event: the receive-file store's completion,
    ;;    which would advance the File WRITING_RECEIVE_FILE -> FILE_PROCESS_BATCH.
    ;;    Its transition reaches before-commit and the JVM halts with the
    ;;    transaction still open — the server rolls it back.
    (db/in-tx [tx ds]
      (kernel/submit-event! tx {:event-id "e2" :tree-id "T" :target-node-id "F1"
                                :event-type "ReceiveFileWritten"}))
    (kernel/drain! ds rt)

    ;; Unreachable: the kill above took the process down. If this ever prints, the
    ;; drill did not exercise what it claims to.
    (println "KILLDRILL-SURVIVED")
    (System/exit 0)))
