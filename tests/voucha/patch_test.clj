(ns voucha.patch-test
  (:require [clojure.test :refer [deftest is testing]]
            [voucha.codec :as codec]
            [voucha.patch :as patch]))

(deftest apply-patch-operations
  (testing "assoc-in sets a value at a keyword path"
    (is (= {:a {:b 1}}
           (patch/apply-patch {} '[(assoc-in [:a :b] 1)]))))

  (testing "dissoc-in removes a nested key without pruning the parent"
    (is (= {:a {:c 2}}
           (patch/apply-patch {:a {:b 1 :c 2}} '[(dissoc-in [:a :b])]))))

  (testing "inc-in creates the counter when absent, so parents need no priming"
    (is (= {:completed 1} (patch/apply-patch {} '[(inc-in [:completed] 1)])))
    (is (= {:completed 4} (patch/apply-patch {:completed 3} '[(inc-in [:completed] 1)]))))

  (testing "conj-in appends to a vector, creating it when absent"
    (is (= {:errors ["e1"]} (patch/apply-patch {} '[(conj-in [:errors] "e1")])))
    (is (= {:errors ["e1" "e2"]}
           (patch/apply-patch {:errors ["e1"]} '[(conj-in [:errors] "e2")]))))

  (testing "operations apply in order"
    (is (= {:n 2} (patch/apply-patch {} '[(assoc-in [:n] 1) (inc-in [:n] 1)])))))

(deftest patch-vocabulary-is-closed
  (testing "unknown operations are rejected, not silently ignored"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid state patch"
                          (patch/apply-patch {} '[(delete-everything [:a])]))))

  (testing "a patch may not smuggle a function in as an argument"
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Invalid state patch"
                          (patch/validate! [(list 'inc-in [:n] inc)]))))

  (testing "paths must be non-empty vectors of keywords"
    (is (thrown? clojure.lang.ExceptionInfo (patch/validate! '[(assoc-in [] 1)])))
    (is (thrown? clojure.lang.ExceptionInfo (patch/validate! '[(assoc-in ["a"] 1)])))
    (is (thrown? clojure.lang.ExceptionInfo (patch/validate! '[(assoc-in :a 1)]))))

  (testing "a patch must be a vector of operations"
    (is (thrown? clojure.lang.ExceptionInfo (patch/validate! '((assoc-in [:a] 1)))))))

(deftest patch-round-trips-through-canonical-edn
  (let [p '[(assoc-in [:mainframe-request-id] "REQ-1")
            (inc-in [:completed-count] 1)
            (conj-in [:errors] {:code "E17" :at "T1"})]]
    (is (= p (patch/decode (patch/encode p))))))

(deftest canonical-edn-is-order-independent
  (testing "logically equal maps hash identically regardless of insertion order"
    (let [a (array-map :b 2 :a 1)
          b (array-map :a 1 :b 2)]
      (is (not= (pr-str a) (pr-str b)) "precondition: naive printing differs")
      (is (= (codec/canonical-edn a) (codec/canonical-edn b)))
      (is (= (codec/content-hash a) (codec/content-hash b)))))

  (testing "mixed key types are orderable"
    (is (string? (codec/canonical-edn {:a 1 "b" 2 3 :c})))))

(deftest canonical-edn-is-lossless
  ;; Regression: canonical encoding once flattened sets to vectors, so an
  ;; artifact read back from execution_artifact was not equal to the artifact
  ;; that was compiled -- silently breaking content addressing on reload.
  (testing "sets survive the round trip as sets"
    (let [x {:events #{"A" "B"} :effects {"e" {:kind :external}}}]
      (is (= x (codec/read-edn (codec/canonical-edn x))))))

  (testing "nested collections survive"
    (let [x {:a [1 2 {:b #{:x :y}}] :c {"k" ["v"]}}]
      (is (= x (codec/read-edn (codec/canonical-edn x))))))

  (testing "set element order does not affect the hash"
    (is (= (codec/content-hash #{"A" "B" "C"})
           (codec/content-hash #{"C" "A" "B"})))))
