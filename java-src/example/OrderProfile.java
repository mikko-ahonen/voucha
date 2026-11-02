package example;

import voucha.sdk.EffectSpec;
import voucha.sdk.ProcessProfile;
import voucha.sdk.ProfileBuilder;
import voucha.sdk.Schema;
import static voucha.sdk.Schema.*;

/**
 * The order example's definition: two node types, four effects, one aggregation.
 *
 * <p>An order is placed with a list of lines. Each line reserves its stock; when
 * every line has either reserved or turned out to be unavailable, the order
 * either charges the customer and ships, or releases what was reserved and is
 * cancelled. The handlers are in {@link OrderHandlers}.
 */
public final class OrderProfile implements ProcessProfile {

    /** One order line, as placed and as commanded to its item. */
    static final String LINE = record("item-id", STR, "sku", STR, "qty", INT, "price", INT);

    @Override
    public void define(ProfileBuilder p) {
        p.profile("order", "v1")

         .events("OrderPlaced", "OrderValid", "OrderRejected",
                 "ItemSettled", "AllItemsReserved", "SomeItemsUnavailable", "ItemsPending",
                 "PaymentCharged", "PaymentFailed", "ChargeRetry", "ChargeGaveUp",
                 "ShipAfterCharge", "ReleaseAfterCharge",
                 "OrderCancelRequested",
                 "OrderShipped", "ShippingFailed",
                 "StockReleased", "StockReleaseFailed",
                 "ItemReserveCommanded", "ItemReserved", "ItemOutOfStock")

         // What the payloads carry, for the compiler's checks and the typed specification.
         .payload("OrderPlaced", "customer", STR, "items", setOf(LINE))
         .payload("ItemReserveCommanded", "item-id", STR, "sku", STR, "qty", INT, "price", INT)
         .payload("PaymentCharged", "payment-ref", STR)
         .payload("PaymentFailed", "error", STR)
         .payload("OrderCancelRequested", "reason", STR)
         .payload("OrderShipped", "tracking", STR)
         .payload("ShippingFailed", "error", STR)
         .payload("ItemReserved", "reservation", STR)

         // Reserve one line's stock. The warehouse deduplicates on the item id we
         // send, so an unknown outcome is simply tried again.
         .effect(EffectSpec.external("inventory.reserve")
                     .retrySafe()
                     .onCompletion("ItemReserved")
                     .onFailure("ItemOutOfStock"))

         // Release everything reserved for an order, keyed by order, so the
         // handler need not know which lines succeeded.
         .effect(EffectSpec.external("inventory.release")
                     .retrySafe()
                     .onCompletion("StockReleased")
                     .onFailure("StockReleaseFailed"))

         // Charging twice is the one thing that must never happen: an unknown
         // outcome is resolved by asking the payment provider, never by resending.
         // A failed charge is retried as a new intent, so the attempt number is
         // part of the key.
         .effect(EffectSpec.external("payment.charge")
                     .idempotencyKey("tree-id", "node-id", "attempt")
                     .query("payment.status")
                     .onCompletion("PaymentCharged")
                     .onFailure("PaymentFailed"))

         .effect(EffectSpec.external("shipping.dispatch")
                     .retrySafe()
                     .onCompletion("OrderShipped")
                     .onFailure("ShippingFailed"))

         .nodeType("Order")
             .mayContain("Item")
             // ORDER_RESERVE_ITEMS is the decision: every line settled and all reserved,
             // every line settled with some unavailable, or lines still pending. Its
             // auto transitions choose from the counters; ORDER_RESERVING is where the
             // items run, and each settling line brings the order back to decide.
             .states("ORDER_NEW", "ORDER_VALIDATING", "ORDER_RESERVE_ITEMS", "ORDER_RESERVING",
                     // Charging: the call, the wait before a failed charge is retried, and the
                     // decision after a charge went through: ship, or release if a cancel came.
                     "ORDER_CHARGING", "ORDER_CHARGE_RETRY_WAIT", "ORDER_CHARGED",
                     "ORDER_SHIPPING", "ORDER_RELEASING",
                     "ORDER_COMPLETED", "ORDER_CANCELLED", "ORDER_REJECTED", "ORDER_SHIPPING_FAILED")
             .initialState("ORDER_NEW")
             .terminalStates("ORDER_COMPLETED", "ORDER_CANCELLED", "ORDER_REJECTED", "ORDER_SHIPPING_FAILED")
             .failureState("ORDER_CANCELLED")
             .mayEmitEffects("inventory.release", "payment.charge", "shipping.dispatch")
             // What the order knows about its items: how many reserved, how many could not.
             .counter("items-reserved", "Item", "ITEM_RESERVED")
             .counter("items-unavailable", "Item", "ITEM_UNAVAILABLE")
             .hosts("Item", "ORDER_RESERVING")
             // What an order records.
             .data("customer", STR)
             .data("items", setOf(LINE))
             .data("total", INT)
             .data("charge-attempts", INT)
             .data("reason", STR)
             .data("cancel-reason", STR)
             .data("payment-ref", STR)
             .data("tracking", STR)
             .data("stock-release-pending", BOOL)
             .data("charged-after-cancel", BOOL)
             .data("cancel-requested", BOOL)
             // The decision the charge produced may still be queued when a cancel overtakes it.
             .ignores("ORDER_RELEASING", "ShipAfterCharge", "ReleaseAfterCharge", "ChargeRetry", "ChargeGaveUp")
             // An operator's cancel that arrives too late, once the order ships or is already
             // being released, is ignored, not rejected: the operator sees it recorded as such.
             .ignores("ORDER_SHIPPING", "OrderCancelRequested")
             .ignores("ORDER_COMPLETED", "OrderCancelRequested")
             .ignores("ORDER_SHIPPING_FAILED", "OrderCancelRequested")
             .ignores("ORDER_RELEASING", "OrderCancelRequested")
             .ignores("ORDER_CANCELLED", "OrderCancelRequested")

         .nodeType("Item")
             .states("ITEM_NEW", "ITEM_RESERVING", "ITEM_RESERVED", "ITEM_UNAVAILABLE")
             .initialState("ITEM_NEW")
             .terminalStates("ITEM_RESERVED", "ITEM_UNAVAILABLE")
             .failureState("ITEM_UNAVAILABLE")
             .mayEmitEffects("inventory.reserve")
             .data("sku", STR)
             .data("qty", INT)
             .data("reservation", STR)

         // An item's settle event goes up to its order; until every item has
         // settled, the order's rule is not met and the event is ignored, not rejected.
         .routeUpward("ItemSettled", "Item", "Order")

         // What must hold of every order, beyond what the framework checks. Charging twice
         // is two requests reaching the provider, so the property is over every charge
         // effect the provider may have acted on: not cancelled before dispatch, and not
         // definitely failed, after which a new request is a retry, not a double charge. A
         // request that timed out here may have succeeded there, so it still counts.
         // (Provable.)
         .invariant("one-charge-per-order",
                    "[:forall :o [:nodes \"Order\"]"
                    + " [:at-most-one :e [:effects \"payment.charge\" :owner [:var :o]]"
                    + "  [:not [:or [:= [:status-of [:var :e]] \"CANCELLED\"] [:= [:status-of [:var :e]] \"FAILED\"]]]]]")
         // An order ships only on a completed charge. (Provable.)
         .invariant("shipped-is-paid",
                    "[:forall :o [:nodes \"Order\" :in [\"ORDER_SHIPPING\" \"ORDER_COMPLETED\"]]"
                    + " [:exactly-one :e [:effects \"payment.charge\" :owner [:var :o] :status [\"COMPLETED\"]]]]")
         // The total charged is the lines' total. Sums a collection: checkable, not provable.
         .invariant("charged-total-is-the-lines",
                    "[:forall :o [:nodes \"Order\" :in [\"ORDER_SHIPPING\" \"ORDER_COMPLETED\"]]"
                    + " [:= [:data-of [:var :o] :total]"
                    + "     [:sum :l [:data-of [:var :o] :items] [:* [:get [:var :l] :qty] [:get [:var :l] :price]]]]]")
         // A cancelled order holds no stock, once its release is through. (Provable.)
         .invariant("cancelled-holds-nothing",
                    "[:forall :o [:nodes \"Order\" :in [\"ORDER_CANCELLED\"]]"
                    + " [:implies [:not [:data-of [:var :o] :stock-release-pending]]"
                    + "  [:exactly-one :e [:effects \"inventory.release\" :owner [:var :o] :status [\"COMPLETED\"]]]]]");
    }
}
