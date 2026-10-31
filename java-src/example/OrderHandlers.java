package example;

import clojure.lang.IPersistentMap;
import voucha.sdk.EffectIntent;
import voucha.sdk.Event;
import voucha.sdk.NodeContext;
import voucha.sdk.Transition;
import voucha.sdk.Values;

import java.util.HashMap;
import java.util.Map;

/**
 * The order example's handlers. Each is a pure function of the node context and
 * the event, returning a plan. None performs I/O: a {@code call} is an intent
 * the dispatcher executes after the transaction commits.
 *
 * Each is written in the subset the plan extractor reads ({@code
 * voucha.sdk.java-plan}): the plan builder, the reads on the context and the
 * event, {@link Values}, and plain control flow. The kernel runs the extracted
 * plan; this source is its syntax, and the model checker reads it exactly.
 */
public final class OrderHandlers {

    // --- Order -------------------------------------------------------------------

    /** Remember the lines and the customer; an order with no lines is rejected. */
    @Transition(nodeType = "Order", from = "ORDER_NEW", on = "OrderPlaced", to = "ORDER_VALIDATING",
                emit = {"OrderValid", "OrderRejected"})
    public IPersistentMap placed(NodeContext ctx, Event event) {
        Object items = event.payload("items");
        boolean empty = Values.isEmpty(items);
        return ctx.plan()
            .state("ORDER_VALIDATING")
            .set("customer", event.payload("customer"))
            .set("items", items)
            .emitTo(ctx.nodeId(), empty ? "OrderRejected" : "OrderValid")
            .build();
    }

    @Transition(nodeType = "Order", from = "ORDER_VALIDATING", on = "OrderRejected", to = "ORDER_REJECTED")
    public IPersistentMap rejected(NodeContext ctx, Event event) {
        return ctx.plan()
            .state("ORDER_REJECTED")
            .set("reason", "an order needs at least one line")
            .build();
    }

    /**
     * One Item child per line, each commanded to reserve. The children are
     * created and commanded in this same plan; the kernel creates them before the
     * command is delivered.
     */
    @Transition(nodeType = "Order", from = "ORDER_VALIDATING", on = "OrderValid", to = "ORDER_RESERVE_ITEMS",
                creates = {"Item"}, commands = {"ItemReserveCommanded"})
    public IPersistentMap reserve(NodeContext ctx, Event event) {
        var plan = ctx.plan().state("ORDER_RESERVE_ITEMS");
        for (Object line : (Iterable<?>) ctx.data("items")) {
            String itemId = (String) Values.field(line, "item-id");
            plan = plan.child(itemId, "Item")
                       .emitTo(itemId, "ItemReserveCommanded", (IPersistentMap) line);
        }
        return plan.build();
    }

    /**
     * The decision, taken by the kernel on entering ORDER_RESERVE_ITEMS from the
     * counters it keeps: every line settled and none unavailable means charge.
     */
    @Transition(nodeType = "Order", from = "ORDER_RESERVE_ITEMS", on = "AllItemsReserved", to = "ORDER_CHARGING",
                effects = {"payment.charge"}, await = {"PaymentCharged"},
                auto = true, countersCoverChildren = {"items-reserved", "items-unavailable"},
                counter = "items-unavailable", counterOp = "=", counterValue = 0)
    public IPersistentMap charge(NodeContext ctx, Event event) {
        return chargeAttempt(ctx);
    }

    /** Every line settled, some unavailable: release what was reserved. */
    @Transition(nodeType = "Order", from = "ORDER_RESERVE_ITEMS", on = "SomeItemsUnavailable", to = "ORDER_RELEASING",
                effects = {"inventory.release"}, await = {"StockReleased"},
                auto = true, countersCoverChildren = {"items-reserved", "items-unavailable"})
    public IPersistentMap releaseShort(NodeContext ctx, Event event) {
        return release(ctx, "stock unavailable");
    }

