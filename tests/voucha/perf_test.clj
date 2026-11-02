(ns voucha.perf-test
  "The statement accounting the performance tests rest on: a known transition
  produces the statement classes the kernel's path says it does."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [voucha.compiler :as compiler]
            [voucha.db :as db]
            [voucha.example.batch :as app]
            [voucha.kernel :as kernel]
            [voucha.profiles.file-batch-transaction :as fbt]
            [voucha.repo.artifact :as artifact]
            [voucha.runtime :as runtime]
            [voucha.test-db :as tdb :refer [*ds*]]))

(use-fixtures :once tdb/with-db)
(use-fixtures :each tdb/clean-db)

(deftest a-transition-is-accounted-by-statement-class-and-table
  (let [rt (runtime/runtime {:handlers app/handlers})
        aid (db/in-tx [tx *ds*] (let [a (compiler/compile-definition fbt/definition)] (artifact/insert! tx a) (:artifact-id a)))]
    (db/in-tx [tx *ds*]
      (kernel/create-tree! tx {:tree-id "t" :artifact-id aid :root-node-id "F1" :root-node-type "File"})
      (kernel/submit-event! tx {:event-id "e1" :tree-id "t" :target-node-id "F1" :event-type "FileReceived" :payload {:name "f" :batches []}}))
    (let [[outcomes records] (db/with-accounting (kernel/drain! *ds* rt))
          summary (db/summarize records)
          tables (set (map second (keys (dissoc summary :total))))]
      (is (= [:transitioned] (map :outcome outcomes)))
      (is (pos? (get-in summary [:total :n])))
      (is (every? #(and (keyword? (:class %)) (number? (:ms %))) records))
      (is (contains? tables "voucha.claim_event") "the claim")
      (is (contains? tables "voucha.node_get") "the context reads")
      (is (contains? tables "voucha.commit_transition") "the node, the checkpoint and the effect, as one call")
      (is (some #(= :call (:class %)) records))
      (is (nil? db/*accounting*) "off again outside"))))
