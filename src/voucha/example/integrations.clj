(ns voucha.example.integrations
  "Emulated external systems for the reference application.

  These are not fakes in the test-double sense: each behaves the way its real
  counterpart behaves, including the parts that make integration hard.

    * `rest-service`  -- synchronous request/response. Answers in one round trip,
                         so the effect goes straight to COMPLETED or FAILED.
                         Returns 5xx as a *thrown* exception, because a server
                         error after the request left is an unknown outcome, not
                         a failure.

    * `message-broker` -- accepts a message and returns nothing but the fact that
                         it left. The effect rests in DISPATCHED. The consumer
                         acknowledges receipt, does the work, and publishes a
                         result, both arriving later on a different channel and
                         correlated by the key that travelled with the message --
                         never by a request id, because there isn't one.

  The broker deduplicates on message id, which is the effect's idempotency key,
  so a retried send after an uncertain outcome enqueues nothing new."
  (:require [voucha.adapter :as adapter]
            [voucha.dispatcher :as dispatcher]))

;; --- a synchronous REST service --------------------------------------------------

(defrecord RestAdapter [handler calls]
  adapter/EffectAdapter
  (dispatch [_ effect]
    (swap! calls conj {:op :dispatch
                       :idempotency-key (:idempotency-key effect)
                       :request (:request-payload effect)})
    (let [{:keys [status body]} (handler (:request-payload effect))]
      (cond
        ;; A server error arrives after the request left our process. Whether the
        ;; other side acted on it is unknown, so it is not reported as a failure.
        (>= status 500)
        (throw (ex-info (format "HTTP %d from screening service" status) {:status status}))

        ;; A client error is a definite answer: the request was understood and refused.
        (>= status 400)
        {:outcome :failed :payload (assoc body :status status)}

        :else
        {:outcome :completed :payload body})))

  (query-status [_ _effect]
    ;; A synchronous endpoint has no out-of-band status resource. An effect that
    ;; needs one must declare :uncertainty-policy :query and a :status-query.
    {:outcome :unknown}))

(defn rest-service
  "`handler` maps a request payload to {:status int :body map}."
  [handler]
  (->RestAdapter handler (atom [])))

(defn rest-calls [a] @(:calls a))

;; --- a message broker ---------------------------------------------------------------

(defrecord BrokerAdapter [broker calls]
  adapter/EffectAdapter
  (dispatch [_ effect]
    (swap! calls conj {:op :dispatch :idempotency-key (:idempotency-key effect)})
    (let [msg {:message-id (:idempotency-key effect)
               :tree-id (:tree-id effect)
               :effect-name (:effect-name effect)
               :correlation-key (:correlation-key effect)
               :body (:request-payload effect)}]
      (swap! broker
             (fn [b]
               (if (contains? (:seen b) (:message-id msg))
                 b                       ; broker-side dedupe on message id
                 (-> b
                     (update :seen conj (:message-id msg))
                     (update :inbound conj msg)))))
      ;; The send returned. That is all we know.
      {:outcome :dispatched}))

  (query-status [_ effect]
    (let [b @broker]
      (if (contains? (:completed b) (:correlation-key effect))
        {:outcome :completed}
        {:outcome :unknown}))))

(defn message-broker
  []
  (atom {:inbound [] :seen #{} :completed #{}}))

(defn broker-adapter [broker] (->BrokerAdapter broker (atom [])))

(defn broker-calls [a] @(:calls a))

(defn depth
  "Messages waiting for a consumer."
  [broker]
  (count (:inbound @broker)))

;; --- the queue processor ---------------------------------------------------------------

(defn- take-message!
  "Pop one message, atomically, the way a competing consumer would."
  [broker]
  (let [msg (atom nil)]
    (swap! broker (fn [b]
                    (if-let [m (first (:inbound b))]
                      (do (reset! msg m) (update b :inbound subvec 1))
                      b)))
    @msg))

(defn process-once!
  "Consume one message and answer, the way a real queue processor does.

  Two responses, not one: the consumer acknowledges receipt before it starts
  work, and publishes the result when it finishes. Both are delivered back to
  the kernel by correlation key -- the only handle the consumer has.

  `work` maps a message body to {:outcome :completed|:failed :payload map}.
  Returns nil when the queue is empty."
  [ds broker work]
  (when-let [{:keys [correlation-key tree-id effect-name body] :as msg} (take-message! broker)]
    (let [deliver (fn [outcome payload]
                    (dispatcher/deliver-callback!
                     ds {:tree-id tree-id
                         :correlation-key correlation-key
                         :effect-name effect-name
                         :outcome outcome
                         :payload payload}))]
      ;; 1. the consumer has the message
      (deliver :acknowledged {:consumer "ledger-poster" :message-id (:message-id msg)})
      ;; 2. it does the work and publishes the result
      (let [{:keys [outcome payload]} (work body)]
        (when (= :completed outcome)
          (swap! broker update :completed conj correlation-key))
        (deliver outcome payload)
        {:message-id (:message-id msg) :outcome outcome}))))

(defn drain!
  "Run the processor until the queue is empty. Returns what it processed."
  [ds broker work]
  (loop [processed []]
    (if-let [r (process-once! ds broker work)]
      (recur (conj processed r))
      processed)))

(defn start-processor!
  "A long-lived consumer thread. Returns a stop function.

  The kernel does not know this exists: the processor speaks to it only through
  `deliver-callback!`, exactly as an out-of-process consumer would."
  [ds broker work {:keys [poll-interval-ms] :or {poll-interval-ms 50}}]
  (let [running (atom true)]
    (doto (Thread. ^Runnable
                   (fn []
                     (while @running
                       (try (when-not (process-once! ds broker work)
                              (Thread/sleep poll-interval-ms))
                            (catch InterruptedException _ (reset! running false))
                            (catch Exception e
                              (println "queue processor error:" (ex-message e))
                              (Thread/sleep poll-interval-ms)))))
                   "voucha-queue-processor")
      (.setDaemon true)
      (.start))
    (fn stop! [] (reset! running false))))
