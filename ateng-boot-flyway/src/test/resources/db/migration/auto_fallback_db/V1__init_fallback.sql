CREATE TABLE fallback_audit_item (
    id BIGINT PRIMARY KEY,
    note VARCHAR(64)
);
INSERT INTO fallback_audit_item (id, note) VALUES (1, 'auto-fallback-ok');
