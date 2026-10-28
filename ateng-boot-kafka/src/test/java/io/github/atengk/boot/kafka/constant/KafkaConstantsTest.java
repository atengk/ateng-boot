package io.github.atengk.boot.kafka.constant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Kafka 基础常量单元测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("KafkaConstants 基础常量单元测试")
class KafkaConstantsTest {

    @Test
    @DisplayName("验证核心命名与宏标签常量")
    void testConstants() {
        assertEquals("ateng.boot.kafka", KafkaConstants.PROPERTY_PREFIX);
        assertEquals("kafkaListenerContainerFactory", KafkaConstants.DEFAULT_CONTAINER_FACTORY);
        assertEquals("batchKafkaListenerContainerFactory", KafkaConstants.BATCH_CONTAINER_FACTORY);
        assertEquals(".DLT", KafkaConstants.DEFAULT_DLT_SUFFIX);

        assertEquals("[MQ-SEND]", KafkaConstants.LOG_SEND_PREFIX);
        assertEquals("[MQ-CONSUME]", KafkaConstants.LOG_CONSUME_PREFIX);
        assertEquals("[MQ-CONSUME-BATCH]", KafkaConstants.LOG_CONSUME_BATCH_PREFIX);
        assertEquals("[MQ-DLT-FORWARD]", KafkaConstants.LOG_DLT_FORWARD_PREFIX);
        assertEquals("[MQ-IDEMPOTENT-DUPLICATE]", KafkaConstants.LOG_IDEMPOTENT_DUPLICATE_PREFIX);
        assertEquals("[MQ-TRANSACTION-POST-COMMIT-FAILED]", KafkaConstants.LOG_TX_POST_COMMIT_FAILED_PREFIX);
    }
}
