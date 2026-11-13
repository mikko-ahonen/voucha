(ns voucha.dev-test
  "`voucha dev` and the admin API.

  Design 61 asks for a local runtime in which the whole lifecycle can be driven
  without a network: kernel, dispatchers, fake adapters, simulation controls, and
  an API to inspect what happened. The pieces existed as libraries; nothing
  assembled them.

  This test boots the real thing -- an embedded PostgreSQL, migrations, a compiled
  artifact, worker threads, fakes, an HTTP server -- and drives the reference
  application through it over HTTP, exactly as a developer would."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [voucha.dev :as dev]
            [voucha.example.batch :as app]
            [voucha.intercept :as intercept]
            [voucha.scaffold :as scaffold])
  (:import (java.io File)
           (java.net URI)
           (java.net.http HttpClient HttpRequest HttpRequest$BodyPublishers HttpResponse$BodyHandlers)
           (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

(def ^:dynamic *instance* nil)

(defn with-dev-runtime
  [f]
  (let [instance (dev/start! {:app app/app :port 0})]
    (try
      (binding [*instance* instance] (f))
      (finally ((:stop instance))))))

(use-fixtures :once with-dev-runtime)
;; Debug interceptors live in a process-global registry; clear it after each test
;; so a test that installs one cannot leak into another.
(use-fixtures :each (fn [f] (try (f) (finally (intercept/reset!)))))

;; --- an HTTP client, because the API's contract is HTTP -----------------------

(def ^:private client (HttpClient/newHttpClient))

(defn- url [path] (URI/create (str "http://127.0.0.1:" (:admin-port *instance*) path)))

(defn- send*
  [^HttpRequest request]
  (let [response (.send client request (HttpResponse$BodyHandlers/ofString))]
    {:status (.statusCode response)
     :body (json/read-str (.body response) :key-fn keyword)}))

(defn- GET [path]
  (send* (.. (HttpRequest/newBuilder (url path)) GET build)))

(defn- POST [path body]
  (send* (.. (HttpRequest/newBuilder (url path))
             (header "Content-Type" "application/json")
             (POST (HttpRequest$BodyPublishers/ofString (json/write-str body)))
             build)))

(defn- event! [node-id event-type payload]
  (POST "/event" {:tree-id "dev-tree" :node-id node-id
                  :event-type event-type :payload payload}))

;; --- boot ---------------------------------------------------------------------

(deftest dev-mode-boots-a-complete-runtime
  (is (str/starts-with? (:jdbc-url *instance*) "jdbc:postgresql:")
      "the JDBC url is printed so voucha inspect can reach this instance")
  (is (pos? (:admin-port *instance*)))
  (is (= #{"screening.check" "account.debit" "account.reverse" "rail.inst.send" "rail.sct.submit"
           "file.writeReceive" "file.writeResult"}
         (set (:faked-effects *instance*)))
      "every effect without a real adapter got a fake, and dev mode says which")
  (is (= 200 (:status (GET "/health")))))

;; --- the §61 checklist, over HTTP ----------------------------------------------

(deftest an-event-posted-over-the-admin-api-advances-a-node
  (testing "a tree is created over the API, pinned to the artifact dev mode compiled"
    (let [{:keys [status body]} (POST "/tree" {:tree-id "dev-tree" :root-node-id "F1"
                                               :root-node-type "File"})]
      (is (= 200 status))
      (is (= (get-in *instance* [:artifact :artifact-id]) (:artifact-id body)))))

  (testing "an event is validated against the pinned artifact like any other"
    (let [{:keys [status body]} (event! "F1" "FileReceived" {:name "payments.csv" :batches []})]
      (is (= 200 status))
      (is (= "transitioned" (:outcome body)))))

  (testing "GET /node reports what the kernel actually wrote"
    ;; The dev store worker completes the receive-file write; a file with no
    ;; batches has nothing to wait for, so it writes its (empty) result file and
    ;; completes -- two store writes, driven end to end by the dev workers.
    (let [settled (loop [n 0]
                    (let [{:keys [body]} (GET "/node/dev-tree/F1")]
                      (if (or (= "FILE_COMPLETED" (:local-state body)) (> n 50))
                        (:local-state body)
                        (do (Thread/sleep 100) (recur (inc n))))))]
      (is (= "FILE_COMPLETED" settled)))
    (let [{:keys [body]} (GET "/node/dev-tree/F1")]
      (is (= [] (get-in body [:local-data :batches])))))

  (testing "an event with no transition is rejected, with a reason"
    (let [{:keys [body]} (event! "F1" "FileReceived" {})]
      (is (= "rejected" (:outcome body)))
      (is (re-find #"no transition from FILE_COMPLETED" (:reason body)))))

  (testing "GET /why answers 'why is this node stuck?'"
    (let [{:keys [body]} (GET "/why/dev-tree/F1")]
      (is (= "FILE_COMPLETED" (:local-state body)))
      (is (= #{} (set (:allowed-next-events body))) "terminal: nothing can advance it")))

  (testing "GET /tree walks the whole tree"
    (let [{:keys [body]} (GET "/tree/dev-tree")]
      (is (= "F1" (:root-node-id body)))
      (is (= ["F1"] (mapv :node-id (:nodes body))))))

  (testing "GET on an unknown route is a 404, not a 500"
    (is (= 404 (:status (GET "/nonsense/dev-tree"))))))

(defn- node-state [tree node-id]
  (:local-state (:body (GET (str "/node/" tree "/" node-id)))))

(defn- await-state [tree node-id target]
  (loop [n 0]
    (let [s (node-state tree node-id)]
      (if (or (= target s) (> n 80))
        s
        (do (Thread/sleep 100) (recur (inc n)))))))

(deftest simulation-controls-drive-an-effect-to-completion-over-http
  (POST "/tree" {:tree-id "sim-tree" :root-node-id "F2" :root-node-type "File"})
  ;; One instant transaction. The dev workers drive the whole pipeline
  ;; autonomously -- the store, screening and account fakes all complete -- until
  ;; the instant rail, whose fake only dispatches and then waits for an
  ;; out-of-band response the operator must inject.
  (POST "/event" {:tree-id "sim-tree" :node-id "F2" :event-type "FileReceived"
                  :payload {:name "f"
                            :batches [{:batch-id "SB1"
                                       :transactions [{:tx-id "ST1" :amount 100 :payment-type "INST"}]}]}})

  (testing "the fake store adapter completes the store effect and the node resumes"
    (is (= "FILE_PROCESS_BATCH" (await-state "sim-tree" "F2" "FILE_PROCESS_BATCH"))
        "the dispatcher worker ran the fake receive-file store and its event drove the node"))

  (testing "the fake instant rail stops at its dispatch, as its declared lifecycle says"
    (is (= "ATTEMPT_INST_WAITING" (await-state "sim-tree" "ST1-A0" "ATTEMPT_INST_WAITING"))
        "a fake that only dispatches parks the attempt awaiting the rail's response")
    (is (= "TX_PAYING" (node-state "sim-tree" "ST1"))
        "the transaction did not race to success; it waits on the attempt"))

  (testing "an operator injects the completion the fake never sends"
    (let [{:keys [status body]}
          (POST "/simulate/callback" {:tree-id "sim-tree"
                                      :correlation-key "ST1-A0"
                                      :effect-name "rail.inst.send"
                                      :outcome "completed"
                                      :payload {:rail-ref "MF-BATCH-9"}})]
      (is (= 200 status))
      (is (= "completed" (:applied body))))
    (is (= "ATTEMPT_SUCCEEDED" (await-state "sim-tree" "ST1-A0" "ATTEMPT_SUCCEEDED")))
    (let [{:keys [body]} (GET "/node/sim-tree/ST1-A0")]
      (is (= "MF-BATCH-9" (get-in body [:local-data :rail-ref]))
          "the injected external reference landed in node state")))

  (testing "GET /chain projects the profile's chain view over the finished batch"
    (is (= "BATCH_COMPLETED" (await-state "sim-tree" "SB1" "BATCH_COMPLETED")))
    (let [{:keys [body]} (GET "/chain/sim-tree/BatchChain/SB1")]
      (is (= ["Validate" "Process"]
             (mapv :name (:steps body))))
      (is (every? #(= "complete" (:status %)) (:steps body))))))

(deftest simulated-timeouts-fire-both-kinds
  (let [{:keys [status body]} (POST "/simulate/timeouts" {})]
    (is (= 200 status))
    (is (= 0 (:transition-timeouts body)))
    (is (= 0 (:effect-timeouts body))
        "both the transition-level and the effect-level scans are exposed (design 27)")))

;; --- the debug testing API, over HTTP ------------------------------------------

(defn- worker-tick-hits []
  (->> (:installed (:body (GET "/debug/hits")))
       (filter #(= "worker/tick" (:point %)))
       (map :hits)
       (reduce max 0)))

(defn- poll-until [pred timeout-ms]
  (loop [waited 0]
    (let [v (pred)]
      (cond v v
            (>= waited timeout-ms) nil
            :else (do (Thread/sleep 100) (recur (+ waited 100)))))))

(deftest the-debug-api-installs-an-observe-and-reads-hits-over-http
  (POST "/debug/reset" {})
  (testing "install returns an id, and the namespaced point round-trips intact"
    (let [{:keys [status body]} (POST "/debug/install" {:point "worker/tick" :action "observe"})]
      (is (= 200 status))
      (is (string? (:id body)))
      (is (= ["worker/tick"] (distinct (map :point (:installed (:body (GET "/debug/hits")))))))))

  (testing "the live worker's ticks are observed and counted over HTTP"
    (is (poll-until #(>= (worker-tick-hits) 2) 8000)
        "the dev worker fires :worker/tick; the observe records it"))

  (testing "reset clears the registry"
    (is (= 200 (:status (POST "/debug/reset" {}))))
    (is (empty? (:installed (:body (GET "/debug/hits")))))))

(deftest the-debug-api-blocks-a-worker-and-signals-it-over-http
  ;; The network testing API's headline: park a running worker at a seam, then
  ;; release it from outside the process. Scoped to the dispatcher worker so the
  ;; kernel worker keeps draining, with a timeout so it self-heals regardless.
  (POST "/debug/reset" {})
  (POST "/debug/install" {:point "worker/tick" :action "block" :sig "go"
                          :match [{:path ["worker"] :equals "voucha-dispatcher"}]
                          :timeout-ms 8000})

  (is (poll-until #(>= (worker-tick-hits) 1) 5000) "the dispatcher worker reached the seam")
  (Thread/sleep 600)
  (is (= 1 (worker-tick-hits)) "and parked there: no further ticks while blocked")

  (is (= 200 (:status (POST "/debug/signal" {:sig "go"}))))
  (is (poll-until #(>= (worker-tick-hits) 2) 5000)
      "signalled over HTTP, the worker resumes ticking")

  (POST "/debug/reset" {}))

;; --- voucha init -----------------------------------------------------------------

(deftest init-scaffolds-a-project-that-compiles
  (let [root (.toFile (Files/createTempDirectory "voucha-init" (into-array FileAttribute [])))
        paths (scaffold/init! root "payments-flow")]
    (is (= ["deps.edn" "README.md"
            "src/payments_flow/definition.clj"
            "src/payments_flow/handlers.clj"
            "tests/payments_flow/smoke_test.clj"]
           (mapv #(str/replace % (str (.getPath ^File root) "/") "") paths)))

    (testing "the scaffolded definition is a real definition on the standard profile"
      (let [src (slurp (File. root "src/payments_flow/definition.clj"))]
        (is (re-find #"\(ns payments-flow\.definition" src))
        (is (re-find #"profile/derive-definition" src))))

    (testing "init refuses to clobber an existing project"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Refusing to overwrite"
                            (scaffold/init! root "payments-flow"))))))
