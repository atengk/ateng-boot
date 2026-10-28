package io.github.atengk.boot.rocketmq.checker;

import io.github.atengk.boot.core.context.MessageContextPropagator;
import io.github.atengk.boot.rocketmq.spi.RocketmqLocalTransactionChecker;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.apis.producer.TransactionChecker;
import org.apache.rocketmq.client.apis.producer.TransactionResolution;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * RocketMQ 5.x 全局统一事务回查分发器
 *
 * <p>作为原生 {@link org.apache.rocketmq.client.apis.producer.Producer} 的全局唯一 TransactionChecker 注册组件。
 * 容器启动时收集所有 {@link RocketmqLocalTransactionChecker} 业务 SPI 实现。当 Broker 触发事务半消息状态回查时，
 * 自动激活消息头中的四维上下文（MDC traceId、租户、用户等），依据 Topic 策略精准路由分发至对应业务领域回查器，
 * 执行完毕后严格保证上下文现场还原；若未匹配到任何处理器或执行异常，安全保底返回 {@link TransactionResolution#UNKNOWN}。</p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
public class RocketmqTransactionChecker implements TransactionChecker {

    private final List<RocketmqLocalTransactionChecker> checkers;

    public RocketmqTransactionChecker() {
        this.checkers = new CopyOnWriteArrayList<>();
    }

    public RocketmqTransactionChecker(List<RocketmqLocalTransactionChecker> checkers) {
        this.checkers = checkers != null ? new CopyOnWriteArrayList<>(checkers) : new CopyOnWriteArrayList<>();
    }

    public RocketmqTransactionChecker(ObjectProvider<RocketmqLocalTransactionChecker> checkerProvider) {
        this.checkers = new CopyOnWriteArrayList<>();
        if (checkerProvider != null) {
            checkerProvider.orderedStream().forEach(this.checkers::add);
        }
    }

    /**
     * 手动注册业务回查器
     *
     * @param checker 业务领域回查器 SPI
     */
    public void addChecker(RocketmqLocalTransactionChecker checker) {
        if (checker != null) {
            this.checkers.add(checker);
        }
    }

    /**
     * 获取当前已注册的回查器列表快照
     *
     * @return 业务回查器不可变列表
     */
    public List<RocketmqLocalTransactionChecker> getCheckers() {
        return Collections.unmodifiableList(this.checkers);
    }

    @Override
    public TransactionResolution check(MessageView messageView) {
        if (messageView == null) {
            log.warn("[MQ-TRANSACTION-CHECK] 收到空 MessageView 回查请求，安全保底返回 UNKNOWN");
            return TransactionResolution.UNKNOWN;
        }

        String topic = messageView.getTopic();
        String messageId = messageView.getMessageId() != null ? messageView.getMessageId().toString() : "UNKNOWN";

        // 激活四维上下文并确保在 finally 中确定性现场清理
        try (MessageContextPropagator.ContextScope ignored = MessageContextPropagator.activate(messageView.getProperties())) {
            RocketmqLocalTransactionChecker matchedChecker = null;
            for (RocketmqLocalTransactionChecker checker : checkers) {
                if (checker != null && checker.supports(topic)) {
                    matchedChecker = checker;
                    break;
                }
            }

            if (matchedChecker == null) {
                log.warn("[MQ-TRANSACTION-CHECK] 未找到匹配 Topic [{}] 的 RocketmqLocalTransactionChecker 处理器，安全保底返回 UNKNOWN (messageId={})",
                        topic, messageId);
                return TransactionResolution.UNKNOWN;
            }

            try {
                TransactionResolution resolution = matchedChecker.check(messageView);
                if (resolution == null) {
                    log.warn("[MQ-TRANSACTION-CHECK] 业务回查器 [{}] 返回决断为 null，保底降级为 UNKNOWN: topic={}, messageId={}",
                            matchedChecker.getClass().getName(), topic, messageId);
                    return TransactionResolution.UNKNOWN;
                }
                log.info("[MQ-TRANSACTION-CHECK] 事务消息回查决断成功: topic={}, resolution={}, messageId={}",
                        topic, resolution, messageId);
                return resolution;
            } catch (Throwable t) {
                log.error("[MQ-TRANSACTION-CHECK] 业务回查器 [{}] 执行回查异常，安全保底返回 UNKNOWN: topic={}, messageId={}",
                        matchedChecker.getClass().getName(), topic, messageId, t);
                return TransactionResolution.UNKNOWN;
            }
        }
    }
}
