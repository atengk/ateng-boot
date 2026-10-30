package io.github.atengk.boot.sample.rocketmq.checker;

import io.github.atengk.boot.rocketmq.helper.RocketmqMessageHelper;
import io.github.atengk.boot.rocketmq.spi.RocketmqLocalTransactionChecker;
import io.github.atengk.boot.sample.rocketmq.producer.TradeRocketmqProducer;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.apache.rocketmq.client.apis.producer.TransactionResolution;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 订单领域本地事务回查器（实现 RocketmqLocalTransactionChecker SPI）
 *
 * <p>声明支持 {@link TradeRocketmqProducer#TOPIC_TX} 主题，Broker 回查时执行本地事务决断。</p>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
@Component
public class SampleOrderTransactionChecker implements RocketmqLocalTransactionChecker {

    private final Set<String> committedOrders = ConcurrentHashMap.newKeySet();
    private final Set<String> rolledBackOrders = ConcurrentHashMap.newKeySet();

    @Getter
    private final AtomicInteger checkCount = new AtomicInteger(0);

    @Override
    public String getTopic() {
        return TradeRocketmqProducer.TOPIC_TX;
    }

    @Override
    public TransactionResolution check(MessageView messageView) {
        checkCount.incrementAndGet();
        String bizKey = RocketmqMessageHelper.extractBizKey(messageView);
        log.info("[SAMPLE-TX-CHECKER] 收到 Broker 事务半消息回查: topic={}, bizKey={}", getTopic(), bizKey);

        if (committedOrders.contains(bizKey)) {
            log.info("[SAMPLE-TX-CHECKER] 本地事务已完成，决策 COMMIT: bizKey={}", bizKey);
            return TransactionResolution.COMMIT;
        }

        if (rolledBackOrders.contains(bizKey)) {
            log.info("[SAMPLE-TX-CHECKER] 本地事务已回滚，决策 ROLLBACK: bizKey={}", bizKey);
            return TransactionResolution.ROLLBACK;
        }

        log.warn("[SAMPLE-TX-CHECKER] 本地事务状态未决，决策 UNKNOWN: bizKey={}", bizKey);
        return TransactionResolution.UNKNOWN;
    }

    public void markCommitted(String orderId) {
        committedOrders.add(orderId);
    }

    public void markRolledBack(String orderId) {
        rolledBackOrders.add(orderId);
    }

    public boolean isCommitted(String orderId) {
        return committedOrders.contains(orderId);
    }

    public boolean isRolledBack(String orderId) {
        return rolledBackOrders.contains(orderId);
    }

    public void clear() {
        committedOrders.clear();
        rolledBackOrders.clear();
        checkCount.set(0);
    }
}
