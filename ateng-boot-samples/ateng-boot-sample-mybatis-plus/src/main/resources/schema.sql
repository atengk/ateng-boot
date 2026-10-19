CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT NOT NULL PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    age INT,
    email VARCHAR(256),
    phone VARCHAR(256),
    dept_id BIGINT,
    create_time TIMESTAMP,
    update_time TIMESTAMP,
    create_by VARCHAR(64),
    update_by VARCHAR(64),
    version INT DEFAULT 0,
    deleted INT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS tenant_order (
    id BIGINT NOT NULL PRIMARY KEY,
    order_no VARCHAR(64) NOT NULL,
    order_title VARCHAR(128) NOT NULL,
    sku_code VARCHAR(64) NOT NULL,
    amount DECIMAL(10, 2),
    status VARCHAR(32) DEFAULT 'CREATED',
    tenant_id VARCHAR(64),
    create_time TIMESTAMP,
    update_time TIMESTAMP,
    create_by VARCHAR(64),
    update_by VARCHAR(64),
    version INT DEFAULT 0
);
