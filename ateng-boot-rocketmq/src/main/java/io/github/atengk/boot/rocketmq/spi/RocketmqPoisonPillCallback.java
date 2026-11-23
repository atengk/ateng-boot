package io.github.atengk.boot.rocketmq.spi;

import org.apache.rocketmq.client.apis.message.MessageView;

/**
 * RocketMQ 消费端不可自愈毒丸消息处置 SPI 扩展点
 *
 * <p>当 {@code @RocketmqListener} 捕获到 GZIP 解压失败、JSON 反序列化失败、JSR-303 参数校验违规或
 * {@link io.github.atengk.boot.core.helper.FatalExceptionClassifier} 判定的不可自愈毒丸异常时回调，
 * 配合可选死信归档实现“0 次重试阻断自旋 + 毒丸可追溯不丢信”。框架会对回调自身抛出的异常进行隔离告警，
 * 确保毒丸确认流程稳定收敛。</p>
 *
 * @author Ateng
 * @since 2026-11-15
 */
@FunctionalInterface
public interface RocketmqPoisonPillCallback {

    /**
     * 毒丸消息安全处置完成后触发通知回调
     *
     * @param messageView    触发毒丸拦截的原始入站消息视图
     * @param topic          原始订阅业务主题
     * @param consumerGroup  当前监听消费组名称
     * @param dlqTopic       归档目标死信主题（{@code %DLQ%<ConsumerGroup>}）
     * @param forwardedToDlq 是否已成功转储归档至死信主题（开关关闭、缺失可选 Producer 或转储失败时为 {@code false}）
     * @param cause          触发毒丸判定的异常根因
     */
    void onPoisonPill(
            MessageView messageView,
            String topic,
            String consumerGroup,
            String dlqTopic,
            boolean forwardedToDlq,
            Throwable cause);
}
