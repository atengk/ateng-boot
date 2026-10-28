package io.github.atengk.boot.rocketmq.spi;

/**
 * 本地数据库事务提交后发送 RocketMQ 消息失败兜底补偿 SPI
 *
 * <p>当 {@code sendAfterCommit} 在本地数据库事务物理 Commit 成功后，因网络抖动、Broker 宕机或超时导致发信失败时被回调。
 * 允许业务系统无缝落库本地补偿表或向外部监控告警中心上报，杜绝发信丢失。</p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@FunctionalInterface
public interface RocketmqTransactionFailureCallback {

    /**
     * 发送失败补偿回调
     *
     * @param topic   目标主题
     * @param bizKey  业务唯一标识（保证 100% 非空）
     * @param payload 原始消息载荷
     * @param cause   底层异常原因
     */
    void onFailure(String topic, String bizKey, Object payload, Throwable cause);
}
