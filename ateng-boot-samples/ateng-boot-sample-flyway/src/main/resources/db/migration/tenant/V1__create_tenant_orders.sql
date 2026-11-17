CREATE TABLE IF NOT EXISTS biz_tenant_order (
    id BIGINT PRIMARY KEY,
    order_no VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    source_ds VARCHAR(64) NOT NULL,
    product_name VARCHAR(128) NOT NULL,
    amount DECIMAL(12, 2) NOT NULL,
    status VARCHAR(32) NOT NULL
);

INSERT INTO biz_tenant_order (id, order_no, tenant_id, source_ds, product_name, amount, status)
VALUES (1, 'ORD-${ateng_tenant_id}-INIT', '${ateng_tenant_id}', '${ateng_ds_key}', '企业级 SaaS 标准版订阅', 2999.00, 'PAID');
