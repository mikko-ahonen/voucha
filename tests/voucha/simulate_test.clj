(ns voucha.simulate-test
  "D16: the simulation executor explores interleavings the kernel could produce
  and checks invariants, liveness at quiescence, and expected states -- with no
  database. It finds what the compiler cannot: a handler that never emits the
  event its own retry state waits for."
  (:require [clojure.test :refer [deftest is testing]]
            [voucha.compiler :as compiler]
            [voucha.example.batch :as app]
            [voucha.example.order :as order]
            [voucha.profiles.file-batch-transaction :as fbt]
            [voucha.simulate :as sim]
            [voucha.workload :as w]))

(def ^:private specs (:specs (compiler/compile-definition fbt/definition)))

(defn- small-workload [seed opts]
  (w/generate seed (merge {:files 1 :batches [1 1] :transactions [2 2]
                           :p-invalid 0 :p-rejected 0 :p-screening-transient 0 :p-rail-fail-once 0 :p-rail-permanent 0}
                          opts)))

(defn- root [wl] (:root-node-id (first (:files wl))))

(defn- with-plan
  "The definition with one transition's plan replaced."
  [d handler-sym plan]
  (update d :transitions (fn [ts] (mapv #(if (= handler-sym (:handler %)) (assoc % :plan plan) %) ts))))

(deftest every-interleaving-of-a-two-rail-file-completes-with-the-expected-states
  (let [wl (small-workload 1 {})
        r (sim/explore (sim/world-for specs wl) app/handlers (sim/workload-script wl) (root wl)
                       ;; the workload's expected end states assume no operator steps in
                       {:expected (:expected wl) :absent (:absent wl) :inject false})]
    (is (:complete? r) (str "explored " (:explored r) " worlds without finishing"))
    (is (> (:explored r) 1) "there is more than one interleaving")
    (is (pos? (:terminal r)) "some worlds are quiescent")
    (is (empty? (:violations r)) (pr-str (take 3 (:violations r))))))

(deftest under-an-adversarial-world-every-interleaving-stays-consistent
  ;; Every effect may succeed or fail, in any order. Invariants must hold in
  ;; every world and every quiescent world must be a finished file -- with
  ;; both successful and failed transactions reachable.
  (let [wl (small-workload 2 {})
        r (sim/explore (sim/world-for specs wl) app/handlers sim/any-outcome (root wl) {:max-worlds 40000})]
    (is (empty? (filter #(not= :I11 (:property %)) (:violations r))) (pr-str (take 3 (:violations r))))
    (is (empty? (:violations r)) "no quiescent world is unfinished")
    (is (> (:explored r) 100))))

(deftest seeded-random-walks-over-a-larger-workload-find-nothing
  (let [wl (w/generate 3 {:files 1 :batches [2 3] :transactions [2 4]
                          :p-invalid 0.2 :p-rejected 0.2 :p-screening-transient 0.2 :p-rail-fail-once 0.2 :p-rail-permanent 0.1})
        script (sim/workload-script wl)]
    (doseq [seed (range 50)]
      (let [r (sim/random-walk (sim/world-for specs wl) app/handlers script (root wl) seed
                               {:steps 2000 :expected (:expected wl) :absent (:absent wl) :inject false})]
        (is (empty? (:violations r)) (str "seed " seed ": " (pr-str (take 2 (:violations r)))))
        (is (:quiescent? r) (str "seed " seed " did not reach quiescence in " (:steps r) " steps"))))))

(deftest a-handler-that-never-emits-its-retry-decision-is-caught-as-a-dead-tree
  ;; The transition TX_SCREEN_RETRY_WAIT -> TX_SCREENING exists, so the compiler is
  ;; satisfied; but this handler lands in the wait state and emits nothing, so a
  ;; transiently failing screening strands the transaction. The simulator sees
  ;; the quiescent, unfinished tree.
  (let [broken (:specs (compiler/compile-definition (with-plan fbt/definition 'tx/screening-decide [])))
        wl (small-workload 4 {:p-screening-transient 1.0})
        r (sim/explore (sim/world-for specs wl) app/handlers (sim/workload-script wl) (root wl) {})
        r* (sim/explore (sim/world-for broken wl) app/handlers (sim/workload-script wl) (root wl) {})]
    (is (empty? (:violations r)) "the real handler retries and the file completes")
    (is (some #(= :I11 (:property %)) (:violations r*)) "the broken handler leaves a dead tree")
    (is (some #(re-find #"TransactionScreeningFailed" (str (:path %))) (:violations r*))
        "and the path shows the transient failure that was never retried")))

(deftest a-command-sent-twice-is-caught-as-a-rejected-event
  ;; The batch commands its screened transactions to pay once. A handler that
  ;; commands them twice leaves a second TransactionPayCommanded for a node
  ;; already paying, which the kernel refuses: the simulator reports the
  ;; rejection, with the path to it, where a database run would only log it.
  (let [twice (:specs (compiler/compile-definition
                       (with-plan fbt/definition 'batch/start-paying
                         [[:each :id [:data :children] [:emit-to [:var :id] "TransactionPayCommanded"]]
                          [:each :id [:data :children] [:emit-to [:var :id] "TransactionPayCommanded"]]])))
        wl (small-workload 5 {})
        r (sim/explore (sim/world-for twice wl) app/handlers (sim/workload-script wl) (root wl) {})]
    (is (some #(= :rejected (:property %)) (:violations r)) (pr-str (take 2 (:violations r))))
    (is (some #(re-find #"no transition from TX_(PAYING|PAY_RETRY_WAIT|COMPLETED) on TransactionPayCommanded" (:detail %)) (:violations r)))))

(deftest a-world-from-a-root-and-its-first-event
  ;; What `voucha simulate` builds: the order example, from OrderPlaced.
  (let [order-specs (:specs (compiler/compile-definition order/definition))
        w (sim/world-from order-specs "Order" "O1" "OrderPlaced"
                          {:customer "C-9" :items [{:item-id "L1" :sku "A" :qty 2 :price 10}
                                                   {:item-id "L2" :sku "B" :qty 1 :price 25}]})
        r (sim/explore w order/handlers sim/any-outcome "O1" {})]
    (is (:complete? r))
    (is (empty? (:violations r)) (pr-str (take 3 (:violations r))))))

(deftest an-attempt-that-skips-the-debit-is-caught-by-a-business-invariant
  ;; A handler that reports the debit done without requesting it. Every state is
  ;; declared, every child allowed, the tree finishes: the framework's properties
  ;; are silent. The definition's own success-only-after-debit sees an attempt in
  ;; ATTEMPT_SUCCEEDED with no completed account.debit, on the first world it reaches.
  ;; (The compiler already refuses a mis-wired target state as unreachable; a wrong
  ;; plan in a right state is what a business invariant is for.)
  (let [skipping (update fbt/definition :transitions
                         (fn [ts] (mapv #(if (= 'attempt/debit (:handler %))
                                           (-> % (assoc :plan [[:emit-to :self "AccountDebited"]]) (dissoc :effects :await :emit :creates :commands))
                                           %) ts)))
        broken (:specs (compiler/compile-definition skipping))
        wl (small-workload 5 {})
        r (sim/explore (sim/world-for broken wl) app/handlers (sim/workload-script wl) (root wl) {:max-worlds 5000})
        business (filter #(= :success-only-after-debit (:property %)) (:violations r))]
    (is (seq business) (pr-str (take 3 (:violations r))))
    (is (re-find #"success-only-after-debit fails for a = .*-A0 \(Attempt ATTEMPT_SUCCEEDED\)" (:detail (first business))))
    (is (empty? (remove #(= :success-only-after-debit (:property %)) (:violations r))) "nothing else sees it")))

(deftest the-outside-worlds-events-are-injected-where-a-node-handles-them
  ;; An operator's cancel is produced by nothing in the order definition; the simulator
  ;; injects it once per node and event wherever the node's state handles it, so the
  ;; cancel during a charge in flight, which only the induction check had reached, is
  ;; explored here too. The example's earlier design, cancelling straight to releasing
  ;; with the charge in flight, is found: the charge completes and its event is rejected.
  (let [order-specs (:specs (compiler/compile-definition order/definition))
        world (fn [specs] (sim/world-from specs "Order" "O1" "OrderPlaced" {:customer "C-9" :items [{:item-id "L1" :sku "A" :qty 1 :price 5}]}))
        r (sim/explore (world order-specs) order/handlers sim/any-outcome "O1" {:max-worlds 20000})]
    (is (= ["OrderCancelRequested" "OrderPlaced"] (compiler/external-events order-specs)) "the first event is external too; the driver sends it")
    (is (seq (#'sim/injections (assoc-in (world order-specs) [:nodes "O1" :local-state] "ORDER_CHARGING")))
        "a cancel is a move for an order being charged")
    (is (:complete? r))
    (is (empty? (:violations r)) (pr-str (take 2 (:violations r))))
    (let [earlier-design (:specs (compiler/compile-definition
                                  (update order/definition :transitions
                                          (fn [ts] (mapv #(if (and (= "ORDER_CHARGING" (:from %)) (= "OrderCancelRequested" (:on %)))
                                                            (-> % (assoc :to "ORDER_RELEASING"
                                                                         :plan [[:set [:cancel-reason] "cancelled by operator"]
                                                                                [:call "inventory.release" {:order-id [:node-id]}]
                                                                                [:await "StockReleased"]])
                                                                (dissoc :effects :await :emit :creates :commands))
                                                            %) ts)))))
          r* (sim/explore (world earlier-design) order/handlers sim/any-outcome "O1" {:max-worlds 20000})]
      (is (some #(= :rejected (:property %)) (:violations r*)) (pr-str (take 2 (:violations r*))))
      (is (some #(re-find #"no transition from ORDER_(RELEASING|CANCELLED) on Payment(Charged|Failed)" (:detail %)) (:violations r*))))))