    /** Otherwise lines are still pending: wait where the items run. */
    @Transition(nodeType = "Order", from = "ORDER_RESERVE_ITEMS", on = "ItemsPending", to = "ORDER_RESERVING", auto = true)
    public IPersistentMap waitForItems(NodeContext ctx, Event event) {
        return ctx.plan().state("ORDER_RESERVING").build();
    }

    /** A line settled: back to the decision. */
    @Transition(nodeType = "Order", from = "ORDER_RESERVING", on = "ItemSettled", to = "ORDER_RESERVE_ITEMS")
    public IPersistentMap itemSettled(NodeContext ctx, Event event) {
        return ctx.plan().state("ORDER_RESERVE_ITEMS").build();
    }

    /** A line settled while the order was already deciding: decide again. */
    @Transition(nodeType = "Order", from = "ORDER_RESERVE_ITEMS", on = "ItemSettled", to = "ORDER_RESERVE_ITEMS")
    public IPersistentMap itemSettledWhileDeciding(NodeContext ctx, Event event) {
        return ctx.plan().state("ORDER_RESERVE_ITEMS").build();
    }

    /** The same charge again, as a new intent: the attempt number is in its key. */
    @Transition(nodeType = "Order", from = "ORDER_CHARGE_RETRY_WAIT", on = "ChargeRetry", to = "ORDER_CHARGING",
                effects = {"payment.charge"}, await = {"PaymentCharged"})
    public IPersistentMap chargeAgain(NodeContext ctx, Event event) {
        return chargeAttempt(ctx);
    }

    private static IPersistentMap chargeAttempt(NodeContext ctx) {
        long total = 0;
        for (Object line : (Iterable<?>) ctx.data("items")) {
            total += Values.number(Values.field(line, "qty")) * Values.number(Values.field(line, "price"));
        }
        Map<String, Object> request = new HashMap<>();
        request.put("order-id", ctx.nodeId());
        request.put("customer", ctx.data("customer"));
        request.put("amount", total);
        request.put("attempt", Values.number(ctx.data("charge-attempts")));
        return ctx.plan()
            .state("ORDER_CHARGING")
            .set("total", total)
            .call(EffectIntent.of("payment.charge", request))
            .await("PaymentCharged")
            .build();
    }

    /** The provider could not charge: wait and retry, up to a bound, then give up. */
    @Transition(nodeType = "Order", from = "ORDER_CHARGING", on = "PaymentFailed", to = "ORDER_CHARGE_RETRY_WAIT",
                emit = {"ChargeRetry", "ChargeGaveUp"})
    public IPersistentMap chargeFailed(NodeContext ctx, Event event) {
        long attempts = Values.number(ctx.data("charge-attempts"));
        if (ctx.data("cancel-requested") != null) {
            return ctx.plan()
                .state("ORDER_CHARGE_RETRY_WAIT")
                .emitTo(ctx.nodeId(), "ChargeGaveUp")
                .build();
        }
        if (attempts < 2) {
            return ctx.plan()
                .state("ORDER_CHARGE_RETRY_WAIT")
                .increment("charge-attempts", 1)
                .emitTo(ctx.nodeId(), "ChargeRetry")
                .build();
        }
        return ctx.plan()
            .state("ORDER_CHARGE_RETRY_WAIT")
            .set("cancel-reason", event.string("error"))
            .emitTo(ctx.nodeId(), "ChargeGaveUp")
            .build();
    }

    @Transition(nodeType = "Order", from = "ORDER_CHARGE_RETRY_WAIT", on = "ChargeGaveUp", to = "ORDER_RELEASING",
                effects = {"inventory.release"}, await = {"StockReleased"})
    public IPersistentMap releaseAfterCharging(NodeContext ctx, Event event) {
        Object reason = ctx.data("cancel-reason");
        return release(ctx, reason == null ? "payment failed" : reason.toString());
    }

