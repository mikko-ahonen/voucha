(ns voucha.subprocess-test
  "A checker runs under a deadline: stopped when it runs out, with what it
  printed so far."
  (:require [clojure.test :refer [deftest is]]
            [voucha.model-tla]
            [voucha.subprocess :as subprocess]))

(deftest a-process-that-finishes-in-time-reports-its-exit-and-output
  (let [r (subprocess/run! "." ["sh" "-c" "echo hello; exit 3"] {:timeout-ms 5000})]
    (is (= 3 (:exit r)))
    (is (= "hello\n" (:out r)))
    (is (false? (:timed-out? r)))))

(deftest a-process-that-overruns-is-stopped-and-says-so
  (let [t0 (System/currentTimeMillis)
        r (subprocess/run! "." ["sh" "-c" "echo started; sleep 30; echo late"] {:timeout-ms 500})]
    (is (true? (:timed-out? r)))
    (is (nil? (:exit r)))
    (is (< (- (System/currentTimeMillis) t0) 10000) "stopped at the deadline, not at the process's end")))

(deftest without-a-deadline-it-waits
  (is (= 0 (:exit (subprocess/run! "." ["true"] {})))))

(deftest tlc-counts-come-from-the-summary-or-the-last-progress-line
  (is (= [127482 117205] (voucha.model-tla/tlc-counts "foo\n127482 states generated, 117205 distinct states found, 0 states left on queue.\n")))
  (is (= [2345678 1234567]
         (voucha.model-tla/tlc-counts (str "Progress(11) at 2026-11-02 12:40:00: 1,000,000 states generated (100,000 s/min), 800,000 distinct states found (300,000 ds/min), 5 states left on queue.\n"
                                           "Progress(12) at 2026-11-02 12:41:00: 2,345,678 states generated (120,000 s/min), 1,234,567 distinct states found (310,000 ds/min), 7 states left on queue.\n")))
      "the last progress line, thousands separators stripped")
  (is (nil? (voucha.model-tla/tlc-counts "nothing yet"))))
