CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT NOT NULL PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    age INT,
    email VARCHAR(128),
    create_time TIMESTAMP,
    update_time TIMESTAMP,
    create_by VARCHAR(64),
    update_by VARCHAR(64),
    version INT DEFAULT 0,
    deleted INT DEFAULT 0
);
