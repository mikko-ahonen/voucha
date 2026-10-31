package voucha.sdk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Declare a process profile from Java (design 37).
 *
 * <p>Produces the same definition data the Clojure profile API produces. The
 * compiler snapshots it into the artifact (decision D12), so nothing about the
 * authoring language survives into the runtime.
 */
public final class ProfileBuilder {

    private String name;
    private String version;
    private final Map<String, Map<String, Object>> nodeTypes = new LinkedHashMap<>();
    private final List<String> events = new ArrayList<>();
    private final Map<String, Map<String, Object>> effects = new LinkedHashMap<>();
    private final List<Map<String, Object>> routes = new ArrayList<>();
    private final Map<String, Map<String, String>> payloads = new LinkedHashMap<>();
    private final Map<String, String> invariants = new LinkedHashMap<>();
    private final Map<String, String> predicates = new LinkedHashMap<>();
    private String current;

    public ProfileBuilder profile(String name, String version) {
        this.name = name;
        this.version = version;
        return this;
    }

    public ProfileBuilder nodeType(String type) {
        current = type;
        nodeTypes.computeIfAbsent(type, k -> new LinkedHashMap<>())
                 .putIfAbsent("may-contain", new ArrayList<String>());
        return this;
    }

    @SuppressWarnings("unchecked")
    public ProfileBuilder mayContain(String childType) {
        ((List<String>) nodeTypes.get(current).get("may-contain")).add(childType);
        return this;
    }

    @SuppressWarnings("unchecked")
    private List<Object> stateList() {
        return (List<Object>) nodeTypes.get(current).computeIfAbsent("states", k -> new ArrayList<Object>());
    }

    /** Declare states, in order. Call again, or {@link #composite}, to add more. */
    public ProfileBuilder states(String... states) {
        stateList().addAll(Arrays.asList(states));
        return this;
    }

    /**
     * Declare a composite state: a state with sub-states. Entering it enters
     * {@code initial}; a transition declared from it applies in every sub-state
     * unless a sub-state declares its own. The runtime only ever sees the
     * sub-states.
     */
    public ProfileBuilder composite(String name, String initial, String... subStates) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("name", name);
        c.put("initial", initial);
        c.put("states", new ArrayList<Object>(Arrays.asList(subStates)));
        stateList().add(c);
        return this;
    }

    public ProfileBuilder initialState(String state) {
        nodeTypes.get(current).put("initial-state", state);
        return this;
    }

    public ProfileBuilder terminalStates(String... states) {
        nodeTypes.get(current).put("terminal-states", Arrays.asList(states));
        return this;
    }

    public ProfileBuilder failureState(String state) {
        nodeTypes.get(current).put("failure-state", state);
        return this;
    }

    public ProfileBuilder mayEmitEffects(String... effects) {
        nodeTypes.get(current).put("may-emit-effects", Arrays.asList(effects));
        return this;
    }

    /** Declare the state in which this type's children of {@code childType} live; diagrams draw them there. */
    @SuppressWarnings("unchecked")
    public ProfileBuilder hosts(String childType, String state) {
        ((Map<String, Object>) nodeTypes.get(current).computeIfAbsent("hosts", k -> new LinkedHashMap<String, Object>()))
            .put(childType, state);
        return this;
    }

    /** Declare events a state ignores: dropped as ignored, not rejected, when no transition handles them. Additive per state. */
    @SuppressWarnings("unchecked")
    public ProfileBuilder ignores(String state, String... events) {
        Map<String, Object> ignores =
            (Map<String, Object>) nodeTypes.get(current).computeIfAbsent("ignores", k -> new LinkedHashMap<String, Object>());
        List<String> list = (List<String>) ignores.computeIfAbsent(state, k -> new ArrayList<String>());
        for (String e : events) {
            if (!list.contains(e)) list.add(e);
        }
        return this;
    }

    /** Declare an aggregate counter over children entering the given states. */
    @SuppressWarnings("unchecked")
    public ProfileBuilder counter(String counterName, String childType, String... whenChildEnters) {
        Map<String, Object> counters =
            (Map<String, Object>) nodeTypes.get(current)
                .computeIfAbsent("counters", k -> new LinkedHashMap<String, Object>());
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("child-type", childType);
        spec.put("when-child-enters", Arrays.asList(whenChildEnters));
        counters.put(counterName, spec);
        return this;
    }

    /**
     * Declare a field of this node type's data and its type (see {@link Schema}).
     * With schemas declared, the compiler checks the plans against them and the
     * typed specification behind {@code voucha prove --inductive} can be generated.
     */
    @SuppressWarnings("unchecked")
    public ProfileBuilder data(String field, String type) {
        ((Map<String, Object>) nodeTypes.get(current).computeIfAbsent("data", k -> new LinkedHashMap<String, Object>()))
            .put(field, type);
        return this;
    }

    /** Declare the fields of an event's payload and their types (see {@link Schema}). */
    public ProfileBuilder payload(String event, String... fieldsAndTypes) {
        if (fieldsAndTypes.length % 2 != 0) {
            throw new IllegalArgumentException("payload takes field name and type pairs");
        }
        Map<String, String> fields = payloads.computeIfAbsent(event, k -> new LinkedHashMap<>());
        for (int i = 0; i < fieldsAndTypes.length; i += 2) {
            fields.put(fieldsAndTypes[i], fieldsAndTypes[i + 1]);
        }
        return this;
    }

    /**
     * Declare a business invariant: a property of every tree, as the EDN text of
     * the property language (docs/design/invariants.md), the same form the
     * Clojure profile API writes. The compiler validates it at assembly.
     */
    public ProfileBuilder invariant(String name, String property) {
        invariants.put(name, property);
        return this;
    }

    /** Declare a named predicate the invariants may use: {@code [:fn :x body]} as EDN text. */
    public ProfileBuilder predicate(String name, String fn) {
        predicates.put(name, fn);
        return this;
    }

    /** Declare event types. Every event a transition consumes, emits or awaits must be declared. */
    public ProfileBuilder events(String... eventTypes) {
        events.addAll(Arrays.asList(eventTypes));
        return this;
    }

    /** Declare an effect. See {@link EffectSpec}. */
    public ProfileBuilder effect(EffectSpec spec) {
        effects.put(spec.name(), spec.toMap());
        return this;
    }

    /**
     * Route an event a child emits without a target up to its parent. A parent
     * whose rule is not yet met ignores the event rather than rejecting it.
     */
    public ProfileBuilder routeUpward(String event, String fromType, String toType) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("event", event);
        r.put("from-node-type", fromType);
        r.put("to-node-type", toType);
        r.put("scope", "upward");
        r.put("on-unsatisfied", "ignore");
        routes.add(r);
        return this;
    }

    public String name() { return name; }
    public String version() { return version; }
    public Map<String, Map<String, Object>> nodeTypes() { return nodeTypes; }
    public List<String> events() { return events; }
    public Map<String, Map<String, Object>> effects() { return effects; }
    public List<Map<String, Object>> routes() { return routes; }
    public Map<String, Map<String, String>> payloads() { return payloads; }
    public Map<String, String> invariants() { return invariants; }
    public Map<String, String> predicates() { return predicates; }
}