    /**
     * An operator cancels the order while a charge is in flight. A charge cannot
     * be recalled, so the cancel is recorded and honoured when the charge
     * settles: a failed charge gives up at once, a completed one is released
     * and recorded for a refund. (The induction check found the race this
     * closes, a charge completing after the order had moved on; no simulated
     * run reaches it, since none sends an operator's cancel.)
     */
    @Transition(nodeType = "Order", from = "ORDER_CHARGING", on = "OrderCancelRequested", to = "ORDER_CHARGING")
    public IPersistentMap cancelRequested(NodeContext ctx, Event event) {
        String reason = event.string("reason");
        return ctx.plan()
            .state("ORDER_CHARGING")
            .set("cancel-requested", true)
            .set("cancel-reason", reason == null ? "cancelled by operator" : reason)
            .build();
    }

    /** An operator cancels the order while it waits to retry a failed charge: nothing is in flight. */
    @Transition(nodeType = "Order", from = "ORDER_CHARGE_RETRY_WAIT", on = "OrderCancelRequested", to = "ORDER_RELEASING",
                effects = {"inventory.release"}, await = {"StockReleased"})
    public IPersistentMap cancelRequestedWhileWaiting(NodeContext ctx, Event event) {
        String reason = event.string("reason");
        return release(ctx, reason == null ? "cancelled by operator" : reason);
    }

    /** The charge went through: ship, unless a cancel was asked for meanwhile. */
    @Transition(nodeType = "Order", from = "ORDER_CHARGING", on = "PaymentCharged", to = "ORDER_CHARGED",
                emit = {"ShipAfterCharge", "ReleaseAfterCharge"})
    public IPersistentMap charged(NodeContext ctx, Event event) {
        if (ctx.data("cancel-requested") != null) {
            return ctx.plan()
                .state("ORDER_CHARGED")
                .set("payment-ref", event.string("payment-ref"))
                .emitTo(ctx.nodeId(), "ReleaseAfterCharge")
                .build();
        }
        return ctx.plan()
            .state("ORDER_CHARGED")
            .set("payment-ref", event.string("payment-ref"))
            .emitTo(ctx.nodeId(), "ShipAfterCharge")
            .build();
    }

    /**
     * An operator cancels the order just after the charge went through, before
     * it ships: released and recorded for a refund, like a cancel the charge
     * overtook. (Found by TLC once it injected an operator's events.)
     */
    @Transition(nodeType = "Order", from = "ORDER_CHARGED", on = "OrderCancelRequested", to = "ORDER_RELEASING",
                effects = {"inventory.release"}, await = {"StockReleased"})
    public IPersistentMap cancelAfterCharge(NodeContext ctx, Event event) {
        String reason = event.string("reason");
        Map<String, Object> request = new HashMap<>();
        request.put("order-id", ctx.nodeId());
        return ctx.plan()
            .state("ORDER_RELEASING")
            .set("charged-after-cancel", true)
            .set("cancel-reason", reason == null ? "cancelled by operator" : reason)
            .call(EffectIntent.of("inventory.release", request))
            .await("StockReleased")
            .build();
    }

    /**
     * The customer has been charged for an order that will not ship: the order
     * marks itself for a refund and releases its stock.
     */
    @Transition(nodeType = "Order", from = "ORDER_CHARGED", on = "ReleaseAfterCharge", to = "ORDER_RELEASING",
                effects = {"inventory.release"}, await = {"StockReleased"})
    public IPersistentMap releaseAfterCharge(NodeContext ctx, Event event) {
        Map<String, Object> request = new HashMap<>();
        request.put("order-id", ctx.nodeId());
        return ctx.plan()
            .state("ORDER_RELEASING")
            .set("charged-after-cancel", true)
            .call(EffectIntent.of("inventory.release", request))
            .await("StockReleased")
            .build();
    }

    @Transition(nodeType = "Order", from = "ORDER_CHARGED", on = "ShipAfterCharge", to = "ORDER_SHIPPING",
                effects = {"shipping.dispatch"}, await = {"OrderShipped"})
    public IPersistentMap ship(NodeContext ctx, Event event) {
        Map<String, Object> request = new HashMap<>();
        request.put("order-id", ctx.nodeId());
        request.put("customer", ctx.data("customer"));
        request.put("items", ctx.data("items"));
        return ctx.plan()
            .state("ORDER_SHIPPING")
            .call(EffectIntent.of("shipping.dispatch", request))
            .await("OrderShipped")
            .build();
    }

