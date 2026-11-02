(ns voucha.config
  "Runtime configuration. Read once at startup and passed down explicitly --
  never read from inside operations or transition handlers, which must stay
  deterministic (design 58)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def defaults
  {:db {:jdbc-url "jdbc:postgresql://localhost:5432/voucha"
        :username "voucha"
        :password "voucha"
        :max-pool-size 10}
   :dispatcher {:poll-interval-ms 200
                :batch-size 50}
   :event {:max-attempts 5
           :backoff-ms 500}})

(defn- env
  [k]
  (System/getenv k))

(defn load-config
  "Merge defaults, an optional EDN file, and environment overrides."
  ([] (load-config (or (env "VOUCHA_CONFIG") "voucha.edn")))
  ([path]
   (let [file (io/file path)
         from-file (when (.exists file) (edn/read-string (slurp file)))
         from-env (cond-> {}
                    (env "VOUCHA_DB_URL") (assoc-in [:db :jdbc-url] (env "VOUCHA_DB_URL"))
                    (env "VOUCHA_DB_USER") (assoc-in [:db :username] (env "VOUCHA_DB_USER"))
                    (env "VOUCHA_DB_PASSWORD") (assoc-in [:db :password] (env "VOUCHA_DB_PASSWORD")))]
     (merge-with merge defaults from-file from-env))))
