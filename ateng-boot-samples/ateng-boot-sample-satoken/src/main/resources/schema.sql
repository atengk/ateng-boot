CREATE TABLE IF NOT EXISTS sys_tenant_order (
    id BIGINT NOT NULL PRIMARY KEY,
    order_no VARCHAR(64) NOT NULL,
    amount DECIMAL(10, 2),
    tenant_id VARCHAR(64),
    create_time TIMESTAMP,
    update_time TIMESTAMP,
    create_by VARCHAR(64),
    update_by VARCHAR(64)
);
