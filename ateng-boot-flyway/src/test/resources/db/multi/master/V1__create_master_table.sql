CREATE TABLE IF NOT EXISTS platform_config (
    id BIGINT PRIMARY KEY,
    ds_tag VARCHAR(64) NOT NULL,
    tenant_tag VARCHAR(64) NOT NULL
);

INSERT INTO platform_config (id, ds_tag, tenant_tag)
VALUES (1, '${ateng_ds_key}', '${ateng_tenant_id}');
