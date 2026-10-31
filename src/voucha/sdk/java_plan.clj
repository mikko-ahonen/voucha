(ns voucha.sdk.java-plan
  "Java handlers as plans: the extractor (docs/design/handler-ir.md, phase C).

  A handler method written in the subset below is read from its source with
  JavaParser and turned into the same plan IR a Clojure transition carries in
  `:plan` (`voucha.handler`). The plan then runs in the kernel, the Java method
  being its syntax, and the model checker reads it exactly.

  The subset is the plan API and what feeds it:

    * `ctx.plan()` and the builder calls `state`, `set`, `setIn`, `increment`,
      `emit`, `emitTo`, `call(EffectIntent.of(name[, request]))`, `store`,
      `await`, `child`, `timeoutAfter(Duration.of..., event)`, `build`;
    * reads: `ctx.nodeId()`, `ctx.data(k)`, `ctx.counter(n)`, `ctx.childCount()`,
      `event.payload(k)`, `event.string(k)`; `Values.field`, `Values.number`,
      `Values.count`, `Values.isEmpty`, `x.equals(y)`, `x.toString()`;
    * literals, locals, `+ - * < <= > >= == != && || !`, `c ? a : b`,
      `x == null ? d : x`;
    * `Map<String, Object> m = new HashMap<>(); m.put(k, v);` as a request or
      payload; `new String[]{...}` as a path;
    * `if` whose branches return, `for (Object x : coll)` that extends the plan
      or sums a term, `return`, and calls to private helpers of the class, which
      are inlined.

  A handler outside the subset fails extraction with the construct named; it
  may then be kept as code with `@Transition(code = true)`, which the model
  abstracts instead of reading."
  (:require [clojure.string :as str])
  (:import (com.github.javaparser StaticJavaParser)
           (com.github.javaparser.ast CompilationUnit)
           (com.github.javaparser.ast.body ClassOrInterfaceDeclaration MethodDeclaration Parameter)
           (com.github.javaparser.ast.expr AnnotationExpr ArrayCreationExpr AssignExpr BinaryExpr
                                          BooleanLiteralExpr CastExpr ConditionalExpr EnclosedExpr
                                          Expression IntegerLiteralExpr LongLiteralExpr MethodCallExpr
                                          NameExpr NormalAnnotationExpr NullLiteralExpr ObjectCreationExpr
                                          StringLiteralExpr UnaryExpr VariableDeclarationExpr)
           (com.github.javaparser.ast.stmt BlockStmt ExpressionStmt ForEachStmt IfStmt ReturnStmt Statement)))

(defn- fail [env what & [node]]
  (throw (ex-info (format "%s: %s%s" (:method env) what (if node (str ": " (str node)) ""))
                  {:type ::outside-subset :method (:method env)})))

;; --- values the walker carries ---------------------------------------------------

(defn- plan-value [ops] {::plan (vec ops)})
(defn- plan-value? [v] (and (map? v) (contains? v ::plan)))
(defn- map-value [pairs] {::map (vec pairs)})
(defn- map-value? [v] (and (map? v) (contains? v ::map)))
(defn- effect-value [name* request] {::effect name* ::request request})

(defn- as-ir
  "A walker value as an IR expression: a built map becomes a map literal."
  [env v]
  (cond
    (map-value? v) (into {} (map (fn [[k x]] [(keyword k) (as-ir env x)])) (::map v))
    (plan-value? v) (fail env "a plan used as a value")
    (and (map? v) (::effect v)) (fail env "an effect intent used as a value")
    :else v))

(defn- string-arg [env ^Expression e what]
  (if (instance? StringLiteralExpr e)
    (.asString ^StringLiteralExpr e)
    (fail env (str what " must be a string literal") e)))

