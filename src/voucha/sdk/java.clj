(ns voucha.sdk.java
  "The Java SDK's Clojure side (design 36-42).

  Java is an authoring surface, not a second runtime. A `@Transition`-annotated
  method is read once, at compile time, into the same transition spec a Clojure
  definition produces, and its body is wrapped into the same handler signature.
  Once compiled, the artifact carries no trace of which language wrote it -- and
  the kernel, which only ever sees artifacts, cannot tell."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [voucha.sdk.java-plan :as java-plan])
  (:import (java.lang.reflect Method)
           (voucha.sdk Event NodeContext Transition ProfileBuilder ProcessProfile)))

(defn- handler-symbol
  "A stable name for a Java handler: Class/method, matching how Clojure handlers
  are named in the transition log."
  [^Class klass ^Method m]
  (symbol (str (.getSimpleName klass) "/" (.getName m))))

(defn- wrap-handler
  "Adapt a Java method to the kernel's handler signature.

  The kernel calls (handler ctx event) and expects plan data. The Java method
  receives typed wrappers and returns the same data via Plan.build()."
  [instance ^Method m]
  (fn [ctx ev]
    (.invoke m instance (object-array [(NodeContext. ctx) (Event. ev)]))))

(defn- ->transition-spec
  [^Class klass ^Method m ^Transition ann]
  (cond-> {:node-type (.nodeType ann)
           :from (.from ann)
           :on (.on ann)
           :to (.to ann)
           :handler (handler-symbol klass m)}
    (seq (.effects ann)) (assoc :effects (vec (.effects ann)))
    (seq (.await ann)) (assoc :await (vec (.await ann)))
    (seq (.emit ann)) (assoc :emit (vec (.emit ann)))
    (seq (.parentState ann)) (update :requires (fnil conj [])
                                     {:constraint :parent-state :allowed (vec (.parentState ann))})
    (seq (.parentStateForbidden ann)) (update :requires (fnil conj [])
                                              {:constraint :parent-state :forbidden (vec (.parentStateForbidden ann))})
    (seq (.countersCoverChildren ann)) (update :requires (fnil conj [])
                                               {:constraint :counters-cover-children
                                                :counters (mapv keyword (.countersCoverChildren ann))})
    (seq (.counter ann)) (update :requires (fnil conj [])
                                 {:constraint :counter :counter (keyword (.counter ann))
                                  :op (keyword (.counterOp ann)) :value (.counterValue ann)})
    (.auto ann) (assoc :auto true)
    (.code ann) (assoc :code true)
    (seq (.creates ann)) (assoc :creates (vec (.creates ann)))
    (seq (.commands ann)) (assoc :commands (vec (.commands ann)))))

(defn- annotated-methods
  [^Class klass]
  (keep (fn [^Method m]
          (when-let [ann (.getAnnotation m Transition)]
            [m ann]))
        (.getDeclaredMethods klass)))

(defn scan
  "Read a Java handler class into {:transitions [..] :handlers {sym fn}}.

  Methods are sorted by name so the emitted transition order -- and therefore the
  artifact id -- does not depend on JVM reflection order."
  [^Class klass]
  (let [instance (.newInstance (.getDeclaredConstructor klass (into-array Class []))
                               (object-array 0))
        pairs (sort-by (fn [[^Method m _]] (.getName m)) (annotated-methods klass))]
    {:transitions (mapv (fn [[m ann]] (->transition-spec klass m ann)) pairs)
     :handlers (into {} (map (fn [[^Method m _]]
                               [(handler-symbol klass m) (wrap-handler instance m)]))
                     pairs)}))

(defn scan-all
  [classes]
  (reduce (fn [acc k]
            (let [{:keys [transitions handlers]} (scan k)]
              (-> acc
                  (update :transitions into transitions)
                  (update :handlers merge handlers))))
          {:transitions [] :handlers {}}
          classes))

(defn- keywordize-keys
  "Java maps and lists in, Clojure maps and vectors with keyword keys out, all
  the way down."
  [x]
  (cond
    (instance? java.util.Map x) (into {} (map (fn [[k v]] [(if (string? k) (keyword k) k) (keywordize-keys v)])) x)
    (instance? java.util.List x) (mapv keywordize-keys x)
    :else x))

