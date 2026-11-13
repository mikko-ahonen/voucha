package voucha.sdk;

import clojure.lang.ILookup;
import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;

/**
 * The only view a handler has of the execution tree (design 22, 58).
 *
 * <p>There is deliberately no database, clock, or HTTP client reachable from
 * here. A handler that needs the transaction time asks {@link #transactionTime()};
 * one that needs an identifier asks {@link #nextId()}. Both are derived from
 * values fixed at commit time, so replaying the event reproduces them.
 */
public final class NodeContext {

    private static final Keyword NODE = Keyword.intern("node");
    private static final Keyword PARENT = Keyword.intern("parent");
    private static final Keyword NODE_ID = Keyword.intern("node-id");
    private static final Keyword NODE_TYPE = Keyword.intern("node-type");
    private static final Keyword LOCAL_STATE = Keyword.intern("local-state");
    private static final Keyword LOCAL_DATA = Keyword.intern("local-data");
    private static final Keyword COUNTERS = Keyword.intern("counters");
    private static final Keyword CHILD_COUNT = Keyword.intern("child-count");
    private static final Keyword FACTS = Keyword.intern("facts");

    private final IPersistentMap ctx;

    public NodeContext(IPersistentMap ctx) {
        this.ctx = ctx;
    }

    private static Object get(Object m, Keyword k) {
        return m instanceof ILookup ? ((ILookup) m).valAt(k) : null;
    }

    private Object node() {
        return ctx.valAt(NODE);
    }

    public String nodeId() {
        return (String) get(node(), NODE_ID);
    }

    public String nodeType() {
        return (String) get(node(), NODE_TYPE);
    }

    public String localState() {
        return (String) get(node(), LOCAL_STATE);
    }

    /** Runtime-owned node data. Read only: mutate it through the plan's patch ops. */
    public Object data(String key) {
        return get(get(node(), LOCAL_DATA), Keyword.intern(key));
    }

    /** An aggregate counter the kernel maintains for this node's children. */
    public long counter(String name) {
        Object counters = get(get(node(), LOCAL_DATA), COUNTERS);
        Object v = get(counters, Keyword.intern(name));
        return v == null ? 0L : ((Number) v).longValue();
    }

    public long childCount() {
        Object v = get(get(node(), LOCAL_DATA), CHILD_COUNT);
        return v == null ? 0L : ((Number) v).longValue();
    }

    /** The parent's local state, when the transition's constraints loaded it. */
    public String parentState() {
        Object parent = ctx.valAt(PARENT);
        return parent == null ? null : (String) get(parent, LOCAL_STATE);
    }

    /** The single clock reading for this kernel transaction. */
    public Object transactionTime() {
        return Facts.transactionTime(ctx.valAt(FACTS));
    }

    /** An identifier derived from the triggering event, so replay reproduces it. */
    public String nextId() {
        return Facts.nextId(ctx.valAt(FACTS));
    }

    public Plan plan() {
        return new Plan();
    }
}
