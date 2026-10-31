package example;

import clojure.lang.ILookup;
import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;
import clojure.lang.PersistentVector;
import voucha.sdk.EffectIntent;
import voucha.sdk.Event;
import voucha.sdk.NodeContext;
import voucha.sdk.Transition;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The design's Java examples (sections 38-41), re-expressed on the SEPA instant
 * payment example and running against the real kernel.
 *
 * <p>None of these methods performs I/O. A {@code .call(...)} or {@code .store(...)}
 * creates an effect intent; both become durable rows inside the kernel transaction,
 * and are executed only after it commits. Java is an authoring surface: each of
 * these compiles into the same transition spec and produces the same plan data a
 * Clojure handler would, so the kernel cannot tell which language wrote it.
 */
public final class BatchTransitions {

    private static Keyword kw(String s) { return Keyword.intern(s); }

    /** Design 41: a store effect. The transition writes no file; it creates an intent. */
    @Transition(nodeType = "File", from = "FILE_NEW", on = "FileReceived",
                to = "WRITING_RECEIVE_FILE",
                effects = {"file.writeReceive"}, await = {"ReceiveFileWritten"})
    public IPersistentMap received(NodeContext ctx, Event event) {
        Object batches = event.payload("batches");
        int count = (batches instanceof java.util.Collection) ? ((java.util.Collection<?>) batches).size() : 0;
        String name = event.string("name");
        if (name == null) name = "file";
        return ctx.plan()
            .state("WRITING_RECEIVE_FILE")
            .setIn(new String[]{"batches"}, batches)
            .store(EffectIntent.of("file.writeReceive", Map.of("name", name, "batch-count", count)))
            .await("ReceiveFileWritten")
            .build();
    }

    /**
     * A transition that creates child nodes, reading its own data back. Commanding
     * the children on is a separate step (the Java plan surface deliberately has
     * no downward-command primitive).
     */
    @Transition(nodeType = "Batch", from = "BATCH_VALIDATING", on = "BatchValidated",
                to = "BATCH_SCREEN_TRANSACTIONS")
    public IPersistentMap prepare(NodeContext ctx, Event event) {
        Object txns = ctx.data("transactions");
        var plan = ctx.plan().state("BATCH_SCREEN_TRANSACTIONS");
        List<Object> childIds = new ArrayList<>();
        for (Object t : (Iterable<?>) txns) {
            String id = (String) ((ILookup) t).valAt(kw("tx-id"));
            plan = plan.child(id, "Transaction");
            childIds.add(id);
        }
        return plan
            .setIn(new String[]{"children"}, PersistentVector.create(childIds))
            .set("validated-count", (long) childIds.size())
            .build();
    }

    /** Design 38: screen the transaction, await the answer, arm a timeout. */
    @Transition(nodeType = "Transaction", from = "TX_VALIDATED", on = "TransactionScreeningStart",
                to = "TX_SCREENING",
                effects = {"screening.check"}, await = {"TransactionScreeningCleared"})
    public IPersistentMap screen(NodeContext ctx, Event event) {
        Object retries = ctx.data("screening-retries");
        long attempt = (retries instanceof Number) ? ((Number) retries).longValue() : 0L;
        Map<String, Object> request = new HashMap<>();
        request.put("tx-id", ctx.nodeId());
        request.put("amount", ctx.data("amount"));
        request.put("attempt", attempt);
        return ctx.plan()
            .state("TX_SCREENING")
            .call(EffectIntent.of("screening.check", request))
            .await("TransactionScreeningCleared")
            .timeoutAfter(Duration.ofMinutes(15), "TransactionScreeningTimedOut")
            .build();
    }

    /**
     * Design 39: the screening answer carries a durable external reference, which
     * enters node state before the transaction bubbles its readiness upward.
     */
    @Transition(nodeType = "Transaction", from = "TX_SCREENING", on = "TransactionScreeningCleared",
                to = "TX_SCREENED", emit = {"TransactionReady"})
    public IPersistentMap screened(NodeContext ctx, Event event) {
        return ctx.plan()
            .state("TX_SCREENED")
            .set("screening-ref", event.string("request-id"))
            .emit("TransactionReady")
            .build();
    }

    /**
     * Design 40: completion reads the kernel's aggregate counters and bubbles
     * upward to the file. The aggregate rule guarding this transition stays
     * declared in the profile; the annotation supplies the handler.
     */
    @Transition(nodeType = "Batch", from = "BATCH_PAY_TRANSACTIONS", on = "AllTransactionsSettled",
                to = "BATCH_COMPLETED", emit = {"BatchSettled"},
                auto = true, countersCoverChildren = {"tx-paid", "tx-failed"})
    public IPersistentMap completed(NodeContext ctx, Event event) {
        return ctx.plan()
            .state("BATCH_COMPLETED")
            .set("paid", ctx.counter("tx-paid"))
            .set("failed", ctx.counter("tx-failed"))
            .emit("BatchSettled")
            .build();
    }
}
