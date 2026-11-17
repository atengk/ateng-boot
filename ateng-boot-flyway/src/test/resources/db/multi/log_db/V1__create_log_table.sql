CREATE TABLE IF NOT EXISTS biz_audit_log (
    id BIGINT PRIMARY KEY,
    source_ds VARCHAR(64) NOT NULL,
    custom_tag VARCHAR(64) NOT NULL
);

INSERT INTO biz_audit_log (id, source_ds, custom_tag)
VALUES (1, '${ateng_ds_key}', '${customTag}');
