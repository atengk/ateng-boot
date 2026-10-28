package io.github.atengk.boot.kafka.interceptor;

import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.core.context.MessageContextPropagator;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.header.internals.RecordHeaders;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Kafka 单条消费拦截器单元测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("KafkaRecordInterceptor 单条消费拦截器单元测试")
class KafkaRecordInterceptorTest {

    private final KafkaRecordInterceptor interceptor = new KafkaRecordInterceptor();

    @AfterEach
    void tearDown() {
        MDC.clear();
        TenantContextHolder.clear();
        UserContextHolder.clear();
    }

    @Test
    @DisplayName("消费入站成功还原四维上下文，并在 afterRecord 中确定性清理现场")
    void testContextActivationAndDeterministicCleanup() {
        // 1. 准备携带四维上下文的入站记录
        RecordHeaders headers = new RecordHeaders();
        headers.add(new RecordHeader(MessageHeaderConstants.TRACE_ID, "trace-custom-999".getBytes(StandardCharsets.UTF_8)));
        headers.add(new RecordHeader(MessageHeaderConstants.TENANT_ID, "tenant-test-88".getBytes(StandardCharsets.UTF_8)));
        headers.add(new RecordHeader(MessageHeaderConstants.USER_ID, "user-admin-01".getBytes(StandardCharsets.UTF_8)));
        headers.add(new RecordHeader(MessageHeaderConstants.IGNORE_TENANT, "true".getBytes(StandardCharsets.UTF_8)));
        headers.add(new RecordHeader(MessageHeaderConstants.BIZ_KEY, "biz-order-777".getBytes(StandardCharsets.UTF_8)));

        ConsumerRecord<Object, Object> record = new ConsumerRecord<>("order.topic", 0, 100L, "key-1", "order-data");
        headers.forEach(h -> record.headers().add(h));

        // 2. 模拟前置拦截
        ConsumerRecord<Object, Object> intercepted = interceptor.intercept(record);
        assertThat(intercepted).isSameAs(record);

        // 验证当前线程四维上下文已成功激活
        assertThat(MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY)).isEqualTo("trace-custom-999");
        assertThat(TenantContextHolder.getTenantId()).isEqualTo("tenant-test-88");
        assertThat(TenantContextHolder.isIgnoreTenant()).isTrue();
        assertThat(UserContextHolder.get()).isEqualTo("user-admin-01");

        // 3. 模拟消费成功回调
        interceptor.success(record, null);

        // 4. 模拟消费结束 finally 回调 afterRecord
        interceptor.afterRecord(record, null);

        // 验证当前现场已绝对可靠清理，零线程污染
        assertThat(MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY)).isNull();
        assertThat(TenantContextHolder.getTenantId()).isNull();
        assertThat(TenantContextHolder.isIgnoreTenant()).isFalse();
        assertThat(UserContextHolder.get()).isNull();
    }

    @Test
    @DisplayName("消费入站缺失 bizKey 时自适应使用 record.key 或生成 traceId 补齐")
    void testAutoSupplementBizKey() {
        ConsumerRecord<Object, Object> recordWithKey = new ConsumerRecord<>("test.topic", 1, 10L, "my-biz-key", "payload");
        interceptor.intercept(recordWithKey);

        // record.headers 应当被补齐 BIZ_KEY
        assertThat(recordWithKey.headers().lastHeader(MessageHeaderConstants.BIZ_KEY)).isNotNull();
        String bizKey = new String(recordWithKey.headers().lastHeader(MessageHeaderConstants.BIZ_KEY).value(), StandardCharsets.UTF_8);
        assertThat(bizKey).isEqualTo("my-biz-key");

        interceptor.afterRecord(recordWithKey, null);
        assertThat(MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY)).isNull();

        // 无 key 且无 Header 的情况
        ConsumerRecord<Object, Object> recordNoKey = new ConsumerRecord<>("test.topic", 1, 11L, null, "payload");
        interceptor.intercept(recordNoKey);
        assertThat(recordNoKey.headers().lastHeader(MessageHeaderConstants.BIZ_KEY)).isNotNull();
        String fallbackBizKey = new String(recordNoKey.headers().lastHeader(MessageHeaderConstants.BIZ_KEY).value(), StandardCharsets.UTF_8);
        assertThat(fallbackBizKey).isNotBlank();

        interceptor.afterRecord(recordNoKey, null);
        assertThat(MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY)).isNull();
    }

    @Test
    @DisplayName("消费失败时捕获异常并在 afterRecord 中正常记录日志与释放现场")
    void testFailureHandling() {
        ConsumerRecord<Object, Object> record = new ConsumerRecord<>("err.topic", 0, 5L, "k", "v");
        interceptor.intercept(record);

        // 模拟 listener 抛出异常
        interceptor.failure(record, new RuntimeException("Business process failed"), null);

        // 触发 finally afterRecord
        interceptor.afterRecord(record, null);

        // 现场彻底还原
        assertThat(MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY)).isNull();
        assertThat(TenantContextHolder.getTenantId()).isNull();
    }

    @Test
    @DisplayName("消费命中幂等异常时，识别为幂等跳过并在 afterRecord 中正常记录与排空现场")
    void testIdempotentExceptionHandling() {
        ConsumerRecord<Object, Object> record = new ConsumerRecord<>("idemp.topic", 0, 8L, "k-idemp", "v-idemp");
        interceptor.intercept(record);

        // 模拟 listener 抛出嵌套在封装异常中的 IdempotentException
        Exception wrappedEx = new RuntimeException("wrapped", new io.github.atengk.boot.core.exception.IdempotentException("业务重复消费"));
        interceptor.failure(record, wrappedEx, null);

        // 触发 finally afterRecord
        interceptor.afterRecord(record, null);

        // 现场彻底还原
        assertThat(MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY)).isNull();
        assertThat(TenantContextHolder.getTenantId()).isNull();
    }

    @Test
    @DisplayName("入参 record 为 null 时安全防卫")
    void testNullRecord() {
        ConsumerRecord<Object, Object> result = interceptor.intercept(null);
        assertThat(result).isNull();

        interceptor.afterRecord(null, null);
        assertThat(MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY)).isNull();
    }
}
