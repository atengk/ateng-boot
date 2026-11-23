CREATE TABLE IF NOT EXISTS biz_audit_log (
    id BIGINT PRIMARY KEY,
    source_ds VARCHAR(64) NOT NULL,
    audit_scope VARCHAR(64) NOT NULL,
    action_name VARCHAR(64) NOT NULL,
    operator_name VARCHAR(64) NOT NULL,
    detail VARCHAR(256) NOT NULL
);

INSERT INTO biz_audit_log (id, source_ds, audit_scope, action_name, operator_name, detail)
VALUES (1, '${ateng_ds_key}', '${auditScope}', 'SCHEMA_INIT', 'flyway-boot', '初始化审计日志独立库表结构');
