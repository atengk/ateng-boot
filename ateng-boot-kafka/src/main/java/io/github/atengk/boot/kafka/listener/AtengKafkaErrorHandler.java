package io.github.atengk.boot.kafka.listener;

import io.github.atengk.boot.core.exception.IdempotentException;
import io.github.atengk.boot.core.helper.FatalExceptionClassifier;
import io.github.atengk.boot.kafka.properties.AtengKafkaProperties;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.errors.SerializationException;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.DeserializationException;
import org.springframework.messaging.converter.MessageConversionException;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.FixedBackOff;

import jakarta.validation.ValidationException;
import java.util.function.BiFunction;

/**
 * Kafka 生产级默认错误处理器
 *
 * <p>继承 {@link DefaultErrorHandler}，整合 Core 模块 {@link FatalExceptionClassifier} 与本地指数退避重试：
 * <ul>
 *   <li><b>毒丸消息 0 次重试</b>：反序列化失败、JSR-303 参数校验异常、类型转换异常等致命不可自愈错误执行 0 次重试，快速转移至死信 Topic；</li>
 *   <li><b>瞬态故障指数退避</b>：可自愈异常执行本地指数退避重试（默认 3 次，1s, 2s, 4s），重试耗尽后转移至死信 Topic；</li>
 *   <li><b>幂等异常 0 次重试</b>：捕获 {@link IdempotentException} 后直接由 Recoverer 静默提交 Offset，阻断重试。</li>
 * </ul>
 *
 * @author Ateng
 * @since 2026-10-22
 */
@Slf4j
public class AtengKafkaErrorHandler extends DefaultErrorHandler {

    private final BiFunction<ConsumerRecord<?, ?>, Exception, BackOff> customBackOffFunction;

    public AtengKafkaErrorHandler(ConsumerRecordRecoverer recoverer, AtengKafkaProperties properties) {
        super(recoverer, createExponentialBackOff(properties));

        // 1. 配置动态退避函数：结合 FatalExceptionClassifier 动态判定致命不可自愈毒丸
        ExponentialBackOffWithMaxRetries defaultBackOff = createExponentialBackOff(properties);
        this.customBackOffFunction = (record, exception) -> {
            if (isFatalOrIdempotent(exception)) {
                return new FixedBackOff(0L, 0L); // 0 次重试快速分流
            }
            return defaultBackOff;
        };
        setBackOffFunction(this.customBackOffFunction);

        // 2. 强类型静态注册不可自愈异常类型
        addNotRetryableExceptions(
                ValidationException.class,
                IllegalArgumentException.class,
                IllegalStateException.class,
                ClassCastException.class,
                NullPointerException.class,
                ClassNotFoundException.class,
                MessageConversionException.class,
                SerializationException.class,
                DeserializationException.class,
                IdempotentException.class
        );

        // 3. 确保恢复后提交已处理 Offset
        setCommitRecovered(true);
        setAckAfterHandle(true);
    }

    @Override
    public boolean isCommitRecovered() {
        return super.isCommitRecovered();
    }

    /**
     * 获取配置的退避决策函数
     */
    public BiFunction<ConsumerRecord<?, ?>, Exception, BackOff> getCustomBackOffFunction() {
        return this.customBackOffFunction;
    }

    private static boolean isFatalOrIdempotent(Exception exception) {
        if (exception == null) {
            return false;
        }
        return FatalExceptionClassifier.isFatal(exception) || isIdempotent(exception);
    }

    private static boolean isIdempotent(Throwable throwable) {
        Throwable current = throwable;
        int depth = 0;
        while (current != null && depth < 30) {
            if (current instanceof IdempotentException) {
                return true;
            }
            current = current.getCause();
            depth++;
        }
        return false;
    }

    private static ExponentialBackOffWithMaxRetries createExponentialBackOff(AtengKafkaProperties properties) {
        int maxRetries = (properties != null) ? properties.getConsumer().getMaxRetries() : 3;
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(maxRetries);
        if (properties != null) {
            backOff.setInitialInterval(properties.getConsumer().getRetryInterval().toMillis());
            backOff.setMultiplier(properties.getConsumer().getRetryMultiplier());
            // 最大退避上限 30s，防止溢出
            backOff.setMaxInterval(30000L);
        }
        return backOff;
    }
}
