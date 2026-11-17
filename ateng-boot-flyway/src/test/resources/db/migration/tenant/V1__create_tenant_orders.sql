CREATE TABLE IF NOT EXISTS tenant_order (
    id BIGINT PRIMARY KEY,
    order_no VARCHAR(64) NOT NULL,
    ds_tag VARCHAR(64) NOT NULL,
    tenant_tag VARCHAR(64) NOT NULL
);

INSERT INTO tenant_order (id, order_no, ds_tag, tenant_tag)
VALUES (1, 'ORD-${ateng_tenant_id}-001', '${ateng_ds_key}', '${ateng_tenant_id}');
