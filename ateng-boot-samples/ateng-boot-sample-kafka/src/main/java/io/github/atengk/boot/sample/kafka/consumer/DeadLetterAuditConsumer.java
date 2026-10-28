package io.github.atengk.boot.sample.kafka.consumer;

import io.github.atengk.boot.kafka.constant.KafkaConstants;
import io.github.atengk.boot.sample.kafka.config.KafkaScenarioTopologyConfig;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 死信归档与审计监听器（监听各业务 Topic 的配套 .DLT 死信主题）
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
@Component
public class DeadLetterAuditConsumer {

    @Getter
    private final List<DeadLetterEntry> deadLetters = Collections.synchronizedList(new ArrayList<>());

    @KafkaListener(
            topics = {
                    KafkaScenarioTopologyConfig.TOPIC_ORDER + ".DLT",
                    KafkaScenarioTopologyConfig.TOPIC_POISON + ".DLT"
            },
            containerFactory = KafkaConstants.DEFAULT_CONTAINER_FACTORY
    )
    public void onDeadLetter(ConsumerRecord<Object, Object> record,
                             @Header(name = KafkaHeaders.RECEIVED_TOPIC, required = false) String topic,
                             @Header(name = KafkaHeaders.DLT_EXCEPTION_MESSAGE, required = false) byte[] exceptionMessageBytes) {
        String exceptionMessage = (exceptionMessageBytes != null) ? new String(exceptionMessageBytes, StandardCharsets.UTF_8) : null;
        log.warn("[DLT-AUDIT-RECEIVED] 收到死信消息: topic={}, key={}, payload={}, exceptionMessage={}",
                topic, record.key(), record.value(), exceptionMessage);

        deadLetters.add(new DeadLetterEntry(topic, record.key(), record.value(), exceptionMessage));
    }

    public void clear() {
        deadLetters.clear();
    }

    public record DeadLetterEntry(
            String topic,
            Object key,
            Object payload,
            String exceptionMessage
    ) {}
}
