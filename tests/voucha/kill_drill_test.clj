(ns voucha.kill-drill-test
  "Phase D (design: testing-api.md): the hard-kill/restart drill.

  A `Runtime.halt` in a *subprocess* is the strongest crash a test can stage: no
  shutdown hooks, no `finally`, no in-process exception a fresh thread could paper
  over. It proves the runtime keeps no correctness-critical state in memory — a
  killed node restarts, resumes from the last checkpoint, and finishes without
  re-executing the work that already committed (design 20, 52)."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [voucha.compiler :as compiler]
            [voucha.db :as db]
            [voucha.example.batch :as app]
            [voucha.kernel :as kernel]
            [voucha.profiles.file-batch-transaction :as fbt]
            [voucha.repo.artifact :as artifact]
            [voucha.repo.event :as event]
            [voucha.repo.node :as node]
            [voucha.repo.transition-log :as tlog]
            [voucha.test-db :as tdb :refer [*ds*]])
  (:import (java.util.concurrent TimeUnit)))

(use-fixtures :once tdb/with-db)
(use-fixtures :each tdb/clean-db)

(defn- setup! []
  (let [aid (db/in-tx [tx *ds*]
              (let [a (compiler/compile-definition fbt/definition)]
                (artifact/insert! tx a) (:artifact-id a)))]
    (db/in-tx [tx *ds*]
      (kernel/create-tree! tx {:tree-id "T" :artifact-id aid :root-node-id "F1"
                               :root-node-type "File"})
      (kernel/submit-event! tx {:event-id "e1" :tree-id "T" :target-node-id "F1"
                                :event-type "FileReceived" :payload {:name "f" :batches []}}))))

(defn- state-of [node-id]
  (:local-state (db/in-tx [tx *ds*] (node/fetch tx "T" node-id))))

(defn- status-of [event-id]
  (:status (db/in-tx [tx *ds*] (event/fetch tx event-id))))

(defn- receive-checkpoints []
  (filterv #(= "WRITING_RECEIVE_FILE" (:to-state %))
           (db/in-tx [tx *ds*] (tlog/history tx "T" "F1"))))

(defn- run-killdrill!
  "Launch the subprocess with this JVM's classpath and return {:exit :out}."
  [url]
  (let [java (str (System/getProperty "java.home") "/bin/java")
        cp (System/getProperty "java.class.path")
        out (io/file (System/getProperty "java.io.tmpdir") (str "killdrill-" (System/nanoTime) ".log"))
        pb (doto (ProcessBuilder. [java "-cp" cp "clojure.main" "-m" "voucha.killdrill" url])
             (.redirectErrorStream true)
             (.redirectOutput out))
        proc (.start pb)]
    (if (.waitFor proc 120 TimeUnit/SECONDS)
      {:exit (.exitValue proc) :out (when (.exists out) (slurp out))}
      (do (.destroyForcibly proc) {:exit ::timeout :out (when (.exists out) (slurp out))}))))

(deftest a-hard-killed-runtime-restarts-and-completes-without-redoing-committed-work
  (setup!)
  (let [{:keys [exit out]} (run-killdrill! (tdb/jdbc-url))]
    (testing "the subprocess died mid-transaction, it did not run to completion"
      (is (not= ::timeout exit) (str "kill drill subprocess timed out; output:\n" out))
      (is (not= 0 exit) "Runtime.halt exits non-zero")
      (is (not (re-find #"KILLDRILL-SURVIVED" (or out "")))
          "the kill fired; the process never reached the end"))

    (testing "the work that committed before the kill survived it"
      (is (= "PROCESSED" (status-of "e1")))
      (is (= "WRITING_RECEIVE_FILE" (state-of "F1")))
      (is (= 1 (count (receive-checkpoints))) "exactly one receive-file checkpoint"))

    (testing "the transition that was in flight when the JVM died rolled back"
      (is (= "RECEIVED" (status-of "e2")) "still pending, nothing half-applied")
      (is (not= "FILE_PROCESS_BATCH" (state-of "F1"))))

    (testing "a fresh runtime resumes from the checkpoint and finishes the work"
      (let [rt {:handlers app/handlers :event {:max-attempts 3}}]
        (kernel/drain! *ds* rt)
        (is (= "FILE_BATCHES_PROCESSED" (state-of "F1"))
            "the in-flight event completed on restart (and, with no batches, the file moved on to its result write)")
        (is (= "PROCESSED" (status-of "e2")))
        (is (= 1 (count (receive-checkpoints)))
            "and the already-committed receive-file was never re-executed")))))
