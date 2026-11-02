(ns voucha.patch-fuzz-test
  "T1: the patch vocabulary under random programs. A patch must survive the
  encode/decode round trip unchanged in effect, and anything outside the closed
  vocabulary must be refused -- a function or an unknown op can never reach the
  checkpoint log."
  (:require [clojure.test :refer [deftest is testing]]
            [voucha.patch :as patch])
  (:import (java.util Random)))

(defn- pick [^Random r coll] (nth (vec coll) (.nextInt r (count coll))))
(def ^:private keys* [:a :b :c :counters :child-count :nested :list])

(defn- gen-value [^Random r depth]
  (case (.nextInt r (if (pos? depth) 6 4))
    0 (.nextInt r 1000)
    1 (str "s" (.nextInt r 100))
    2 (.nextBoolean r)
    3 nil
    4 (vec (repeatedly (.nextInt r 3) #(gen-value r (dec depth))))
    5 (into {} (repeatedly (.nextInt r 3) (fn [] [(pick r keys*) (gen-value r (dec depth))])))))

(defn- gen-path [^Random r] (vec (repeatedly (inc (.nextInt r 3)) #(pick r keys*))))

(defn- gen-op [^Random r]
  (case (.nextInt r 4)
    0 (list 'assoc-in (gen-path r) (gen-value r 2))
    1 (list 'dissoc-in (gen-path r))
    2 (list 'inc-in (gen-path r) (- (.nextInt r 21) 10))
    3 (list 'conj-in (gen-path r) (gen-value r 1))))

(defn- gen-program [^Random r] (vec (repeatedly (inc (.nextInt r 6)) #(gen-op r))))

(defn- apply-or-error [data ops]
  (try [:ok (patch/apply-patch data ops)]
       (catch Exception e [:error (class e)])))

(deftest random-patches-survive-the-encode-decode-round-trip
  (doseq [seed (range 1 201)]
    (let [r (Random. (long seed))
          ops (gen-program r)
          data (gen-value r 2)
          data (if (map? data) data {})
          decoded (patch/decode (patch/encode ops))]
      (is (patch/valid-patch? ops) (str "seed " seed))
      (is (= ops decoded) (str "seed " seed ": encode/decode changed the program"))
      (is (= (apply-or-error data ops) (apply-or-error data decoded))
          (str "seed " seed ": applying the decoded program behaves differently")))))

(deftest anything-outside-the-vocabulary-is-refused
  (doseq [bad [[(list 'update-in [:a] inc)]
               [(list 'eval [:a] 1)]
               [(list 'assoc-in [:a] 1) (list 'swap! [:b] 2)]
               [(fn [d] d)]
               ['(assoc-in)]]]
    (is (thrown? clojure.lang.ExceptionInfo (patch/validate! bad)) (pr-str bad))))
