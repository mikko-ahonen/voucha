(ns voucha.budget-test
  "T6 gate: the benchmarks must stay within their budgets (docs/testing/budgets.edn).

  Runs in the ordinary suite, so CI fails on a regression beyond each metric's
  tolerance. The tolerance is generous because this measures an embedded database
  on whatever runner we got; the precise trend lives in the ledger
  (`clojure -X:test voucha.bench/record!`). A metric with no budget is reported,
  never failed -- add the budget once the ledger has a baseline."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [voucha.bench :as bench]
            [voucha.test-db :as tdb :refer [*ds*]]))

(use-fixtures :once tdb/with-db)
(use-fixtures :each tdb/clean-db)

(deftest benchmarks-stay-within-budget
  (let [results (bench/run-all *ds*)
        budgets (bench/load-budgets)
        g (bench/gate results budgets)]
    (doseq [line (bench/report results g)] (println line))
    (is (some? budgets) "docs/testing/budgets.edn exists")
    (is (empty? (:unbudgeted g)) (str "every metric has a budget: " (pr-str (map :metric (:unbudgeted g)))))
    (is (:ok? g) (pr-str (:regressions g)))))
