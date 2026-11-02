(ns voucha.example.batch
  "The SEPA instant payment example: the reference application (design 67).

  Every handler of this example is a plan on its transition in
  `voucha.profiles.file-batch-transaction`: data, not code, interpreted by
  `voucha.handler` (docs/design/handler-ir.md). The registry below is therefore
  empty; it is where opaque functions and code handlers would go. This proves
  the profile, not a hard-coded kernel: every rule the handlers rely on -- that
  a transaction pays only under a processing batch, that an attempt runs only
  under a paying transaction, that a file finalizes only when every batch is
  terminal -- lives in the artifact, and so do the handlers themselves."
  (:require [voucha.profiles.file-batch-transaction :as fbt]))

(def handlers
  "Code handlers and opaque functions, by symbol. The SEPA example has none."
  {})

(def app
  "A definition, the handlers implementing its transitions, and (empty) adapter
  maps -- `voucha dev` fakes every effect the profile declares, which is what makes
  the reference application runnable without a payment rail."
  {:definition fbt/definition
   :handlers handlers
   :effect-adapters {}
   :store-adapters {}
   ;; what `voucha verify` checks by default: one batch of one instant and one standard transfer
   :verify {:root-type "File" :root-id "F1" :first-event "FileReceived"
            :payload {:name "f" :batches [{:batch-id "B1" :control-sum 300 :count 2
                                           :transactions [{:tx-id "B1-T1" :amount 100 :payment-type "INST"}
                                                          {:tx-id "B1-T2" :amount 200 :payment-type "SCT"}]}]}
            :children {"Batch" 2 "Transaction" 2} :max-repeat 3}})
