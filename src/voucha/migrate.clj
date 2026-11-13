(ns voucha.migrate
  "Schema migrations. The database owns durable storage; the platform owns
  execution semantics (design 43)."
  (:require [migratus.core :as migratus]))

(defn config
  ([datasource] (config datasource "migrations"))
  ([datasource migration-dir]
   {:store :database
    :migration-dir migration-dir
    :db {:datasource datasource}}))

(defn migrate!
  "Apply the platform's migrations, or, with `migration-dir`, an application's
  own (a resource directory of migratus files), which share the migration
  table and must therefore use distinct ids."
  ([datasource] (migratus/migrate (config datasource)))
  ([datasource migration-dir] (migratus/migrate (config datasource migration-dir))))

(defn rollback!
  ([datasource] (migratus/rollback (config datasource)))
  ([datasource migration-dir] (migratus/rollback (config datasource migration-dir))))
