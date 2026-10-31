(ns voucha.admin
  "The local dev-mode admin API (design 61).

  A thin JSON skin over `voucha.inspect`, `voucha.kernel/submit-event!` and the
  dispatcher's simulation entry points, so the whole lifecycle -- send an event,
  watch a node move, inject an acknowledgement, fire a timeout, ask why something
  is stuck -- can be driven without a REPL.

  It answers only what the CLI already answers. Nothing here reaches into the
  kernel: an event posted through this API is an ordinary event, validated against
  the tree's pinned artifact like any other, and a simulated callback goes through
  the same `deliver-callback!` a real adapter would use.

  There is no authentication. Authorization is the deployment's concern (decision
  D11) and this server binds to loopback, because it is a development tool."
  (:require [clojure.data.json :as json]
            [clojure.string :as str]
            [voucha.codec :as codec]
            [voucha.db :as db]
            [voucha.dispatcher :as dispatcher]
            [voucha.inspect :as inspect]
            [voucha.intercept :as intercept]
            [voucha.repo.event :as event]
            [voucha.kernel :as kernel]
            [voucha.metrics :as metrics])
  (:import (com.sun.net.httpserver HttpExchange HttpHandler HttpServer)
           (java.io InputStreamReader)
           (java.net InetSocketAddress)
           (java.nio.charset StandardCharsets)))

(defn- jsonable
  "Timestamps, keywords and symbols all reach here from artifacts and database
  rows. Render anything JSON does not know as its printed form rather than
  failing the request."
  [_k v]
  (cond
    (or (nil? v) (string? v) (number? v) (boolean? v) (coll? v)) v
    (keyword? v) (subs (str v) 1)   ; keep the namespace: :worker/tick -> "worker/tick"
    :else (str v)))

(defn- respond!
  [^HttpExchange exchange status body]
  (let [bytes (.getBytes ^String (json/write-str body :value-fn jsonable :key-fn name)
                         StandardCharsets/UTF_8)]
    (.set (.getResponseHeaders exchange) "Content-Type" "application/json")
    (.sendResponseHeaders exchange status (alength bytes))
    (with-open [out (.getResponseBody exchange)]
      (.write out bytes))))

(defn- read-body
  [^HttpExchange exchange]
  (with-open [r (InputStreamReader. (.getRequestBody exchange) StandardCharsets/UTF_8)]
    (let [s (slurp r)]
      (if (str/blank? s) {} (json/read-str s :key-fn keyword)))))

