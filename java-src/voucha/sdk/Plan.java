package voucha.sdk;

import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;
import clojure.lang.PersistentHashMap;
import clojure.lang.PersistentList;
import clojure.lang.PersistentVector;
import clojure.lang.RT;
import clojure.lang.Symbol;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * The Java face of the developer command model (design 42).
 *
 * <p>A plan decides that things should happen. It never makes them happen:
 * {@code call} and {@code store} create durable intents that the kernel persists
 * and dispatchers execute strictly after the transaction commits.
 *
 * <pre>{@code
 * return ctx.plan()
 *     .state("SUBMISSION_PENDING")
 *     .call(Mainframe.submitBatch(request))
 *     .await(MainframeBatchAcknowledged.class)
 *     .timeoutAfter(Duration.ofMinutes(15))
 *     .build();
 * }</pre>
 *
 * <p>{@code build()} returns the same Clojure data the Clojure SDK produces, so
 * the kernel cannot tell which language wrote the handler.
 */
public final class Plan {

    private static final Keyword STATE = Keyword.intern("state");
    private static final Keyword PATCH = Keyword.intern("patch");
    private static final Keyword EMIT = Keyword.intern("emit");
    private static final Keyword CALL = Keyword.intern("call");
    private static final Keyword STORE = Keyword.intern("store");
    private static final Keyword AWAIT = Keyword.intern("await");
    private static final Keyword CHILD = Keyword.intern("child");
    private static final Keyword TIMEOUT = Keyword.intern("timeout");
    private static final Keyword EVENT_TYPE = Keyword.intern("event-type");
    private static final Keyword PAYLOAD = Keyword.intern("payload");
    private static final Keyword TARGET_NODE_ID = Keyword.intern("target-node-id");
    private static final Keyword EFFECT = Keyword.intern("effect");
    private static final Keyword REQUEST = Keyword.intern("request");
    private static final Keyword NODE_ID = Keyword.intern("node-id");
    private static final Keyword NODE_TYPE = Keyword.intern("node-type");
    private static final Keyword AFTER_MS = Keyword.intern("after-ms");
    private static final Keyword EVENT = Keyword.intern("event");

    private static final Symbol ASSOC_IN = Symbol.intern("assoc-in");
    private static final Symbol DISSOC_IN = Symbol.intern("dissoc-in");
    private static final Symbol INC_IN = Symbol.intern("inc-in");
    private static final Symbol CONJ_IN = Symbol.intern("conj-in");

    private String state;
    private final List<Object> patch = new ArrayList<>();
    private final List<Object> emit = new ArrayList<>();
    private final List<Object> call = new ArrayList<>();
    private final List<Object> store = new ArrayList<>();
    private final List<Object> await = new ArrayList<>();
    private final List<Object> child = new ArrayList<>();
    private Object timeout;

    Plan() {}

    /** The node's target local state. Validated against the pinned artifact. */
    public Plan state(String targetState) {
        this.state = targetState;
        return this;
    }

    /** Set a value in runtime-owned node data. */
    public Plan set(String key, Object value) {
        patch.add(op(ASSOC_IN, path(key), value));
        return this;
    }

    /** Set a value at a nested path in runtime-owned node data. */
    public Plan setIn(String[] keys, Object value) {
        patch.add(op(ASSOC_IN, path(keys), value));
        return this;
    }

    public Plan remove(String key) {
        patch.add(PersistentList.create(List.of(DISSOC_IN, path(key))));
        return this;
    }

    public Plan increment(String key, long amount) {
        patch.add(op(INC_IN, path(key), amount));
        return this;
    }

    public Plan append(String key, Object value) {
        patch.add(op(CONJ_IN, path(key), value));
        return this;
    }

    /** Create an internal event. */
    public Plan emit(String eventType) {
        return emit(eventType, PersistentHashMap.EMPTY);
    }

    public Plan emit(String eventType, IPersistentMap payload) {
        emit.add(PersistentHashMap.create(EVENT_TYPE, eventType, PAYLOAD, payload));
        return this;
    }

    /**
     * Create an internal event addressed at a specific node: a child this plan
     * creates, or this node itself. An untargeted {@link #emit} bubbles upward to
     * the parent instead.
     */
    public Plan emitTo(String nodeId, String eventType) {
        return emitTo(nodeId, eventType, PersistentHashMap.EMPTY);
    }

    public Plan emitTo(String nodeId, String eventType, IPersistentMap payload) {
        emit.add(PersistentHashMap.create(EVENT_TYPE, eventType, PAYLOAD, payload, TARGET_NODE_ID, nodeId));
        return this;
    }

    /** Create an external effect intent. Performs no I/O. */
    public Plan call(EffectIntent intent) {
        call.add(PersistentHashMap.create(EFFECT, intent.name(), REQUEST, intent.request()));
        return this;
    }

    /** Create a store-effect intent. Performs no database write. */
    public Plan store(EffectIntent intent) {
        store.add(PersistentHashMap.create(EFFECT, intent.name(), REQUEST, intent.request()));
        return this;
    }

    /** Declare the event this node expects next, named by its class. */
    public Plan await(Class<?> eventClass) {
        return await(eventClass.getSimpleName());
    }

    public Plan await(String eventType) {
        await.add(eventType);
        return this;
    }

    /** Create a child node. The kernel checks the artifact's containment rules. */
    public Plan child(String nodeId, String nodeType) {
        child.add(PersistentHashMap.create(NODE_ID, nodeId, NODE_TYPE, nodeType));
        return this;
    }

    public Plan timeoutAfter(Duration duration, String eventType) {
        this.timeout = PersistentHashMap.create(AFTER_MS, duration.toMillis(), EVENT, eventType);
        return this;
    }

    public Plan timeoutAfter(Duration duration, Class<?> eventClass) {
        return timeoutAfter(duration, eventClass.getSimpleName());
    }

    /** Render the plan as the Clojure data the kernel consumes. */
    public IPersistentMap build() {
        IPersistentMap m = PersistentHashMap.EMPTY;
        m = m.assoc(STATE, state);
        m = m.assoc(PATCH, PersistentVector.create(patch));
        m = m.assoc(EMIT, PersistentVector.create(emit));
        m = m.assoc(CALL, PersistentVector.create(call));
        m = m.assoc(STORE, PersistentVector.create(store));
        m = m.assoc(AWAIT, PersistentVector.create(await));
        m = m.assoc(CHILD, PersistentVector.create(child));
        if (timeout != null) {
            m = m.assoc(TIMEOUT, timeout);
        }
        return m;
    }

    private static Object op(Symbol head, Object path, Object value) {
        return PersistentList.create(java.util.Arrays.asList(head, path, value));
    }

    private static PersistentVector path(String key) {
        return PersistentVector.create(Keyword.intern(key));
    }

    private static PersistentVector path(String[] keys) {
        List<Object> ks = new ArrayList<>(keys.length);
        for (String k : keys) {
            ks.add(Keyword.intern(k));
        }
        return PersistentVector.create(ks);
    }

    static {
        // Ensure Clojure's runtime is initialised before any keyword interning.
        RT.init();
    }
}
