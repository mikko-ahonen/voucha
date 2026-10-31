(ns voucha.model-p-test
  "The P model of a definition: generated from the artifact, deterministic, and,
  when the P toolchain is installed, compiled and checked. The checker is not
  assumed by CI; those tests skip without `p` on the PATH."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [voucha.compiler :as compiler]
            [voucha.example.order :as order]
            [voucha.model-p :as model-p]
            [voucha.profiles.file-batch-transaction :as fbt]))

(def ^:private sepa (compiler/compile-definition fbt/definition))
(def ^:private sepa-file
  {:name "f.pain"
   :batches [{:batch-id "B1" :control-sum 300 :count 2
              :transactions [{:tx-id "B1-T1" :amount 100 :payment-type "INST"}
                             {:tx-id "B1-T2" :amount 200 :payment-type "SCT"}]}]})
(def ^:private sepa-config {:root-type "File" :root-id "F1" :first-event "FileReceived" :first-payload sepa-file
                            :children {} :max-repeat 3 :allow-drop false})

(def ^:private order-config {:root-type "Order" :root-id "O1" :first-event "OrderPlaced"
                             :first-payload {:customer "C-9" :items [{:item-id "L1" :qty 1 :price 5}]}
                             :children {"Item" 2} :max-repeat 2 :allow-drop false})

