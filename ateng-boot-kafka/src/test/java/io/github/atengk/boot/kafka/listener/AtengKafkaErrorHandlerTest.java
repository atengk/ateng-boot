package io.github.atengk.boot.kafka.listener;

import io.github.atengk.boot.core.exception.IdempotentException;
import io.github.atengk.boot.kafka.properties.AtengKafkaProperties;
import jakarta.validation.ValidationException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.errors.SerializationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;
import org.springframework.util.backoff.FixedBackOff;

import java.util.function.BiFunction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Kafka 生产级错误处理器单元测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("AtengKafkaErrorHandler 毒丸快速分流与退避重试测试")
class AtengKafkaErrorHandlerTest {

    private final AtengKafkaProperties properties = new AtengKafkaProperties();
    private final ConsumerRecordRecoverer recoverer = mock(ConsumerRecordRecoverer.class);
    private final AtengKafkaErrorHandler errorHandler = new AtengKafkaErrorHandler(recoverer, properties);

    @Test
    @DisplayName("验证恢复后提交 Offset 与处理后确认配置生效")
    void testCommitRecoveredConfig() {
        assertThat(errorHandler.isCommitRecovered()).isTrue();
        assertThat(errorHandler.isAckAfterHandle()).isTrue();
    }

    @Test
    @DisplayName("不可自愈毒丸（参数非法、JSR校验失败、序列化失败）触发 0 次重试快速分流")
    @SuppressWarnings("unchecked")
    void testFatalPoisonPillTriggersZeroRetry() throws Exception {
        BiFunction<ConsumerRecord<?, ?>, Exception, BackOff> backOffFunction = getBackOffFunction(errorHandler);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("test.topic", 0, 1L, "k", "v");

        // 1. 参数异常
        BackOff b1 = backOffFunction.apply(record, new IllegalArgumentException("Invalid payload format"));
        assertThat(b1).isInstanceOf(FixedBackOff.class);
        assertThat(b1.start().nextBackOff()).isEqualTo(BackOffExecution.STOP);

        // 2. JSR-303 校验失败
        BackOff b2 = backOffFunction.apply(record, new ValidationException("User age cannot be negative"));
        assertThat(b2).isInstanceOf(FixedBackOff.class);
        assertThat(b2.start().nextBackOff()).isEqualTo(BackOffExecution.STOP);

        // 3. 消息转换异常
        BackOff b3 = backOffFunction.apply(record, new MessageConversionException("JSON parse error"));
        assertThat(b3).isInstanceOf(FixedBackOff.class);
        assertThat(b3.start().nextBackOff()).isEqualTo(BackOffExecution.STOP);

        // 4. 序列化异常
        BackOff b4 = backOffFunction.apply(record, new SerializationException("Corrupted bytes"));
        assertThat(b4).isInstanceOf(FixedBackOff.class);
        assertThat(b4.start().nextBackOff()).isEqualTo(BackOffExecution.STOP);
    }

    @Test
    @DisplayName("IdempotentException 幂等异常触发 0 次重试，交由 Recoverer 静默提交")
    @SuppressWarnings("unchecked")
    void testIdempotentExceptionTriggersZeroRetry() throws Exception {
        BiFunction<ConsumerRecord<?, ?>, Exception, BackOff> backOffFunction = getBackOffFunction(errorHandler);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("test.topic", 0, 1L, "k", "v");

        BackOff backOff = backOffFunction.apply(record, new IdempotentException("Duplicate key"));
        assertThat(backOff).isInstanceOf(FixedBackOff.class);
        assertThat(backOff.start().nextBackOff()).isEqualTo(BackOffExecution.STOP);
    }

    @Test
    @DisplayName("瞬态业务异常触发本地指数退避重试（默认 3 次：1s, 2s, 4s）")
    @SuppressWarnings("unchecked")
    void testTransientExceptionTriggersExponentialBackOff() throws Exception {
        BiFunction<ConsumerRecord<?, ?>, Exception, BackOff> backOffFunction = getBackOffFunction(errorHandler);
        ConsumerRecord<String, String> record = new ConsumerRecord<>("test.topic", 0, 1L, "k", "v");

        Exception transientEx = new RuntimeException("Database connection timeout temporarily");
        BackOff backOff = backOffFunction.apply(record, transientEx);

        assertThat(backOff).isNotInstanceOf(FixedBackOff.class);
        BackOffExecution execution = backOff.start();

        // 第一次重试: 1000ms
        assertThat(execution.nextBackOff()).isEqualTo(1000L);
        // 第二次重试: 2000ms (1000 * 2.0)
        assertThat(execution.nextBackOff()).isEqualTo(2000L);
        // 第三次重试: 4000ms (2000 * 2.0)
        assertThat(execution.nextBackOff()).isEqualTo(4000L);
        // 第四次: 达到最大 3 次上限，停止重试
        assertThat(execution.nextBackOff()).isEqualTo(BackOffExecution.STOP);
    }

    private BiFunction<ConsumerRecord<?, ?>, Exception, BackOff> getBackOffFunction(AtengKafkaErrorHandler handler) {
        return handler.getCustomBackOffFunction();
    }
}
