package io.github.atengk.boot.rocketmq.spi;

/**
 * 本地数据库事务提交后发送 RocketMQ 消息失败兜底补偿 SPI
 *
 * <p>当 {@code sendAfterCommit} 或 {@code sendBatchAfterCommit} 在本地数据库事务物理 Commit 成功后，
 * 因网络抖动、Broker 宕机或超时导致发信失败时被回调。允许业务系统无缝落库本地补偿表或向外部监控告警中心上报，杜绝发信丢失。</p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@FunctionalInterface
public interface RocketmqTransactionFailureCallback {

    /**
     * 发送失败补偿回调（基础 4 参向后兼容契约）
     *
     * @param topic   目标主题
     * @param bizKey  业务唯一标识（保证 100% 非空）
     * @param payload 原始消息载荷
     * @param cause   底层异常原因
     */
    void onFailure(String topic, String bizKey, Object payload, Throwable cause);

    /**
     * 发送失败全上下文补偿回调（携带消息标签与全链路追踪标识，默认降级委派给 4 参方法保持向后兼容）
     *
     * @param topic   目标主题
     * @param tag     消息标签（可能为 null）
     * @param bizKey  业务唯一标识（保证 100% 非空）
     * @param payload 原始消息载荷
     * @param traceId 发起提交阶段捕获的链路追踪 ID（可能为 null）
     * @param cause   底层异常原因
     */
    default void onFailure(String topic, String tag, String bizKey, Object payload, String traceId, Throwable cause) {
        onFailure(topic, bizKey, payload, cause);
    }
}
