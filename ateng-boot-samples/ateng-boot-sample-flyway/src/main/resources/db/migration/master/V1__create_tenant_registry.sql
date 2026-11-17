CREATE TABLE IF NOT EXISTS sys_tenant_registry (
    id BIGINT PRIMARY KEY,
    tenant_code VARCHAR(64) NOT NULL,
    tenant_name VARCHAR(128) NOT NULL,
    ds_key VARCHAR(64) NOT NULL,
    plan_tier VARCHAR(32) NOT NULL,
    source_ds VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL
);

INSERT INTO sys_tenant_registry (id, tenant_code, tenant_name, ds_key, plan_tier, source_ds, status)
VALUES
    (1001, '1001', '星辰云商科技有限公司', 'tenant_1001', 'PRO', '${ateng_ds_key}', 'ACTIVE'),
    (1002, '1002', '云海智能制造集团', 'tenant_1002', 'ENTERPRISE', '${ateng_ds_key}', 'ACTIVE');
