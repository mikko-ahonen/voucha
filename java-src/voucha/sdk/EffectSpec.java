package voucha.sdk;

import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Declare an effect: what an external call or store write is called, how it is
 * made unique, what it reports, and what happens when its outcome is unknown.
 *
 * <pre>{@code
 * EffectSpec.external("payment.charge")
 *     .query("payment.status")          // never charge twice: ask instead
 *     .onCompletion("PaymentCharged")
 *     .onFailure("PaymentDeclined")
 * }</pre>
 *
 * <p>Produces the same declaration the Clojure definition map carries.
 */
public final class EffectSpec {

    private final String name;
    private final Map<String, Object> spec = new LinkedHashMap<>();

    private EffectSpec(String name, String kind) {
        this.name = name;
        spec.put("version", "v1");
        spec.put("kind", kind);
        spec.put("idempotency-key", List.of("tree-id", "node-id"));
        spec.put("correlation-key", "node-id");
        spec.put("uncertainty-policy", "retry-safe");
    }

    /** A call to another system, performed by an {@code EffectAdapter}. */
    public static EffectSpec external(String name) { return new EffectSpec(name, "external"); }

    /** A database write outside the kernel's tables, performed by the named {@code StoreAdapter}. */
    public static EffectSpec store(String name, String adapter) {
        EffectSpec s = new EffectSpec(name, "store");
        s.spec.put("adapter", adapter);
        return s;
    }

    public EffectSpec version(String version) { spec.put("version", version); return this; }

    /** Fields of the context ("tree-id", "node-id") and the request that make an intent unique. */
    public EffectSpec idempotencyKey(String... fields) { spec.put("idempotency-key", Arrays.asList(fields)); return this; }

    public EffectSpec correlationKey(String field) { spec.put("correlation-key", field); return this; }

    /** An unknown outcome is resent under the same idempotency key. */
    public EffectSpec retrySafe() { spec.put("uncertainty-policy", "retry-safe"); spec.remove("status-query"); return this; }

    /** An unknown outcome is resolved by asking the other side, never by resending. */
    public EffectSpec query(String statusQuery) {
        spec.put("uncertainty-policy", "query");
        spec.put("status-query", statusQuery);
        return this;
    }

    /** An unknown outcome waits for an operator. */
    public EffectSpec manual() { spec.put("uncertainty-policy", "manual"); spec.remove("status-query"); return this; }

    /**
     * Carried out by a participant that claims it through the SQL interface
     * ({@code voucha.claim_effects}), not by an adapter. The only kind is {@code "sql"}.
     */
    public EffectSpec participant(String kind) { spec.put("participant", kind); return this; }

    public EffectSpec onDispatch(String event)   { spec.put("dispatch-event", event); return this; }
    public EffectSpec onAck(String event)        { spec.put("ack-event", event); return this; }
    public EffectSpec onCompletion(String event) { spec.put("completion-event", event); return this; }
    public EffectSpec onFailure(String event)    { spec.put("failure-event", event); return this; }

    /** Deliver {@code event} if the effect is dispatched or acknowledged but not completed within {@code after}. */
    public EffectSpec completionTimeout(Duration after, String event) {
        spec.put("completion-timeout-ms", after.toMillis());
        spec.put("timeout-event", event);
        return this;
    }

    public String name() { return name; }
    public Map<String, Object> toMap() { return spec; }
}
