package io.github.atengk.boot.ai.observability;

/**
 * 大模型可观测审计记录外部持久化扩展点 SPI 接口
 * 供业务按需注入 Bean 实现，将脱敏后的结构化审计记录异步落库、投递 Kafka 或写入外部 ES 审计集群
 *
 * @author Ateng
 * @since 2026-11-04
 */
@FunctionalInterface
public interface AiAuditLogHandler {

    /**
     * 消费并处理结构化审计记录
     *
     * @param record 脱敏后的审计记录
     */
    void handle(AiAuditRecord record);
}
