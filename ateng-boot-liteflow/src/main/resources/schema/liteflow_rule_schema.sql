-- ==============================================================================
-- LiteFlow 动态规则元数据表标准 DDL 规范
-- 模块: ateng-boot-liteflow
-- 规范版本: 1.0.0
-- 描述: 适用于 MySQL 5.7+ / 8.0+ / MariaDB / H2 (MySQL Mode) / PostgreSQL / Oracle
-- ==============================================================================

-- ------------------------------------------------------------------------------
-- 1. MySQL / MariaDB / H2 (MySQL Mode) 标准建表语句
-- ------------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS `sys_liteflow_rule` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '主键 ID',
    `rule_id` VARCHAR(128) NOT NULL COMMENT '规则流程链唯一标识 (chainId)',
    `rule_name` VARCHAR(255) NOT NULL COMMENT '规则名称/中文业务描述',
    `el_expression` TEXT NOT NULL COMMENT 'LiteFlow 规则编排 EL 表达式',
    `status` TINYINT NOT NULL DEFAULT 1 COMMENT '状态: 1-启用, 0-禁用',
    `route_group` VARCHAR(64) NOT NULL DEFAULT 'DEFAULT' COMMENT '规则分组/路由/多租户隔离标识',
    `description` VARCHAR(500) DEFAULT NULL COMMENT '规则详细说明备注',
    `created_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_time` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_sys_liteflow_rule_rule_id` (`rule_id`),
    KEY `idx_sys_liteflow_rule_status_route` (`status`, `route_group`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='LiteFlow 动态规则元数据表';

-- ------------------------------------------------------------------------------
-- 2. PostgreSQL 语法变体参考
-- ------------------------------------------------------------------------------
-- CREATE TABLE IF NOT EXISTS sys_liteflow_rule (
--     id BIGSERIAL PRIMARY KEY,
--     rule_id VARCHAR(128) NOT NULL UNIQUE,
--     rule_name VARCHAR(255) NOT NULL,
--     el_expression TEXT NOT NULL,
--     status SMALLINT NOT NULL DEFAULT 1,
--     route_group VARCHAR(64) NOT NULL DEFAULT 'DEFAULT',
--     description VARCHAR(500),
--     created_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
--     updated_time TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
-- );
-- CREATE INDEX IF NOT EXISTS idx_sys_liteflow_rule_status_route ON sys_liteflow_rule (status, route_group);

-- ------------------------------------------------------------------------------
-- 3. 示例测试数据
-- ------------------------------------------------------------------------------
-- INSERT INTO sys_liteflow_rule (rule_id, rule_name, el_expression, status, route_group, description)
-- VALUES
-- ('orderFulfillmentChain', '电商订单主流程编排', 'THEN(orderValidate, WHEN(inventoryDeduct, couponConsume), paymentProcess)', 1, 'DEFAULT', '高并发订单履约编排主链路'),
-- ('refundAuditChain', '售后退款决策审核流程', 'SWITCH(refundType).to(autoRefundNode, manualAuditNode)', 1, 'DEFAULT', '按退款类型动态分流处理');