    @Transition(nodeType = "Order", from = "ORDER_SHIPPING", on = "OrderShipped", to = "ORDER_COMPLETED")
    public IPersistentMap completed(NodeContext ctx, Event event) {
        return ctx.plan()
            .state("ORDER_COMPLETED")
            .set("tracking", event.string("tracking"))
            .build();
    }

    /** Charged but not shipped: a person has to sort this one out. */
    @Transition(nodeType = "Order", from = "ORDER_SHIPPING", on = "ShippingFailed", to = "ORDER_SHIPPING_FAILED")
    public IPersistentMap shippingFailed(NodeContext ctx, Event event) {
        return ctx.plan()
            .state("ORDER_SHIPPING_FAILED")
            .set("reason", event.string("error"))
            .build();
    }

    private static IPersistentMap release(NodeContext ctx, String reason) {
        Map<String, Object> request = new HashMap<>();
        request.put("order-id", ctx.nodeId());
        return ctx.plan()
            .state("ORDER_RELEASING")
            .set("cancel-reason", reason)
            .call(EffectIntent.of("inventory.release", request))
            .await("StockReleased")
            .build();
    }

    @Transition(nodeType = "Order", from = "ORDER_RELEASING", on = "StockReleased", to = "ORDER_CANCELLED")
    public IPersistentMap cancelled(NodeContext ctx, Event event) {
        return ctx.plan().state("ORDER_CANCELLED").build();
    }

    /**
     * The release itself failed: the order is still cancelled, and the stock is
     * flagged for someone to reconcile. A failure must still end the order.
     */
    @Transition(nodeType = "Order", from = "ORDER_RELEASING", on = "StockReleaseFailed", to = "ORDER_CANCELLED")
    public IPersistentMap cancelledUnreleased(NodeContext ctx, Event event) {
        return ctx.plan()
            .state("ORDER_CANCELLED")
            .set("stock-release-pending", true)
            .build();
    }

    // --- Item --------------------------------------------------------------------

    /** Reserve this line's stock. Only while the order is reserving. */
    @Transition(nodeType = "Item", from = "ITEM_NEW", on = "ItemReserveCommanded", to = "ITEM_RESERVING",
                effects = {"inventory.reserve"}, await = {"ItemReserved"},
                parentState = {"ORDER_RESERVE_ITEMS", "ORDER_RESERVING"})
    public IPersistentMap itemReserve(NodeContext ctx, Event event) {
        Map<String, Object> request = new HashMap<>();
        request.put("item-id", ctx.nodeId());
        request.put("sku", event.payload("sku"));
        request.put("qty", event.payload("qty"));
        return ctx.plan()
            .state("ITEM_RESERVING")
            .set("sku", event.payload("sku"))
            .set("qty", event.payload("qty"))
            .call(EffectIntent.of("inventory.reserve", request))
            .await("ItemReserved")
            .build();
    }

    @Transition(nodeType = "Item", from = "ITEM_RESERVING", on = "ItemReserved", to = "ITEM_RESERVED",
                emit = {"ItemSettled"})
    public IPersistentMap itemReserved(NodeContext ctx, Event event) {
        return ctx.plan()
            .state("ITEM_RESERVED")
            .set("reservation", event.string("reservation"))
            .emit("ItemSettled")
            .build();
    }

    @Transition(nodeType = "Item", from = "ITEM_RESERVING", on = "ItemOutOfStock", to = "ITEM_UNAVAILABLE",
                emit = {"ItemSettled"})
    public IPersistentMap itemUnavailable(NodeContext ctx, Event event) {
        return ctx.plan().state("ITEM_UNAVAILABLE").emit("ItemSettled").build();
    }
}
