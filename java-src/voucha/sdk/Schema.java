package voucha.sdk;

/**
 * Field types for the schemas a profile declares: what a node type's data and
 * an event's payload hold. The typed specification the proof runs on needs
 * them; the compiler checks plans against them when they are declared.
 *
 * <p>Each method returns the type as the EDN text the Clojure profile API
 * uses, so the compiler sees one form whichever language declared it:
 * {@code INT} is {@code :int}, {@code maybe(INT)} is {@code [:maybe :int]},
 * {@code setOf(record("sku", STR, "qty", INT))} is
 * {@code [:set {:sku :str :qty :int}]}.
 */
public final class Schema {

    public static final String INT = ":int";
    public static final String STR = ":str";
    public static final String BOOL = ":bool";

    private Schema() {}

    /** A field that may be absent; a handler tests it with a null check. */
    public static String maybe(String type) {
        return "[:maybe " + type + "]";
    }

    /** A collection of values of one type, usually a {@link #record}. */
    public static String setOf(String elementType) {
        return "[:set " + elementType + "]";
    }

    /** A record: field name and type, alternating. */
    public static String record(String... fieldsAndTypes) {
        if (fieldsAndTypes.length % 2 != 0) {
            throw new IllegalArgumentException("record takes field name and type pairs");
        }
        StringBuilder b = new StringBuilder("{");
        for (int i = 0; i < fieldsAndTypes.length; i += 2) {
            if (i > 0) b.append(' ');
            b.append(':').append(fieldsAndTypes[i]).append(' ').append(fieldsAndTypes[i + 1]);
        }
        return b.append('}').toString();
    }
}
