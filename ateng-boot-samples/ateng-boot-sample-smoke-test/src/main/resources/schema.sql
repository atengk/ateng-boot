CREATE TABLE IF NOT EXISTS sys_smoke_record (
    id BIGINT NOT NULL PRIMARY KEY,
    title VARCHAR(128) NOT NULL,
    amount DECIMAL(10, 2),
    create_time TIMESTAMP,
    update_time TIMESTAMP,
    create_by VARCHAR(64),
    update_by VARCHAR(64)
);