(defn- p-installed? []
  (some #(.canExecute (io/file % "p")) (str/split (System/getenv "PATH") #":")))

(deftest the-model-carries-the-definition
  (let [files (model-p/generate sepa sepa-config)
        kernel (get files "PSrc/Kernel.p")
        definition (get files "PSrc/Definition.p")
        driver (get files "PTst/Driver.p")]
    (is (= #{"file_batch_transaction.pproj" "PSrc/Definition.p" "PSrc/Kernel.p" "PSpec/Specs.p" "PTst/Driver.p"}
           (set (keys files))))
    (is (str/includes? definition "S_Transaction_TX_PAYING") "every state, prefixed by its node type")
    (is (str/includes? definition "E_TransactionPayCommanded"))
    (testing "transitions with their guards, children, commands and emits"
      (is (str/includes? kernel "ckind = CK_COUNTERS_COVER"))
      (is (str/includes? kernel "ckind = CK_PARENT_STATE"))
      (is (re-find #"sp \+= \(0, NT_Transaction\);" kernel) "batch/prepare creates transactions")
      (is (re-find #"cm \+= \(0, E_TransactionStartCommanded\);" kernel) "and commands them")
      (is (re-find #"em \+= \(1, E_TransactionGaveUp\);" kernel) "the give-up event is the last alternative")
      (is (str/includes? kernel "isAuto = true") "auto transitions are marked"))
    (testing "plans are translated, not abstracted"
      (is (str/includes? kernel "hasPlan = true"))
      (is (str/includes? kernel "SumField(Get(payload, \"transactions\"), \"amount\")") "the control-sum check")
      (is (str/includes? kernel "NewNode(NT_Transaction, node, ToStr(Get(v_t, \"tx-id\")));") "children from the records")
      (is (str/includes? kernel "if (Lt(Or2(Get(d0, \"attempts\"), 0), 3))") "the bounded retry")
      (is (str/includes? kernel "EnqueueTo(ToStr(v_id), E_TransactionPayCommanded, node, EmptyMap());") "commands to each child"))
    (testing "the tables the kernel enforces"
      (is (str/includes? kernel "ignores += (sizeof(ignores), (ntype = NT_Transaction, nstate = S_Transaction_TX_FAILED, evt = E_TransactionPayCommanded));"))
      (is (str/includes? kernel "routes += (sizeof(routes), (evt = E_BatchSettled, fromType = NT_Batch, toType = NT_File, ignoreUnsat = true));"))
      (is (str/includes? kernel "cname = \"tx-ready\""))
      (is (str/includes? kernel "allowed += (sizeof(allowed), (ntype = NT_File, pstate = S_File_FILE_COMPLETED, childType = NT_Batch")))
    (testing "the driver is the run configuration, payload included"
      (is (str/includes? driver "rootType = NT_File, rootId = \"F1\", firstEvent = E_FileReceived"))
      (is (str/includes? driver "[\"control-sum\"] = 300;"))
      (is (str/includes? driver "[\"tx-id\"] = \"B1-T2\";"))
      (is (str/includes? driver "maxRepeat = 3, allowDrop = false")))
    (is (= files (model-p/generate sepa sepa-config)) "deterministic")
    (is (str/includes? kernel (:artifact-id sepa)) "stamped with the artifact it came from")))

(deftest the-configuration-is-checked
  (is (thrown? clojure.lang.ExceptionInfo (model-p/generate sepa (assoc sepa-config :root-type "Nope"))))
  (is (thrown? clojure.lang.ExceptionInfo (model-p/generate sepa (assoc sepa-config :first-event "Nope")))))

(deftest the-order-example-checks-clean-under-the-p-checker
  (if-not (p-installed?)
    (println "  [skip] P toolchain not on PATH; the model is generated but not checked")
    (let [dir (str "tmp/model-test/order-" (System/nanoTime))
          artifact (compiler/compile-definition order/definition)
          files (model-p/generate artifact {:root-type "Order" :root-id "O1" :first-event "OrderPlaced"
                                            :first-payload {:customer "C-9" :items [{:item-id "L1" :qty 1 :price 5}]}
                                            :children {"Item" 2} :max-repeat 2 :allow-drop false})]
      (model-p/write! dir files)
      (let [r (model-p/check! dir (model-p/project-name (:specs artifact)) {:schedules 100})]
        (is (:ok? r) (str (:out r) (:trace r)))
        (is (= 0 (:bugs r)))))))

(deftest a-definition-that-refuses-a-command-is-found-by-the-checker
  ;; Without TX_FAILED's :ignores, the batch's pay command reaches a transaction
  ;; that failed screening, which the kernel would reject: the model asserts.
  (if-not (p-installed?)
    (println "  [skip] P toolchain not on PATH")
    (let [dir (str "tmp/model-test/sepa-" (System/nanoTime))
          flawed (compiler/compile-definition (update-in fbt/definition [:node-types "Transaction"] dissoc :ignores))
          files (model-p/generate flawed sepa-config)]
      (model-p/write! dir files)
      (let [r (model-p/check! dir (model-p/project-name (:specs flawed)) {:schedules 300})]
        (is (false? (:ok? r)))
        (is (pos? (or (:bugs r) 0)))
        (is (re-find #"rejected: Transaction \S+ has no transition from TX_FAILED on TransactionPayCommanded" (str (:trace r))))))))

(deftest the-business-invariants-are-asserted-after-every-step
  (let [files (model-p/generate (compiler/compile-definition order/definition) order-config)
        kernel (get files "PSrc/Kernel.p")]
    (is (str/includes? kernel "fun Inv_one_charge_per_order(): bool {"))
    (is (str/includes? kernel "if (especs[e6.especRef].ename == \"payment.charge\" && e6.owner == p_o) {"))
    (is (str/includes? kernel "assert Inv_shipped_is_paid(), \"business invariant shipped-is-paid fails\";"))))

(deftest a-business-invariant-violation-is-found-by-the-checker
  (if-not (p-installed?)
    (println "  [skip] P toolchain not on PATH")
    (let [dir (str "tmp/model-test/sepa-skip-" (System/nanoTime))
          flawed (compiler/compile-definition
                  (update fbt/definition :transitions
                          (fn [ts] (mapv #(if (= 'attempt/debit (:handler %))
                                            (-> % (assoc :plan [[:emit-to :self "AccountDebited"]]) (dissoc :effects :await :emit :creates :commands))
                                            %) ts))))]
      (model-p/write! dir (model-p/generate flawed sepa-config))
      (let [r (model-p/check! dir (model-p/project-name (:specs flawed)) {:schedules 300})]
        (is (false? (:ok? r)))
        (is (re-find #"business invariant (success|reversal)-only-after-debit fails" (str (:trace r) (:out r))))))))

