package voucha.sdk;

import clojure.lang.ILookup;
import clojure.lang.Keyword;

import java.util.Collection;

/**
 * Reading the values a handler is given: a field of a record from a payload or
 * node data, a number with a default, the size of a collection. These are the
 * reads the plan extractor understands ({@code voucha.sdk.java-plan}), so a
 * handler that uses them, and nothing else, is read as a plan.
 */
public final class Values {

    private Values() {}

    /** A field of a record: a map with keyword keys, as payloads and node data hold them. */
    public static Object field(Object record, String key) {
        if (record instanceof ILookup) {
            return ((ILookup) record).valAt(Keyword.intern(key));
        }
        return null;
    }

    /** A number, or 0 when the value is absent or not a number. */
    public static long number(Object v) {
        return v instanceof Number ? ((Number) v).longValue() : 0L;
    }

    /** The size of a collection, 0 when absent. */
    public static long count(Object coll) {
        if (coll instanceof Collection) return ((Collection<?>) coll).size();
        if (coll instanceof Iterable) {
            long n = 0;
            for (Object ignored : (Iterable<?>) coll) n++;
            return n;
        }
        return 0L;
    }

    public static boolean isEmpty(Object coll) {
        return count(coll) == 0;
    }
}
