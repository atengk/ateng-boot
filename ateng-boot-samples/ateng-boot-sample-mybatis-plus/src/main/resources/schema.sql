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
    order_no VARCHAR(64) NOT NULL UNIQUE,
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

CREATE TABLE IF NOT EXISTS demo_json_user (
    id BIGINT NOT NULL PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    tags VARCHAR(512),
    roles VARCHAR(512),
    extra_info VARCHAR(1024),
    profile VARCHAR(2048),
    create_time TIMESTAMP,
    update_time TIMESTAMP,
    create_by VARCHAR(64),
    update_by VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS task_queue (
    id BIGINT NOT NULL PRIMARY KEY,
    task_name VARCHAR(64) NOT NULL,
    status VARCHAR(32) DEFAULT 'PENDING',
    version INT DEFAULT 0,
    create_time TIMESTAMP,
    update_time TIMESTAMP,
    create_by VARCHAR(64),
    update_by VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS soft_delete_item (
    id BIGINT NOT NULL PRIMARY KEY,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(128) NOT NULL,
    deleted BIGINT DEFAULT 0,
    version INT DEFAULT 0,
    create_time TIMESTAMP,
    update_time TIMESTAMP,
    create_by VARCHAR(64),
    update_by VARCHAR(64),
    CONSTRAINT uk_code_deleted UNIQUE (code, deleted)
);

CREATE TABLE IF NOT EXISTS sys_dept (
    id BIGINT NOT NULL PRIMARY KEY,
    dept_name VARCHAR(64) NOT NULL,
    status VARCHAR(32) DEFAULT 'ACTIVE',
    create_time TIMESTAMP,
    update_time TIMESTAMP,
    create_by VARCHAR(64),
    update_by VARCHAR(64),
    version INT DEFAULT 0,
    deleted INT DEFAULT 0
);