(defn- duration-ms [env ^Expression e]
  (if (and (instance? MethodCallExpr e) (= "Duration" (some-> ^MethodCallExpr e .getScope (.orElse nil) str)))
    (let [m ^MethodCallExpr e
          n (Long/parseLong (str/replace (str (first (.getArguments m))) #"[Ll]$" ""))]
      (case (.getNameAsString m)
        "ofMillis" n
        "ofSeconds" (* 1000 n)
        "ofMinutes" (* 60000 n)
        "ofHours" (* 3600000 n)
        (fail env "a Duration must be ofMillis, ofSeconds, ofMinutes or ofHours" e)))
    (fail env "a timeout needs a Duration.of... literal" e)))

(declare eval-expr exec-block)

(defn- scope-name [^MethodCallExpr m]
  (some-> (.getScope m) (.orElse nil) (as-> s (when (instance? NameExpr s) (.getNameAsString ^NameExpr s)))))

(defn- args [^MethodCallExpr m] (vec (.getArguments m)))

(defn- name-choice
  "An event or effect name argument: a literal, or a conditional over two
  literals, which becomes a plan-level branch."
  [env ^Expression e what]
  (cond
    (instance? StringLiteralExpr e) {:name (.asString ^StringLiteralExpr e)}
    (instance? ConditionalExpr e)
    (let [c ^ConditionalExpr e]
      {:cond (eval-expr env (.getCondition c))
       :then (string-arg env (.getThenExpr c) what)
       :else (string-arg env (.getElseExpr c) what)})
    (instance? EnclosedExpr e) (name-choice env (.getInner ^EnclosedExpr e) what)
    :else (fail env (str what " must be a string literal or a choice between two") e)))

(defn- builder-op
  "The op a plan builder call adds, as a function of the name it was given,
  so that a conditional name becomes a conditional op."
  [env ^MethodCallExpr m]
  (let [a (args m)
        n (.getNameAsString m)
        payload (fn [i] (when (< i (count a)) (as-ir env (eval-expr env (nth a i)))))
        with-name (fn [what f]
                    (let [{:keys [name cond then else]} (name-choice env (first a) what)]
                      (if name
                        [(f name)]
                        [[:if cond [(f then)] [(f else)]]])))]
    (case n
      "state" []
      "set" [[:set [(keyword (string-arg env (first a) "a data key"))] (as-ir env (eval-expr env (second a)))]]
      "setIn" (let [arr (first a)]
                (if (instance? ArrayCreationExpr arr)
                  [[:set (mapv #(keyword (string-arg env % "a path key")) (.. ^ArrayCreationExpr arr getInitializer get getValues))
                    (as-ir env (eval-expr env (second a)))]]
                  (fail env "setIn needs a new String[]{...} path" m)))
      "increment" [[:inc [(keyword (string-arg env (first a) "a data key"))] (as-ir env (eval-expr env (second a)))]]
      "emit" (with-name "an event" (fn [e] (if-let [p (payload 1)] [:emit e p] [:emit e])))
      "emitTo" (let [target (eval-expr env (first a))
                     target (if (= [:node-id] target) :self (as-ir env target))
                     {:keys [name cond then else]} (name-choice env (second a) "an event")
                     op (fn [e] (if-let [p (payload 2)] [:emit-to target e p] [:emit-to target e]))]
                 (if name [(op name)] [[:if cond [(op then)] [(op else)]]]))
      ("call" "store") (let [v (eval-expr env (first a))
                             kind (keyword n)]
                         (if (and (map? v) (::effect v))
                           [(if-let [r (::request v)] [kind (::effect v) (as-ir env r)] [kind (::effect v)])]
                           (fail env (str n " needs EffectIntent.of(...)") m)))
      "await" [[:await (string-arg env (first a) "an event")]]
      "child" [[:child (as-ir env (eval-expr env (first a))) (string-arg env (second a) "a node type")]]
      "timeoutAfter" [[:timeout (duration-ms env (first a)) (string-arg env (second a) "an event")]]
      "build" []
      (fail env (str "plan builder call ." n " is outside the subset") m))))

(defn- bind-params
  "An environment for a helper's body: `NodeContext` and `Event` parameters are
  the context and the event, the others the argument values."
  [env ^MethodDeclaration helper arg-values]
  (reduce (fn [env [^Parameter p v]]
            (let [t (str (.getType p)) nm (.getNameAsString p)]
              (cond
                (= "NodeContext" t) (assoc env :ctx nm)
                (= "Event" t) (assoc env :event nm)
                :else (assoc-in env [:vars nm] v))))
          (assoc env :vars {} :plans {} :maps {})
          (map vector (.getParameters helper) arg-values)))

(defn- call-helper [env ^MethodCallExpr m]
  (let [n (.getNameAsString m)
        helper (get-in env [:helpers n])
        vals* (map (fn [^Expression a]
                     (if (and (instance? NameExpr a) (#{(:ctx env) (:event env)} (.getNameAsString ^NameExpr a)))
                       ::param
                       (eval-expr env a)))
                   (args m))
        env* (bind-params (assoc env :method (str (:method env) " > " n)) helper vals*)
        {:keys [ops returned?]} (exec-block env* (.. helper getBody get))]
    (when-not returned? (fail env (str "helper " n " does not return a plan")))
    (plan-value ops)))

(defn- eval-expr
  [env ^Expression e]
  (cond
    (instance? StringLiteralExpr e) (.asString ^StringLiteralExpr e)
    (instance? IntegerLiteralExpr e) (long (.asNumber ^IntegerLiteralExpr e))
    (instance? LongLiteralExpr e) (long (.asNumber ^LongLiteralExpr e))
    (instance? BooleanLiteralExpr e) (.getValue ^BooleanLiteralExpr e)
    (instance? NullLiteralExpr e) nil
    (instance? EnclosedExpr e) (eval-expr env (.getInner ^EnclosedExpr e))
    (instance? CastExpr e) (eval-expr env (.getExpression ^CastExpr e))

    (instance? NameExpr e)
    (let [n (.getNameAsString ^NameExpr e)]
      (cond
        (contains? (:vars env) n) (get-in env [:vars n])
        (contains? (:plans env) n) (plan-value (get-in env [:plans n]))
        (contains? (:maps env) n) (map-value (get-in env [:maps n]))
        (contains? (:loop-vars env) n) [:var (keyword n)]
        :else (fail env (str "unknown variable " n))))

    (instance? UnaryExpr e)
    (let [u ^UnaryExpr e x (eval-expr env (.getExpression u))]
      (case (.asString (.getOperator u))
        "!" [:not x]
        "-" (if (number? x) (- x) [:- 0 x])
        (fail env "unary operator" e)))

    (instance? BinaryExpr e)
    (let [b ^BinaryExpr e
          op (.asString (.getOperator b))
          l (.getLeft b) r (.getRight b)
          null? #(instance? NullLiteralExpr %)]
      (cond
        (and (= "==" op) (null? r)) [:nil? (eval-expr env l)]
        (and (= "==" op) (null? l)) [:nil? (eval-expr env r)]
        (and (= "!=" op) (null? r)) [:not [:nil? (eval-expr env l)]]
        (and (= "!=" op) (null? l)) [:not [:nil? (eval-expr env r)]]
        :else
        (let [a (eval-expr env l) c (eval-expr env r)]
          (case op
            "+" [:+ a c] "-" [:- a c] "*" [:* a c]
            "<" [:< a c] "<=" [:<= a c] ">" [:> a c] ">=" [:>= a c]
            "==" [:= a c] "!=" [:not [:= a c]]
            "&&" [:and a c] "||" [:or a c]
            (fail env (str "operator " op) e)))))

    (instance? ConditionalExpr e)
    (let [c ^ConditionalExpr e
          cnd (.getCondition c)
          then (.getThenExpr c) else (.getElseExpr c)
          same-name? (fn [^Expression x ^Expression y]
                       (and (instance? NameExpr x)
                            (or (and (instance? NameExpr y) (= (str x) (str y)))
                                (and (instance? MethodCallExpr y) (= "toString" (.getNameAsString ^MethodCallExpr y))
                                     (= (str x) (scope-name y))))))]
      ;; x == null ? d : x  is a default
      (if (and (instance? BinaryExpr cnd) (= "==" (.asString (.getOperator ^BinaryExpr cnd)))
               (instance? NullLiteralExpr (.getRight ^BinaryExpr cnd))
               (same-name? (.getLeft ^BinaryExpr cnd) else))
        [:or (eval-expr env (.getLeft ^BinaryExpr cnd)) (eval-expr env then)]
        [:if (eval-expr env cnd) (eval-expr env then) (eval-expr env else)]))

    (instance? ObjectCreationExpr e)
    (if (str/includes? (str (.getType ^ObjectCreationExpr e)) "HashMap")
      (map-value [])
      (fail env "object creation" e))

    (instance? MethodCallExpr e)
    (let [m ^MethodCallExpr e
          n (.getNameAsString m)
          scope (scope-name m)
          a (args m)
          key-arg #(keyword (string-arg env (first a) "a key"))]
      (cond
        (= scope (:ctx env))
        (case n
          "nodeId" [:node-id]
          "data" [:data (key-arg)]
          "counter" [:counter (key-arg)]
          "childCount" [:data :child-count]
          "plan" (plan-value [])
          (fail env (str "ctx." n " is outside the subset") e))

        (= scope (:event env))
        (case n
          ("payload" "string") [:payload (key-arg)]
          (fail env (str "event." n " is outside the subset") e))

        (= scope "Values")
        (case n
          "field" [:get (eval-expr env (first a)) (keyword (string-arg env (second a) "a field"))]
          "number" [:or (eval-expr env (first a)) 0]
          "count" [:count (eval-expr env (first a))]
          "isEmpty" [:= [:count (eval-expr env (first a))] 0]
          "string" (eval-expr env (first a))
          (fail env (str "Values." n " is outside the subset") e))

        (= scope "EffectIntent")
        (effect-value (string-arg env (first a) "an effect name")
                      (when (second a) (eval-expr env (second a))))

        (= n "toString") (eval-expr env (.. m getScope get))
        (= n "equals") [:= (eval-expr env (.. m getScope get)) (eval-expr env (first a))]

        ;; a plan builder call on a plan value
        (and (some? (some-> (.getScope m) (.orElse nil)))
             (plan-value? (eval-expr env (.. m getScope get))))
        (plan-value (into (::plan (eval-expr env (.. m getScope get))) (builder-op env m)))

        (and (nil? scope) (nil? (some-> (.getScope m) (.orElse nil))) (get-in env [:helpers n]))
        (call-helper env m)

        :else (fail env (str "call " (str m) " is outside the subset"))))

    :else (fail env (str "expression " (.getClass e)) e)))

;; --- statements ------------------------------------------------------------------

(defn- exec-stmts
  "Run statements. Returns {:ops [...] :env env :returned? bool}."
  [env stmts]
  (loop [env env [^Statement s & more] stmts ops []]
    (cond
      (nil? s) {:ops ops :env env :returned? false}

      (instance? BlockStmt s)
      (let [r (exec-stmts env (.getStatements ^BlockStmt s))]
        (if (:returned? r)
          (update r :ops #(into ops %))
          (recur (:env r) more (into ops (:ops r)))))

      (instance? ReturnStmt s)
      (let [v (eval-expr env (.. ^ReturnStmt s getExpression get))]
        (if (plan-value? v)
          {:ops (into ops (::plan v)) :env env :returned? true}
          (fail env "a handler must return a plan" s)))

      (instance? IfStmt s)
      (let [i ^IfStmt s
            c (eval-expr env (.getCondition i))
            then (exec-stmts env [(.getThenStmt i)])
            else (some-> (.getElseStmt i) (.orElse nil))]
        (cond
          (and (:returned? then) else)
          (let [r (exec-stmts env [else])]
            (if (:returned? r)
              {:ops (conj ops [:if c (:ops then) (:ops r)]) :env env :returned? true}
              (fail env "an if whose branches do not both return" s)))
          (:returned? then)
          (let [r (exec-stmts env more)]
            (if (:returned? r)
              {:ops (conj ops [:if c (:ops then) (:ops r)]) :env env :returned? true}
              (fail env "an if that returns must be followed by a return" s)))
          :else (fail env "an if whose branches do not return" s)))

      (instance? ForEachStmt s)
      (let [f ^ForEachStmt s
            x (.. f getVariable (getVariable 0) getNameAsString)
            coll (as-ir env (eval-expr env (.getIterable f)))
            body (.getBody f)
            body-stmts (if (instance? BlockStmt body) (vec (.getStatements ^BlockStmt body)) [body])
            env-in (-> env (update :loop-vars (fnil conj #{}) x))
            accumulate (fn [^Statement st]
                         ;; total += e  /  total = total + e
                         (when (instance? ExpressionStmt st)
                           (let [ex (.getExpression ^ExpressionStmt st)]
                             (when (instance? AssignExpr ex)
                               (let [as ^AssignExpr ex
                                     target (str (.getTarget as))
                                     op (.asString (.getOperator as))]
                                 (when (contains? (:vars env) target)
                                   (cond
                                     (= "+=" op) [target (eval-expr env-in (.getValue as))]
                                     (and (= "=" op) (instance? BinaryExpr (.getValue as))
                                          (= "+" (.asString (.getOperator ^BinaryExpr (.getValue as))))
                                          (= target (str (.getLeft ^BinaryExpr (.getValue as)))))
                                     [target (eval-expr env-in (.getRight ^BinaryExpr (.getValue as)))]
                                     :else nil)))))))]
        (if-let [[target term] (and (= 1 (count body-stmts)) (accumulate (first body-stmts)))]
          (let [base (get-in env [:vars target])
                sum [:sum (keyword x) coll term]]
            (recur (assoc-in env [:vars target] (if (and (number? base) (zero? base)) sum [:+ base sum]))
                   more ops))
          ;; a loop that extends plans
          (let [before (:plans env)
                r (exec-stmts env-in body-stmts)]
            (when (:returned? r) (fail env "a loop body must not return" s))
            (let [after (:plans (:env r))
                  grown (for [[p ops*] after :let [b (get before p)] :when (and b (> (count ops*) (count b)))]
                          [p (subvec ops* (count b))])
                  new-ops (into ops (map (fn [[_ delta]] (into [:each (keyword x) coll] delta)) grown))
                  env* (reduce (fn [env [p delta]] (assoc-in env [:plans p] (into (get before p) [(into [:each (keyword x) coll] delta)])))
                               env grown)]
              (when (empty? grown) (fail env "a loop that neither sums nor extends a plan" s))
              ;; the :each ops live on the plan var, not loose: ops stay as they were
              (recur env* more ops)))))

      (instance? ExpressionStmt s)
      (let [ex (.getExpression ^ExpressionStmt s)]
        (cond
          (instance? VariableDeclarationExpr ex)
          (recur (reduce (fn [env d]
                           (let [n (.getNameAsString d)
                                 init (some-> (.getInitializer d) (.orElse nil))]
                             (if (nil? init)
                               (fail env (str "variable " n " declared without a value") s)
                               (let [v (eval-expr env init)]
                                 (cond
                                   (plan-value? v) (assoc-in env [:plans n] (::plan v))
                                   (map-value? v) (assoc-in env [:maps n] (::map v))
                                   :else (assoc-in env [:vars n] v))))))
                         env (.getVariables ^VariableDeclarationExpr ex))
                 more ops)

          (instance? AssignExpr ex)
          (let [as ^AssignExpr ex
                target (str (.getTarget as))]
            (when-not (= "=" (.asString (.getOperator as))) (fail env "compound assignment outside a sum loop" s))
            (let [v (eval-expr env (.getValue as))]
              (cond
                (plan-value? v) (recur (assoc-in env [:plans target] (::plan v)) more ops)
                (map-value? v) (recur (assoc-in env [:maps target] (::map v)) more ops)
                :else (recur (assoc-in env [:vars target] v) more ops))))

          (and (instance? MethodCallExpr ex) (= "put" (.getNameAsString ^MethodCallExpr ex))
               (contains? (:maps env) (scope-name ex)))
          (let [m ^MethodCallExpr ex [k v] (args m)]
            (recur (update-in env [:maps (scope-name m)] conj [(string-arg env k "a request key") (eval-expr env v)])
                   more ops))

          :else (fail env "statement" s)))

      :else (fail env (str "statement " (.getClass s)) s))))

(defn- exec-block [env ^BlockStmt body]
  (exec-stmts env (.getStatements body)))

;; --- the class ---------------------------------------------------------------------

(defn- annotation [^MethodDeclaration m]
  (first (filter #(= "Transition" (.getNameAsString ^AnnotationExpr %)) (.getAnnotations m))))

(defn- annotation-value [^AnnotationExpr a k]
  (when (instance? NormalAnnotationExpr a)
    (some (fn [p] (when (= k (.getNameAsString p)) (str (.getValue p)))) (.getPairs ^NormalAnnotationExpr a))))

(defn- method-plan [helpers class-name ^MethodDeclaration m]
  (let [env (bind-params {:helpers helpers :method (str class-name "/" (.getNameAsString m))} m
                         (repeat (count (.getParameters m)) ::param))
        {:keys [ops returned?]} (exec-block env (.. m getBody get))]
    (when-not returned? (fail env "no return"))
    ops))

(defn extract
  "Plans for the `@Transition` methods of the class in `source`, by handler
  symbol: `{'Class/method {:plan [...]}}`, or `{:error \"...\"}` for a method
  outside the subset, and `{:code true}` for one annotated `code = true`."
  [^String source]
  (let [cu ^CompilationUnit (StaticJavaParser/parse source)
        klass ^ClassOrInterfaceDeclaration (.. cu (findFirst ClassOrInterfaceDeclaration) get)
        class-name (.getNameAsString klass)
        methods (vec (.getMethods klass))
        helpers (into {} (map (fn [^MethodDeclaration m] [(.getNameAsString m) m])) methods)]
    (into {}
          (for [^MethodDeclaration m methods
                :let [a (annotation m)]
                :when a
                :let [sym (symbol (str class-name "/" (.getNameAsString m)))]]
            [sym (if (= "true" (annotation-value a "code"))
                   {:code true}
                   (try {:plan (method-plan helpers class-name m)}
                        (catch clojure.lang.ExceptionInfo e
                          (if (= ::outside-subset (:type (ex-data e)))
                            {:error (ex-message e)}
                            (throw e)))))]))))

(defn extract-file [path] (extract (slurp path)))
