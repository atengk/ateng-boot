package io.github.atengk.boot.sample.kafka.consumer;

import io.github.atengk.boot.kafka.constant.KafkaConstants;
import io.github.atengk.boot.sample.kafka.config.KafkaScenarioTopologyConfig;
import jakarta.validation.ValidationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * 毒丸报文消费者（演示非法报文触发 Fatal 致命异常，0次重试快速分流至 DLT）
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
@Component
public class PoisonPillKafkaConsumer {

    @KafkaListener(
            topics = KafkaScenarioTopologyConfig.TOPIC_POISON,
            containerFactory = KafkaConstants.DEFAULT_CONTAINER_FACTORY
    )
    public void onPoisonMessage(@Payload String message) {
        log.info("[SAMPLE-POISON-RECEIVED] 收到报文: {}", message);
        if (message != null && message.contains("POISON")) {
            throw new ValidationException("报文格式非法，触发 JSR-303 致命校验异常: " + message);
        }
    }
}
