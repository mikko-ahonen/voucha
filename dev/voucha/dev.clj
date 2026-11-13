(ns voucha.dev
  "The local dev runtime (design 61).

  One process: an embedded PostgreSQL, the migrations, a compiled artifact, the
  kernel and dispatcher workers, fake adapters for every effect that has no real
  one, and the admin API. No Docker, no network, no external system.

  This namespace lives outside `src` because it is the only part of voucha that
  depends on an embedded database. Running `voucha dev` requires the `:dev` alias;
  a production runtime never loads it."
  (:require [clojure.string :as str]
            [voucha.admin :as admin]
            [voucha.compiler :as compiler]
            [voucha.db :as db]
            [voucha.fake :as fake]
            [voucha.migrate :as migrate]
            [voucha.repo.artifact :as artifact]
            [voucha.runtime :as runtime])
  (:import (io.zonky.test.db.postgres.embedded EmbeddedPostgres)))

(defn- scripted-response
  "How far a fake adapter carries an effect: to the first stop its declared
  lifecycle admits.

  A queue send that declares a `:dispatch-event` stops at DISPATCHED. An effect
  that acknowledges stops at ACKNOWLEDGED. One that only ever completes,
  completes. Everything past that stop is for the simulation controls to inject,
  which is the entire point of dev mode -- a fake that raced to COMPLETED would
  hide the states an operator most needs to see."
  [effect-name spec]
  (cond
    (:dispatch-event spec) {:outcome :dispatched}
    (:ack-event spec) {:outcome :acknowledged :external-request-id (str "dev-" effect-name)}
    :else {:outcome :completed :external-request-id (str "dev-" effect-name)}))

(defn- fill-adapters
  "Supply a fake for every effect the app did not bring a real adapter for.
  Returns the two registries plus the names that were faked, so `dev` can say so
  rather than let a developer believe a mainframe answered."
  [specs {:keys [effect-adapters store-adapters]}]
  (reduce
   (fn [acc [effect-name spec]]
     (let [store? (= :store (:kind spec))
           registry (if store? :store-adapters :effect-adapters)]
       (if (get-in acc [registry effect-name])
         acc
         (-> acc
             (assoc-in [registry effect-name]
                       (if store?
                         (fake/store-adapter)
                         (fake/effect-adapter [(scripted-response effect-name spec)])))
             (update :faked conj effect-name)))))
   {:effect-adapters (or effect-adapters {})
    :store-adapters (or store-adapters {})
    :faked []}
   (:effects specs)))

(defn start!
  "Boot a dev runtime around `app`, a map of

      {:definition <compiled-by-us> :handlers {..}
       :effect-adapters {..} :store-adapters {..}}

  Returns the running instance, including `:stop` and the `:jdbc-url` that
  `voucha inspect` and friends need in order to talk to this database."
  [{:keys [app port scenario seed] :or {port 8710}}]
  (let [pg (.start (EmbeddedPostgres/builder))
        jdbc-url (.getJdbcUrl ^EmbeddedPostgres pg "postgres" "postgres")
        ds (db/pool {:jdbc-url jdbc-url :max-pool-size 10})
        _ (migrate/migrate! ds)
        ;; an application may bring its own tables, data and adapters over this database
        _ (when-let [dir (:migrations app)] (migrate/migrate! ds dir))
        _ (when-let [setup (:setup app)] (setup ds))
        app (if-let [make (:adapters app)]
              (merge-with merge app (select-keys (make ds) [:effect-adapters :store-adapters]))
              app)
        artifact (compiler/compile-definition (:definition app))
        {:keys [effect-adapters store-adapters faked]} (fill-adapters (:specs artifact) app)
        rt (runtime/runtime {:handlers (:handlers app)
                             :effect-adapters effect-adapters
                             :store-adapters store-adapters})
        stop-workers (runtime/start-workers! ds rt)
        _ (artifact/insert! ds artifact)
        placed (when (and scenario (:scenario app))
                 ((:scenario app) ds rt {:artifact-id (:artifact-id artifact) :n scenario :seed (or seed 1)}))
        server (admin/start! ds rt {:port port :artifact artifact})]
    {:ds ds
     :jdbc-url jdbc-url
     :runtime rt
     :artifact artifact
     :faked-effects faked
     :scenario (when placed {:n scenario :seed (or seed 1)})
     :admin-port (:port server)
     :stop (fn stop! []
             ((:stop server))
             (stop-workers)
             (db/close-pool! ds)
             (.close ^EmbeddedPostgres pg))}))

(defn banner
  [{:keys [jdbc-url admin-port artifact faked-effects scenario]}]
  (str/join
   "\n"
   (concat
    [""
     (format "voucha dev runtime — artifact %s" (subs (:artifact-id artifact) 0 16))
     (format "  admin API   http://127.0.0.1:%d" admin-port)
     (format "  database    %s" jdbc-url)
     ""
     (format "  export VOUCHA_DB_URL=%s" (pr-str jdbc-url))
     "  ... so that voucha inspect / why / chain reach this instance"
     ""]
    (when (seq faked-effects)
      [(str "  faked effects (no adapter supplied): " (str/join ", " faked-effects))
       "  simulate their outcomes with POST /simulate/callback and POST /simulate/timeouts"
       ""])
    (when scenario
      [(format "  scenario    %d orders placed (seed %d); watch them with the admin API" (:n scenario) (:seed scenario))
       ""])
    (for [w (:warnings artifact)]
      (format "  warning [%s] %s" (name (:code w)) (:message w))))))

(defn serve!
  "Boot, print how to reach the instance, and block until interrupted."
  [opts]
  (let [instance (start! opts)]
    (println (banner instance))
    (.addShutdownHook (Runtime/getRuntime) (Thread. ^Runnable (:stop instance)))
    @(promise)))
