package voucha.sdk;

import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;
import clojure.lang.PersistentHashMap;

import java.util.Map;

/**
 * A decision that an external call or database write should happen.
 *
 * <p>Constructing one performs no I/O. {@code Mainframe.submitBatch(request)}
 * returns an intent; the kernel turns it into a durable effect_instance row, and
 * a dispatcher executes it after the transaction commits (design 26, 38).
 */
public final class EffectIntent {

    private final String name;
    private final IPersistentMap request;

    private EffectIntent(String name, IPersistentMap request) {
        this.name = name;
        this.request = request;
    }

    /** @param name the effect name as declared in the artifact, e.g. "mainframe.submitBatch" */
    public static EffectIntent of(String name, Map<String, ?> request) {
        IPersistentMap m = PersistentHashMap.EMPTY;
        for (Map.Entry<String, ?> e : request.entrySet()) {
            m = m.assoc(Keyword.intern(e.getKey()), e.getValue());
        }
        return new EffectIntent(name, m);
    }

    public static EffectIntent of(String name) {
        return new EffectIntent(name, PersistentHashMap.EMPTY);
    }

    public String name() {
        return name;
    }

    public IPersistentMap request() {
        return request;
    }
}
