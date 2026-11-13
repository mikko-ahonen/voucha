(ns voucha.subprocess
  "Run a tool as a subprocess under a deadline: its combined output, its exit
  code, and whether it was stopped at the deadline. A launcher script's own
  children, a checker's JVM, are stopped with it."
  (:import (java.util.concurrent TimeUnit)))

(defn- stop! [^Process proc]
  (let [handles (.toArray (.descendants (.toHandle proc)))]
    (doseq [h handles] (clojure.lang.Reflector/invokeInstanceMethod h "destroyForcibly" (object-array 0))))
  (.destroyForcibly proc)
  (.waitFor proc (long 5) TimeUnit/SECONDS))

(defn run!
  "Run `cmd` (a vector) in `dir`, with `:timeout-ms` when given. Returns
  {:exit :out :timed-out?}; `:exit` is nil when the process was stopped."
  [dir cmd {:keys [timeout-ms]}]
  (let [pb (doto (ProcessBuilder. ^java.util.List (vec cmd))
             (.directory (java.io.File. ^String dir))
             (.redirectErrorStream true))
        proc (.start pb)
        out (future (slurp (.getInputStream proc)))
        done? (if timeout-ms
                (.waitFor proc (long timeout-ms) TimeUnit/MILLISECONDS)
                (do (.waitFor proc) true))]
    (when-not done? (stop! proc))
    {:exit (when done? (.exitValue proc))
     :out (deref out 5000 "")
     :timed-out? (not done?)}))
