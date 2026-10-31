(ns voucha.java-plan-test
  "Java handlers read as plans: the order example's methods extract to the IR
  the kernel runs, a method outside the subset is refused by name, and one
  marked code stays code."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [voucha.example.order :as order]
            [voucha.handler :as handler]
            [voucha.sdk.java-plan :as java-plan]))

(def ^:private plans (java-plan/extract-file "java-src/example/OrderHandlers.java"))

(deftest every-order-handler-is-read-as-a-plan
  (is (= (count (re-seq #"@Transition" (slurp "java-src/example/OrderHandlers.java"))) (count plans))
      "one plan per annotated method")
  (is (empty? (filter :error (vals plans))) (pr-str (filter :error (vals plans))))
  (testing "a branch on a payload, with the event name chosen by it"
    (is (= [[:set [:customer] [:payload :customer]]
            [:set [:items] [:payload :items]]
            [:if [:= [:count [:payload :items]] 0]
             [[:emit-to :self "OrderRejected"]]
             [[:emit-to :self "OrderValid"]]]]
           (:plan (plans 'OrderHandlers/placed)))))
  (testing "a loop creating a child per line, commanded with the line"
    (is (= [[:each :line [:data :items]
             [:child [:get [:var :line] :item-id] "Item"]
             [:emit-to [:get [:var :line] :item-id] "ItemReserveCommanded" [:var :line]]]]
           (:plan (plans 'OrderHandlers/reserve)))))
  (testing "a sum over the lines, a request map, an effect and an await"
    (let [total [:sum :line [:data :items] [:* [:or [:get [:var :line] :qty] 0] [:or [:get [:var :line] :price] 0]]]]
      (is (= [[:set [:total] total]
              [:call "payment.charge" {:order-id [:node-id] :customer [:data :customer] :amount total
                                       :attempt [:or [:data :charge-attempts] 0]}]
              [:await "PaymentCharged"]]
             (:plan (plans 'OrderHandlers/charge))))))
  (testing "a bounded retry: ifs whose branches return"
    (is (= [[:if [:not [:nil? [:data :cancel-requested]]]
             [[:emit-to :self "ChargeGaveUp"]]
             [[:if [:< [:or [:data :charge-attempts] 0] 2]
               [[:inc [:charge-attempts] 1] [:emit-to :self "ChargeRetry"]]
               [[:set [:cancel-reason] [:payload :error]] [:emit-to :self "ChargeGaveUp"]]]]]]
           (:plan (plans 'OrderHandlers/chargeFailed)))))
  (testing "a helper is inlined, with `x == null ? d : x` read as a default"
    (is (= [[:set [:cancel-reason] [:or [:payload :reason] "cancelled by operator"]]
            [:call "inventory.release" {:order-id [:node-id]}]
            [:await "StockReleased"]]
           (:plan (plans 'OrderHandlers/cancelRequestedWhileWaiting)))))
  (testing "a plan that only moves"
    (is (= [] (:plan (plans 'OrderHandlers/waitForItems))))))

(deftest the-order-definition-carries-the-extracted-plans
  (let [ts (filter :handler (:transitions order/definition))]
    (is (seq ts))
    (is (every? :plan ts) "every annotated method is a plan on its transition")
    (is (every? #(= "java" (:plan-source %)) ts))
    (is (= [] (handler/conflicts order/definition order/handlers))
        "the registered methods are the plans' own source, not conflicts")))

(deftest a-method-outside-the-subset-is-refused-by-name
  (let [src "package x;
             import voucha.sdk.*;
             import clojure.lang.IPersistentMap;
             public final class H {
               @Transition(nodeType = \"N\", from = \"A\", on = \"E\", to = \"B\")
               public IPersistentMap clocked(NodeContext ctx, Event event) {
                 long now = System.currentTimeMillis();
                 return ctx.plan().state(\"B\").set(\"at\", now).build();
               }
               @Transition(nodeType = \"N\", from = \"B\", on = \"F\", to = \"C\", code = true)
               public IPersistentMap opaque(NodeContext ctx, Event event) {
                 return ctx.plan().state(\"C\").set(\"at\", System.nanoTime()).build();
               }
               @Transition(nodeType = \"N\", from = \"C\", on = \"G\", to = \"D\")
               public IPersistentMap fine(NodeContext ctx, Event event) {
                 return ctx.plan().state(\"D\").set(\"n\", Values.number(ctx.data(\"n\")) + 1).build();
               }
             }"
        r (java-plan/extract src)]
    (is (str/includes? (:error (r 'H/clocked)) "H/clocked"))
    (is (str/includes? (:error (r 'H/clocked)) "System") "the construct is named")
    (is (= {:code true} (r 'H/opaque)) "code = true is not read")
    (is (= [[:set [:n] [:+ [:or [:data :n] 0] 1]]] (:plan (r 'H/fine))))))
