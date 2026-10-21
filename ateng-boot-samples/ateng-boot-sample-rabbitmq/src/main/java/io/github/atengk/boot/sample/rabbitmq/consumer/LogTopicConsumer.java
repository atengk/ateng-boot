package io.github.atengk.boot.sample.rabbitmq.consumer;

import io.github.atengk.boot.sample.rabbitmq.config.RabbitScenarioTopologyConfig;
import io.github.atengk.boot.sample.rabbitmq.model.LogMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 层次通配日志消费者 (场景 A: Topic 路由实测)
 *
 * @author Ateng
 * @since 2026-10-21
 */
@Slf4j
@Component
public class LogTopicConsumer {

    private final List<LogMessage> receivedLogs = new CopyOnWriteArrayList<>();

    @RabbitListener(queues = RabbitScenarioTopologyConfig.QUEUE_LOG_ERROR)
    public void onErrorMessage(LogMessage message) {
        log.info("[CONSUMER-LOG] 收到系统错误日志: service={}, level={}, content={}",
                message.serviceName(), message.level(), message.content());
        receivedLogs.add(message);
    }

    public List<LogMessage> getReceivedLogs() {
        return receivedLogs;
    }

    public void clear() {
        receivedLogs.clear();
    }
}
