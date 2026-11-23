package io.github.atengk.boot.mybatisplus.sqllog;

import io.github.atengk.boot.core.audit.SqlLogModel;

/**
 * 结构化物理 SQL 操作审计日志存储提供者 SPI 函数式契约接口
 * 允许业务模块或外部中间件（如数据库落库、Elasticsearch、Kafka、ClickHouse、日志中心等）
 * 统一接管物理执行 SQL 的操作审计记录流转与持久化。
 *
 * @author Ateng
 * @since 2026-11-06
 */
@FunctionalInterface
public interface SqlLogStorageProvider {

    /**
     * 存储或派发一条结构化物理 SQL 操作审计记录
     *
     * @param logModel 结构化物理 SQL 操作审计元数据模型
     */
    void store(SqlLogModel logModel);
}
