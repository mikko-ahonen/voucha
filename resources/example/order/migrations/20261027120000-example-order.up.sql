-- The order example's own tables: the systems its effects reach, co-located
-- for the example. The platform's tables are in the platform's migrations;
-- an application brings its own, as this one does.

CREATE TABLE example_product (
    sku TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    price_cents BIGINT NOT NULL,
    stock INTEGER NOT NULL
);

--;;

CREATE TABLE example_customer (
    customer_id TEXT PRIMARY KEY,
    name TEXT NOT NULL,
    email TEXT NOT NULL,
    credit_limit_cents BIGINT NOT NULL
);

--;;

-- The warehouse: one row per reserved line, keyed by the line so that a
-- repeated reservation of the same line is the same reservation.
CREATE TABLE example_reservation (
    reservation_id TEXT PRIMARY KEY,
    order_id TEXT NOT NULL,
    item_id TEXT NOT NULL UNIQUE,
    sku TEXT NOT NULL REFERENCES example_product(sku),
    qty INTEGER NOT NULL,
    status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

--;;

-- The payment provider: one row per charge attempt, keyed by the idempotency
-- key the platform sends, so that asking about an uncertain charge is a lookup.
CREATE TABLE example_payment (
    payment_ref TEXT PRIMARY KEY,
    idempotency_key TEXT NOT NULL UNIQUE,
    order_id TEXT NOT NULL,
    customer_id TEXT NOT NULL,
    amount_cents BIGINT NOT NULL,
    status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

--;;

-- The carrier: one shipment per order.
CREATE TABLE example_shipment (
    tracking TEXT PRIMARY KEY,
    order_id TEXT NOT NULL UNIQUE,
    customer_id TEXT NOT NULL,
    line_count INTEGER NOT NULL,
    status TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
