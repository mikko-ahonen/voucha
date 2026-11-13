package voucha.sdk;

import clojure.java.api.Clojure;
import clojure.lang.IFn;

/** Bridge to the kernel's deterministic facts (design 58). */
final class Facts {

    private static final IFn REQUIRE = Clojure.var("clojure.core", "require");

    static {
        REQUIRE.invoke(Clojure.read("voucha.facts"));
    }

    private static final IFn TRANSACTION_TIME = Clojure.var("voucha.facts", "transaction-time");
    private static final IFn NEXT_ID = Clojure.var("voucha.facts", "next-id");

    private Facts() {}

    static Object transactionTime(Object facts) {
        return TRANSACTION_TIME.invoke(facts);
    }

    static String nextId(Object facts) {
        return (String) NEXT_ID.invoke(facts);
    }
}
