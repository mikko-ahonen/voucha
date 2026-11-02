(ns build
  "Compile the Java SDK sources. Run `clojure -T:build javac` before tests."
  (:require [clojure.tools.build.api :as b]))

(def class-dir "target/classes")

(defn javac
  [_]
  (b/javac {:src-dirs ["java-src"]
            :class-dir class-dir
            :basis (b/create-basis {:project "deps.edn"})
            :javac-opts ["-Xlint:-options"]})
  (println "compiled java-src ->" class-dir))

(defn clean [_] (b/delete {:path "target"}))
