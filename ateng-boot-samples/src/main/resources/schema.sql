CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT NOT NULL PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    age INT,
    email VARCHAR(128),
    phone VARCHAR(256),
    id_card VARCHAR(256),
    create_time TIMESTAMP,
    update_time TIMESTAMP,
    create_by VARCHAR(64),
    update_by VARCHAR(64),
    version INT DEFAULT 0,
    deleted INT DEFAULT 0
);

CREATE TABLE IF NOT EXISTS sys_user_role (
    id BIGINT NOT NULL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL
);

CREATE TABLE IF NOT EXISTS sys_tenant_order (
    id BIGINT NOT NULL PRIMARY KEY,
    order_no VARCHAR(64) NOT NULL,
    amount DECIMAL(10, 2),
    tenant_id VARCHAR(64),
    create_time TIMESTAMP,
    update_time TIMESTAMP,
    create_by VARCHAR(64),
    update_by VARCHAR(64)
);

CREATE TABLE IF NOT EXISTS sys_dict (
    id BIGINT NOT NULL PRIMARY KEY,
    dict_key VARCHAR(64) NOT NULL,
    dict_value VARCHAR(128)
);

CREATE TABLE IF NOT EXISTS sys_document (
    id BIGINT NOT NULL PRIMARY KEY,
    title VARCHAR(128) NOT NULL,
    dept_id BIGINT,
    user_id BIGINT,
    create_time TIMESTAMP
);

CREATE TABLE IF NOT EXISTS sys_batch_item (
    id BIGINT NOT NULL PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    code VARCHAR(64) NOT NULL,
    create_time TIMESTAMP,
    update_time TIMESTAMP,
    create_by VARCHAR(64),
    update_by VARCHAR(64),
    version INT DEFAULT 0,
    deleted INT DEFAULT 0
);

