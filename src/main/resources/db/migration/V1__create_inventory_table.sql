-- Inventory on hand per SKU. One row per SKU; quantity can never go negative.
-- See tech-spec.md §3 for the rationale behind each constraint and storage option.

CREATE TABLE inventory (
    sku_id      VARCHAR(64) COLLATE "C" NOT NULL,
    quantity    INTEGER                 NOT NULL DEFAULT 0,
    created_at  TIMESTAMPTZ             NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ             NOT NULL DEFAULT now(),

    CONSTRAINT pk_inventory
        PRIMARY KEY (sku_id),
    CONSTRAINT chk_inventory_quantity_non_negative
        CHECK (quantity >= 0),
    CONSTRAINT chk_inventory_sku_id_not_blank
        CHECK (length(btrim(sku_id)) > 0)
) WITH (fillfactor = 90);

COMMENT ON TABLE  inventory            IS 'Quantity on hand per SKU';
COMMENT ON COLUMN inventory.sku_id     IS 'Merchant SKU identifier, case-sensitive, max 64 chars';
COMMENT ON COLUMN inventory.quantity   IS 'Units on hand; enforced >= 0 at the data tier';
COMMENT ON COLUMN inventory.created_at IS 'Row creation time (first stock-in)';
COMMENT ON COLUMN inventory.updated_at IS 'Last quantity change, maintained by trigger';

CREATE FUNCTION inventory_set_updated_at() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$;

CREATE TRIGGER trg_inventory_set_updated_at
    BEFORE UPDATE ON inventory
    FOR EACH ROW
    EXECUTE FUNCTION inventory_set_updated_at();
