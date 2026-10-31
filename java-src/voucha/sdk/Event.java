package voucha.sdk;

import clojure.lang.ILookup;
import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;

/** The event that triggered a transition. Immutable input (design 22). */
public final class Event {

    private static final Keyword EVENT_TYPE = Keyword.intern("event-type");
    private static final Keyword PAYLOAD = Keyword.intern("payload");
    private static final Keyword EVENT_ID = Keyword.intern("event-id");

    private final IPersistentMap event;

    public Event(IPersistentMap event) {
        this.event = event;
    }

    public String eventId() {
        return (String) event.valAt(EVENT_ID);
    }

    public String eventType() {
        return (String) event.valAt(EVENT_TYPE);
    }

    public Object payload(String key) {
        Object p = event.valAt(PAYLOAD);
        return p instanceof ILookup ? ((ILookup) p).valAt(Keyword.intern(key)) : null;
    }

    public String string(String key) {
        Object v = payload(key);
        return v == null ? null : v.toString();
    }
}