(defn- path-segments
  [^HttpExchange exchange]
  (into [] (remove str/blank?) (str/split (.getPath (.getRequestURI exchange)) #"/")))

;; --- routes --------------------------------------------------------------------

(def ^:private status->outcome
  {event/status-processed :transitioned
   event/status-rejected :rejected
   event/status-ignored :ignored
   event/status-parked :failed})

(defn- settled-outcome
  "What became of one event. Poll rather than trust the drain we ran ourselves:
  in dev mode the kernel worker is draining too, and it may well have taken this
  event first, in which case our own drain returns nothing about it."
  [ds event-id deadline-ms]
  (loop [waited 0]
    (let [ev (db/in-tx [tx ds] (event/fetch tx event-id))
          outcome (status->outcome (:status ev))]
      (cond
        outcome (cond-> {:outcome outcome}
                  (:last-error ev) (assoc :reason (:last-error ev)))
        (>= waited deadline-ms) {:outcome :pending}
        :else (do (Thread/sleep 25) (recur (+ waited 25)))))))

(defn- submit-and-drain!
  "Submit an event, process what it sets off, and report what became of it -- so an
  HTTP caller sees the tree as it will settle rather than as it was a millisecond
  ago."
  [ds runtime event]
  (db/in-tx [tx ds] (kernel/submit-event! tx event))
  (kernel/drain! ds runtime)
  (settled-outcome ds (:event-id event) 5000))

(defn- route
  "Resolve one request to a value, or throw. Every read runs in its own
  transaction, against the tree's pinned artifact."
  [ds runtime artifact ^HttpExchange exchange]
  (let [method (.getRequestMethod exchange)
        segments (path-segments exchange)]
    (case (into [method] segments)
      ["GET" "health"]
      (db/in-tx [tx ds] (metrics/snapshot tx))

      ;; A tree pins the artifact this instance compiled. Without this route a
      ;; fresh project has nothing to send events to.
      ["POST" "tree"]
      (let [{:keys [tree-id root-node-id root-node-type]} (read-body exchange)]
        (when-not (and tree-id root-node-id root-node-type)
          (throw (ex-info "tree-id, root-node-id and root-node-type are required"
                          {:type ::bad-request})))
        (db/in-tx [tx ds]
          (kernel/create-tree! tx {:tree-id tree-id
                                   :artifact-id (:artifact-id artifact)
                                   :root-node-id root-node-id
                                   :root-node-type root-node-type
                                   :profile-name (:profile-name artifact)
                                   :profile-version (:profile-version artifact)}))
        {:tree-id tree-id :artifact-id (:artifact-id artifact) :root-node-id root-node-id})

      ["POST" "event"]
      (let [{:keys [tree-id node-id event-type payload operator-id operator-reason]}
            (read-body exchange)]
        (when-not (and tree-id node-id event-type)
          (throw (ex-info "tree-id, node-id and event-type are required" {:type ::bad-request})))
        (merge {:submitted event-type :to node-id}
               (submit-and-drain! ds runtime
                                  {:event-id (codec/sha256 (str (random-uuid)))
                                   :tree-id tree-id
                                   :target-node-id node-id
                                   :event-type event-type
                                   :payload (or payload {})
                                   :operator-id operator-id
                                   :operator-reason operator-reason
                                   ;; Transient: an installed Level-1 authenticator
                                   ;; resolves this to actor/claims; it never persists.
                                   :credentials (when-let [a (.getFirst (.getRequestHeaders exchange)
                                                                        "Authorization")]
                                                  {:authorization a})})))

      ;; Dev testing API (design: testing-api.md). Installing a fault needs the
      ;; dev-only action builder; signal/reset/hits are core registry ops.
      ["POST" "debug" "install"]
      (if-let [install (try (requiring-resolve 'voucha.debug/install-from-spec!)
                            (catch Exception _ nil))]
        {:id (name (install (read-body exchange)))}
        (throw (ex-info "debug API needs the :dev alias" {:type ::not-found})))

      ["POST" "debug" "signal"]
      (let [{:keys [sig]} (read-body exchange)]
        (when-not sig (throw (ex-info "sig is required" {:type ::bad-request})))
        (intercept/signal! (keyword sig))
        {:signalled sig})

      ["POST" "debug" "reset"]
      (do (intercept/reset!) {:reset true})

      ["GET" "debug" "hits"]
      {:installed (intercept/installed)}

      ;; Simulation (design 61): acknowledgement, completion and failure all arrive
      ;; through the same door a real adapter's callback would use.
      ["POST" "simulate" "callback"]
      (let [{:keys [outcome payload] :as delivery} (read-body exchange)]
        (when-not outcome (throw (ex-info "outcome is required" {:type ::bad-request})))
        (let [applied (dispatcher/deliver-callback!
                       ds (assoc delivery :outcome (keyword outcome) :payload (or payload {})))]
          {:applied (name applied) :outcomes (kernel/drain! ds runtime)}))

      ["POST" "simulate" "timeouts"]
      {:transition-timeouts (dispatcher/fire-due-timeouts! ds)
       :effect-timeouts (dispatcher/fire-effect-timeouts! ds)
       :outcomes (kernel/drain! ds runtime)}

      (let [[kind & args] segments
            arity (count args)]
        (cond
          (not= "GET" method)
          (throw (ex-info (str "No route for " method " /" (str/join "/" segments))
                          {:type ::not-found}))

          (and (= "tree" kind) (= 1 arity))
          (db/in-tx [tx ds] (inspect/tree-view tx (first args)))

          (and (= "node" kind) (= 2 arity))
          (db/in-tx [tx ds] (apply inspect/node-view tx args))

          (and (= "why" kind) (= 2 arity))
          (db/in-tx [tx ds] (apply inspect/why-stuck tx args))

          (and (= "chain" kind) (= 3 arity))
          (db/in-tx [tx ds] (apply inspect/chain-view tx args))

          :else
          (throw (ex-info (str "No route for GET /" (str/join "/" segments))
                          {:type ::not-found})))))))

(defn- status-for
  [e]
  ;; A rejecting authenticator names its own status (401/403); otherwise map the
  ;; error type.
  (or (:http-status (ex-data e))
      (case (:type (ex-data e))
        ::not-found 404
        ::bad-request 400
        500)))

(defn- handler
  [ds runtime artifact]
  (reify HttpHandler
    (handle [_ exchange]
      (try
        (respond! exchange 200 (route ds runtime artifact exchange))
        (catch clojure.lang.ExceptionInfo e
          (respond! exchange (status-for e) {:error (ex-message e)
                                             :detail (dissoc (ex-data e) :type)}))
        (catch Exception e
          (respond! exchange 500 {:error (or (ex-message e) (str (class e)))}))))))

(defn start!
  "Start the admin API on loopback. Returns {:port n :stop (fn [])}.

  Port 0 asks the operating system for a free port, which is what the tests use.
  `artifact` is the compiled artifact this instance runs; trees created through
  the API pin it."
  [ds runtime {:keys [port artifact] :or {port 8710}}]
  (let [server (HttpServer/create (InetSocketAddress. "127.0.0.1" (int port)) 0)]
    (.createContext server "/" (handler ds runtime artifact))
    (.setExecutor server nil)
    (.start server)
    {:port (.getPort (.getAddress server))
     :stop (fn stop! [] (.stop server 0))}))
