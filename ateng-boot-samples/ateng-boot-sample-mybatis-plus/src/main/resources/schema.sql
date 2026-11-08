CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT NOT NULL PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    age INT,
    email VARCHAR(256),
    phone VARCHAR(256),
    dept_id BIGINT,
    internal_remark VARCHAR(256),
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

CREATE TABLE IF NOT EXISTS member_profile (
    id BIGINT NOT NULL PRIMARY KEY,
    member_name VARCHAR(64) NOT NULL,
    tags VARCHAR(512),
    roles VARCHAR(512),
    extra_info VARCHAR(1024),
    profile VARCHAR(2048),
    shipping_addresses VARCHAR(2048),
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

CREATE TABLE IF NOT EXISTS product_sku (
    id BIGINT NOT NULL PRIMARY KEY,
    sku_code VARCHAR(64) NOT NULL,
    sku_name VARCHAR(128) NOT NULL,
    category VARCHAR(64),
    price DECIMAL(10, 2),
    deleted BIGINT DEFAULT 0,
    version INT DEFAULT 0,
    create_time TIMESTAMP,
    update_time TIMESTAMP,
    create_by VARCHAR(64),
    update_by VARCHAR(64),
    CONSTRAINT uk_sku_code_deleted UNIQUE (sku_code, deleted)
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
