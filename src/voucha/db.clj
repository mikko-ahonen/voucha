(ns voucha.db
  "Datasource construction and the kernel transaction boundary.

  The kernel performs all runtime-owned writes inside one database transaction
  and never performs external I/O there (design 52)."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs])
  (:import (com.zaxxer.hikari HikariConfig HikariDataSource)))

(def default-opts
  {:builder-fn rs/as-unqualified-lower-maps})

(defn pool
  "Build a pooled datasource. `spec` is {:jdbc-url .. :username .. :password ..}."
  ^HikariDataSource [{:keys [jdbc-url username password max-pool-size connection-timeout-ms]
                      :or {max-pool-size 10}}]
  (let [cfg (doto (HikariConfig.)
              (.setJdbcUrl jdbc-url)
              (.setMaximumPoolSize max-pool-size))]
    (when connection-timeout-ms (.setConnectionTimeout cfg (long connection-timeout-ms)))
    (when username (.setUsername cfg username))
    (when password (.setPassword cfg password))
    (HikariDataSource. cfg)))

(defn close-pool!
  [^HikariDataSource ds]
  (.close ds))

(defmacro in-tx
  "Run `body` with `tx-sym` bound to a transactional connection.

  This is the kernel transaction. Nothing inside it may call external systems."
  [[tx-sym ds] & body]
  `(jdbc/with-transaction [~tx-sym ~ds]
     ~@body))

;; --- statement accounting (docs/design/performance.md) ------------------------------------
;;
;; When `*accounting*` is bound to an atom, every statement run through this namespace
;; records its class (:select :update :insert :delete ...), the first table it names, and
;; its wall time. Unbound, the kernel pays one `nil?` test per statement.

(def ^:dynamic *accounting* nil)

(defn- statement-shape
  "[class table] of a SQL string: the verb, and the table it acts on."
  [^String sql]
  (let [s (.trim sql)
        verb (keyword (.toLowerCase ^String (re-find #"^[A-Za-z]+" s)))
        table (second (or (re-find #"(?is)\b(?:FROM|INTO|UPDATE|TABLE)\s+([A-Za-z_][A-Za-z0-9_]*)" s)
                          [nil nil]))]
    [verb table]))

(defmacro ^:private accounted
  [sql & body]
  `(if-let [acc# *accounting*]
     (let [t0# (System/nanoTime) r# (do ~@body) [class# table#] (statement-shape (first ~sql))]
       (swap! acc# conj {:class class# :table table# :ms (/ (- (System/nanoTime) t0#) 1e6)
                         :sql (let [t# (.replaceAll (.trim ^String (first ~sql)) "\\s+" " ")] (subs t# 0 (min 70 (count t#))))})
       r#)
     (do ~@body)))

(defmacro with-accounting
  "Run `body` with statement accounting on; returns [result records]."
  [& body]
  `(binding [*accounting* (atom [])]
     (let [r# (do ~@body)] [r# @*accounting*])))

(defn summarize
  "Accounting records as {[class table] {:n count :ms total}}, with :total."
  [records]
  (assoc (reduce (fn [m {:keys [class table ms]}]
                   (-> m (update-in [[class table] :n] (fnil inc 0)) (update-in [[class table] :ms] (fnil + 0.0) ms)))
                 {} records)
         :total {:n (count records) :ms (reduce + 0.0 (map :ms records))}))

(defn execute!
  ([conn sql] (accounted sql (jdbc/execute! conn sql default-opts)))
  ([conn sql opts] (accounted sql (jdbc/execute! conn sql (merge default-opts opts)))))

(defn execute-one!
  ([conn sql] (accounted sql (jdbc/execute-one! conn sql default-opts)))
  ([conn sql opts] (accounted sql (jdbc/execute-one! conn sql (merge default-opts opts)))))
