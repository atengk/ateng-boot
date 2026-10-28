package io.github.atengk.boot.web.audit;

import io.github.atengk.boot.core.audit.OperateLogModel;

/**
 * 结构化操作审计日志存储提供者 SPI
 * 允许业务模块或外部中间件（如数据库持久化、Elasticsearch、Kafka 等）统一接管审计日志流转
 *
 * @author Ateng
 * @since 2026-10-18
 */
@FunctionalInterface
public interface OperateLogStorageProvider {

    /**
     * 存储或派发一条操作审计记录
     *
     * @param logModel 结构化操作审计元数据模型
     */
    void store(OperateLogModel logModel);
}
