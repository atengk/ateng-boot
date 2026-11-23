package io.github.atengk.boot.rabbitmq.builder;

/**
 * RabbitMQ 队列溢出策略枚举 (x-overflow)
 * 用于配置队列在达到最大消息条数 (x-max-length) 或最大字节容量 (x-max-length-bytes) 时的溢出丢弃与流控行为
 *
 * @author Ateng
 * @since 2026-10-29
 */
public enum OverflowStrategy {

    /**
     * 丢弃队头旧消息：当队列已满时，丢弃或死信流转队列头部最旧的消息，新消息正常入队 (RabbitMQ 默认行为)
     */
    DROP_HEAD("drop-head"),

    /**
     * 拒绝发布新消息：当队列已满时，直接拒绝接收新发布的消息，向生产者返回 Basic.Nack
     */
    REJECT_PUBLISH("reject-publish"),

    /**
     * 拒绝发布并流转死信：当队列已满时，拒绝接收新消息并将其直接死信路由至配置的 DLX 交换机 (面向 Quorum/Classic 队列)
     */
    REJECT_PUBLISH_DLX("reject-publish-dlx");

    private final String value;

    OverflowStrategy(String value) {
        this.value = value;
    }

    /**
     * 获取 RabbitMQ 底层参数配置字符串
     *
     * @return 底层策略配置标识
     */
    public String getValue() {
        return this.value;
    }

    @Override
    public String toString() {
        return this.value;
    }
}
