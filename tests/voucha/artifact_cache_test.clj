(ns voucha.artifact-cache-test
  "Decoded artifacts are cached by id (T6: the kernel re-parsed the profile's
  EDN on every event). Safe because an artifact is content-addressed: the same
  id always means the same specs, and a different definition always gets a
  different id -- so the cache can never serve stale rules."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [voucha.compiler :as compiler]
            [voucha.db :as db]
            [voucha.profiles.file-batch-transaction :as fbt]
            [voucha.repo.artifact :as artifact]
            [voucha.test-db :as tdb :refer [*ds*]]))

(use-fixtures :once tdb/with-db)
(use-fixtures :each tdb/clean-db)

(deftest a-fetched-artifact-is-decoded-once-and-equal-thereafter
  (let [a (compiler/compile-definition fbt/definition)]
    (db/in-tx [tx *ds*] (artifact/insert! tx a))
    (let [first* (db/in-tx [tx *ds*] (artifact/fetch tx (:artifact-id a)))
          again (db/in-tx [tx *ds*] (artifact/fetch tx (:artifact-id a)))]
      (is (= (:specs a) (:specs first*)) "what was stored is what comes back")
      (is (identical? (:specs first*) (:specs again)) "the second fetch is served from the cache")
      (is (= (:artifact-id a) (:artifact-id again))))))

(deftest a-different-definition-is-a-different-artifact-never-a-cache-hit
  (let [a (compiler/compile-definition fbt/definition)
        b (compiler/compile-definition (assoc-in fbt/definition [:profile :version] "v-other"))]
    (db/in-tx [tx *ds*] (artifact/insert! tx a) (artifact/insert! tx b))
    (is (not= (:artifact-id a) (:artifact-id b)))
    (is (= "v-other" (get-in (db/in-tx [tx *ds*] (artifact/fetch tx (:artifact-id b))) [:specs :manifest :profile :version])))
    (is (= (get-in fbt/definition [:profile :version])
           (get-in (db/in-tx [tx *ds*] (artifact/fetch tx (:artifact-id a))) [:specs :manifest :profile :version])))))
