package io.github.atengk.boot.kafka.spi;

/**
 * 本地事务提交后发信失败兜底补偿 SPI
 *
 * <p>当调用 {@code sendAfterCommit} 在本地数据库事务物理 Commit 成功后，
 * 因网络闪断或 Broker 故障导致消息未能成功发送时触发回调，供企业将失败消息写入本地补偿表或发送至监控告警中心。</p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@FunctionalInterface
public interface KafkaTransactionFailureCallback {

    /**
     * 发信失败回调处理
     *
     * @param topic     目标主题
     * @param partition 指定分区（若未显式指定则为 null）
     * @param key       分区键
     * @param payload   消息载荷
     * @param bizKey    业务唯一标识
     * @param traceId   全链路追踪标识
     * @param cause     导致失败的根异常
     */
    void onFailure(String topic, Integer partition, String key, Object payload, String bizKey, String traceId, Throwable cause);
}