(defn- effect-spec->clj
  "An EffectSpec's map uses strings throughout; the definition wants keywords
  for keys, for the kind and policy, and for the key fields."
  [spec]
  (let [m (keywordize-keys spec)]
    (cond-> (-> m
                (update :kind keyword)
                (update :uncertainty-policy keyword)
                (update :idempotency-key #(mapv keyword %))
                (update :correlation-key keyword))
      (:completion-timeout-ms m) (update :completion-timeout-ms long)
      (:participant m) (update :participant keyword))))

(defn profile-definition
  "Run a Java ProcessProfile and return its definition fragment as Clojure data.

  Counter names become keywords, matching the Clojure profile API, so the
  compiler's `:missing-counter` check reads the same either way."
  [^ProcessProfile profile]
  (let [b (ProfileBuilder.)]
    (.define profile b)
    {:profile {:name (.name b) :version (.version b)}
     :node-types (into {}
                       (map (fn [[nt spec]]
                              [nt (-> (keywordize-keys (into {} spec))
                                      (update :counters
                                              #(when % (into {} (map (fn [[k v]] [(keyword k) v])) %)))
                                      ;; child type names and state names are strings, not keywords
                                      (update :hosts
                                              #(when % (into {} (map (fn [[k v]] [(name k) v])) %)))
                                      (update :ignores
                                              #(when % (into {} (map (fn [[k v]] [(name k) (vec v)])) %)))
                                      ;; the schema's types are EDN text (voucha.sdk.Schema)
                                      (update :data
                                              #(when % (into {} (map (fn [[k v]] [(keyword (name k)) (edn/read-string v)])) %)))
                                      (as-> m (into {} (remove (comp nil? val)) m)))]))
                       (.nodeTypes b))
     :payloads (into {} (map (fn [[ev fields]]
                               [ev (into {} (map (fn [[k v]] [(keyword k) (edn/read-string v)])) fields)]))
                     (.payloads b))
     ;; the properties and predicates, verbatim EDN: one form whichever language wrote it
     :invariants (into {} (map (fn [[n v]] [(keyword n) (edn/read-string v)])) (.invariants b))
     :predicates (into {} (map (fn [[n v]] [(keyword n) (edn/read-string v)])) (.predicates b))
     :events (set (.events b))
     :effects (into {} (map (fn [[n spec]] [n (effect-spec->clj (into {} spec))])) (.effects b))
     :routing (mapv (fn [r] (-> (keywordize-keys (into {} r))
                                (update :scope keyword)
                                (update :on-unsatisfied keyword)))
                    (.routes b))}))

(defn- with-extracted-plans
  "Transitions with the plans read from the handler sources: a method in the
  extractor's subset becomes `:plan` on its transition (marked `:plan-source
  \"java\"`), one annotated `code = true` stays code, and one outside the
  subset fails assembly, naming the construct."
  [transitions sources]
  (let [extracted (apply merge (map (fn [src] (java-plan/extract-file src)) sources))
        errors (keep (fn [[sym r]] (when (:error r) (:error r))) extracted)]
    (when (seq errors)
      (throw (ex-info (str "Java handlers outside the plan subset (write them in it, or mark them code = true):\n  "
                           (str/join "\n  " errors))
                      {:type ::outside-subset :errors (vec errors)})))
    (mapv (fn [t]
            (let [r (get extracted (:handler t))]
              (if (and (:plan r) (not (:code t)))
                (assoc t :plan (:plan r) :plan-source "java")
                t)))
          transitions)))

(defn definition
  "Assemble a full definition from a Java profile plus Java handler classes.
  With `:sources`, the handler methods' source files, every method in the plan
  extractor's subset becomes a plan on its transition."
  [{:keys [profile handler-classes sources events effects routing chain-views node-types]}]
  (let [{:keys [transitions handlers]} (scan-all handler-classes)
        transitions (if (seq sources) (with-extracted-plans transitions sources) transitions)
        base (if profile (profile-definition profile) {})]
    {:definition (-> base
                     (assoc :events (into (set (:events base)) events)
                            :effects (merge (:effects base) effects)
                            :transitions transitions
                            :routing (into (vec (:routing base)) routing)
                            :chain-views (or chain-views {}))
                     (update :node-types #(merge-with merge % node-types)))
     :handlers handlers}))
