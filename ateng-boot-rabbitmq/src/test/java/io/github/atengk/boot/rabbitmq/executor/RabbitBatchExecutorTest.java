package io.github.atengk.boot.rabbitmq.executor;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.core.context.MessageContextPropagator;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.exception.IdempotentException;
import io.github.atengk.boot.rabbitmq.autoconfig.AtengRabbitmqAutoConfiguration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RabbitBatchExecutor 单元测试
 *
 * @author Ateng
 * @since 2026-11-15
 */
@DisplayName("RabbitBatchExecutor 单元测试")
class RabbitBatchExecutorTest {

    private RabbitBatchExecutor batchExecutor;
    private ListAppender<ILoggingEvent> logAppender;
    private Logger executorLogger;

    @BeforeEach
    void setUp() {
        MessageConverter converter = new AtengRabbitmqAutoConfiguration().messageConverter(
                new StaticListableBeanFactory().getBeanProvider(ObjectMapper.class));
        batchExecutor = new RabbitBatchExecutor(converter);

        executorLogger = (Logger) LoggerFactory.getLogger(RabbitBatchExecutor.class);
        logAppender = new ListAppender<>();
        logAppender.start();
        executorLogger.addAppender(logAppender);
    }

    @AfterEach
    void tearDown() {
        if (executorLogger != null && logAppender != null) {
            executorLogger.detachAppender(logAppender);
        }
        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("generateBatchTraceId 生成 batch- 前缀唯一追踪 ID")
    void shouldGenerateBatchTraceIdWithPrefix() {
        String id1 = RabbitBatchExecutor.generateBatchTraceId();
        String id2 = RabbitBatchExecutor.generateBatchTraceId();
        assertThat(id1).startsWith("batch-").isNotEqualTo(id2);
    }

    @Test
    @DisplayName("静态 runInContext 与 callInContext 在异常场景下仍 100% 清理与恢复四维上下文")
    void shouldIsolateAndRestoreContextOnSuccessAndFailure() {
        MDC.put(MessageContextPropagator.MDC_TRACE_ID_KEY, "outer-trace");
        TenantContextHolder.setTenantId("outer-tenant");
        UserContextHolder.set("outer-user");

        Message msg = buildJsonMessage("{\"orderId\":\"O-1\",\"amount\":99}", "trace-1", "tenant-1", "user-1", true, "O-1");

        AtomicReference<String> capturedContext = new AtomicReference<>();
        String callResult = RabbitBatchExecutor.callInContext(msg, m -> {
            capturedContext.set(MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY) + "|"
                    + TenantContextHolder.getTenantId() + "|"
                    + UserContextHolder.get() + "|"
                    + TenantContextHolder.isIgnoreTenant());
            return "CALL_OK";
        });

        assertThat(callResult).isEqualTo("CALL_OK");
        assertThat(capturedContext.get()).isEqualTo("trace-1|tenant-1|user-1|true");
        assertOuterContextRestored();

        assertThatThrownBy(() -> RabbitBatchExecutor.runInContext(msg, m -> {
            throw new IllegalStateException("消费处理异常");
        })).isInstanceOf(IllegalStateException.class);
        assertOuterContextRestored();

        RabbitBatchExecutor.runInContext(null, m -> {});
        String nullResult = RabbitBatchExecutor.callInContext(null, m -> "X");
        assertThat(nullResult).isNull();
    }

    @Test
    @DisplayName("Bean 重载支持无 __TypeId__ 头下的 Inferred Deserialization 强类型反序列化")
    void shouldDeserializeInferredTypeWithoutTypeIdHeader() {
        Message msg = buildJsonMessage("{\"orderId\":\"ORD-888\",\"amount\":128}",
                "trace-inferred", "tenant-inferred", "user-inferred", false, "ORD-888");
        assertThat(msg.getMessageProperties().getHeaders()).doesNotContainKey("__TypeId__");

        AtomicReference<String> observed = new AtomicReference<>();
        SampleOrderDto res = batchExecutor.callInContext(msg, SampleOrderDto.class, (rawMsg, dto) -> {
            observed.set(TenantContextHolder.getTenantId() + ":" + dto.orderId());
            return dto;
        });

        assertThat(res).isEqualTo(new SampleOrderDto("ORD-888", 128));
        assertThat(observed.get()).isEqualTo("tenant-inferred:ORD-888");
        assertThat(TenantContextHolder.getTenantId()).isNull();
    }

    @Test
    @DisplayName("processBatchSafely 在跨租户混排批次中逐条隔离上下文并防跨条串味")
    void shouldIsolateMultiTenantContextAcrossMixedBatchItems() {
        MDC.put(MessageContextPropagator.MDC_TRACE_ID_KEY, "outer-trace");
        TenantContextHolder.setTenantId("outer-tenant");
        UserContextHolder.set("outer-user");

        Message m1 = buildJsonMessage("{\"orderId\":\"O-A\",\"amount\":10}", "t-A", "tenant-A", "user-A", false, "O-A");
        Message m2 = buildJsonMessage("{\"orderId\":\"O-NONE\",\"amount\":20}", "t-NONE", null, null, false, "O-NONE");
        Message m3 = buildJsonMessage("{\"orderId\":\"O-B\",\"amount\":30}", "t-B", "tenant-B", "user-B", true, "O-B");

        List<String> snapshots = new ArrayList<>();
        batchExecutor.processBatchSafely(List.of(m1, m2, m3), SampleOrderDto.class, (msg, dto) -> {
            snapshots.add(dto.orderId() + "|" + MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY) + "|"
                    + TenantContextHolder.getTenantId() + "|" + UserContextHolder.get() + "|"
                    + TenantContextHolder.isIgnoreTenant());
        });

        assertThat(snapshots).containsExactly(
                "O-A|t-A|tenant-A|user-A|false",
                "O-NONE|t-NONE|null|null|false",
                "O-B|t-B|tenant-B|user-B|true"
        );
        assertOuterContextRestored();
    }

    @Test
    @DisplayName("processBatchSafely 对 IdempotentException/REPEAT_SUBMIT 输出 WARN 日志并静默跳过且不触发 failureHandler，对其余异常触发失败回调后继续执行")
    void shouldSilentSkipIdempotentDuplicatesAndIsolateNormalFailures() {
        Message ok1 = buildJsonMessage("{\"orderId\":\"O-1\",\"amount\":1}", "t-1", "ten-1", "u-1", false, "O-1");
        Message dupIdemp = buildJsonMessage("{\"orderId\":\"O-DUP1\",\"amount\":2}", "t-2", "ten-2", "u-2", false, "O-DUP1");
        Message dupCode = buildJsonMessage("{\"orderId\":\"O-DUP2\",\"amount\":3}", "t-3", "ten-3", "u-3", false, "O-DUP2");
        Message failMsg = buildJsonMessage("{\"orderId\":\"O-FAIL\",\"amount\":4}", "t-4", "ten-4", "u-4", false, "O-FAIL");
        Message ok2 = buildJsonMessage("{\"orderId\":\"O-2\",\"amount\":5}", "t-5", "ten-5", "u-5", false, "O-2");

        List<String> succeeded = new ArrayList<>();
        List<String> failedKeys = new ArrayList<>();

        batchExecutor.processBatchSafely(
                List.of(ok1, dupIdemp, dupCode, failMsg, ok2),
                SampleOrderDto.class,
                dto -> {
                    if ("O-DUP1".equals(dto.orderId())) {
                        throw new IdempotentException("重复消费-1");
                    }
                    if ("O-DUP2".equals(dto.orderId())) {
                        throw new BusinessException(CommonResultCode.REPEAT_SUBMIT, "重复消费-2");
                    }
                    if ("O-FAIL".equals(dto.orderId())) {
                        throw new IllegalStateException("常规业务失败");
                    }
                    succeeded.add(dto.orderId());
                },
                (failed, ex) -> {
                    failedKeys.add(MessageContextPropagator.extractString(
                            failed.getMessageProperties().getHeaders(), MessageHeaderConstants.BIZ_KEY));
                    throw new RuntimeException("failureHandler 次生异常不应中断后续批次");
                }
        );

        assertThat(succeeded).containsExactly("O-1", "O-2");
        assertThat(failedKeys).containsExactly("O-FAIL");

        List<ILoggingEvent> warnDuplicateLogs = logAppender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .filter(event -> event.getFormattedMessage().contains("[MQ-IDEMPOTENT-DUPLICATE]"))
                .toList();
        assertThat(warnDuplicateLogs).hasSize(2);
    }

    @Test
    @DisplayName("反序列化失败与类型不匹配均转译为 BusinessException 并保留底层 cause，且批次内毒丸隔离不污染外部上下文")
    void shouldWrapDeserializationAndTypeMismatchFailuresIntoBusinessException() {
        MDC.put(MessageContextPropagator.MDC_TRACE_ID_KEY, "outer-trace");
        TenantContextHolder.setTenantId("outer-tenant");
        UserContextHolder.set("outer-user");

        Message badJsonMsg = buildJsonMessage("{bad-json", "t-bad", "ten-bad", "u-bad", false, "BAD-1");
        assertThatThrownBy(() -> batchExecutor.runInContext(badJsonMsg, SampleOrderDto.class, dto -> {
        }))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(CommonResultCode.BAD_REQUEST.getCode());
                    assertThat(be.getCause()).isInstanceOf(MessageConversionException.class);
                });
        assertOuterContextRestored();

        MessageConverter mismatchConverter = new MessageConverter() {
            @Override
            public Message toMessage(Object object, MessageProperties messageProperties) {
                return badJsonMsg;
            }

            @Override
            public Object fromMessage(Message message) {
                return "unexpected-string-payload";
            }
        };
        RabbitBatchExecutor mismatchExecutor = new RabbitBatchExecutor(mismatchConverter);
        Message normalRaw = buildJsonMessage("{\"orderId\":\"O-X\",\"amount\":1}", "t-x", "ten-x", "u-x", false, "O-X");
        assertThatThrownBy(() -> mismatchExecutor.callInContext(normalRaw, SampleOrderDto.class, SampleOrderDto::orderId))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> {
                    BusinessException be = (BusinessException) ex;
                    assertThat(be.getCode()).isEqualTo(CommonResultCode.BAD_REQUEST.getCode());
                    assertThat(be.getCause()).isInstanceOf(MessageConversionException.class);
                });
        assertOuterContextRestored();

        Message ok1 = buildJsonMessage("{\"orderId\":\"OK-1\",\"amount\":10}", "t-1", "ten-1", "u-1", false, "OK-1");
        Message ok2 = buildJsonMessage("{\"orderId\":\"OK-2\",\"amount\":20}", "t-2", "ten-2", "u-2", false, "OK-2");
        List<String> processed = new ArrayList<>();
        List<Throwable> capturedCauses = new ArrayList<>();

        batchExecutor.processBatchSafely(
                List.of(ok1, badJsonMsg, ok2),
                SampleOrderDto.class,
                dto -> processed.add(dto.orderId() + "|" + TenantContextHolder.getTenantId()),
                (failed, ex) -> {
                    assertThat(failed).isSameAs(badJsonMsg);
                    assertThat(ex).isInstanceOf(BusinessException.class);
                    capturedCauses.add(ex.getCause());
                }
        );

        assertThat(processed).containsExactly("OK-1|ten-1", "OK-2|ten-2");
        assertThat(capturedCauses).hasSize(1);
        assertThat(capturedCauses.get(0)).isInstanceOf(MessageConversionException.class);
        assertOuterContextRestored();
    }

    @Test
    @DisplayName("空入参、null targetType 与 null MessageProperties 边界应 Fail-Fast 或安全放行")
    void shouldHandleNullBoundariesAndFailFastGuards() {
        assertThatThrownBy(() -> new RabbitBatchExecutor(null))
                .isInstanceOf(NullPointerException.class);

        Message validMsg = buildJsonMessage("{\"orderId\":\"O-V\",\"amount\":1}", "t-v", "ten-v", "u-v", false, "O-V");
        assertThatThrownBy(() -> batchExecutor.runInContext(validMsg, null, (Consumer<SampleOrderDto>) dto -> {
        })).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> batchExecutor.callInContext((Message) null, null, dto -> "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> batchExecutor.processBatchSafely(Collections.emptyList(), null, (Consumer<SampleOrderDto>) dto -> {
        })).isInstanceOf(IllegalArgumentException.class);

        RabbitBatchExecutor.runInContext((Message) null, msg -> {
        });
        RabbitBatchExecutor.runInContext(validMsg, null);
        Object nullMsgResult = RabbitBatchExecutor.callInContext((Message) null, msg -> "noop");
        assertThat(nullMsgResult).isNull();
        Object nullFuncResult = RabbitBatchExecutor.callInContext(validMsg, (java.util.function.Function<Message, Object>) null);
        assertThat(nullFuncResult).isNull();
        String typedNullResult = batchExecutor.callInContext((Message) null, SampleOrderDto.class, SampleOrderDto::orderId);
        assertThat(typedNullResult).isNull();

        Message mockNullPropsMsg = mock(Message.class);
        when(mockNullPropsMsg.getMessageProperties()).thenReturn(null);
        AtomicReference<String> nullPropsTenant = new AtomicReference<>("unset");
        RabbitBatchExecutor.runInContext(mockNullPropsMsg, msg -> nullPropsTenant.set(TenantContextHolder.getTenantId()));
        assertThat(nullPropsTenant.get()).isNull();

        Message emptyPropsMsg = new Message("{\"orderId\":\"O-NP\",\"amount\":9}".getBytes(StandardCharsets.UTF_8), new MessageProperties());
        List<String> processed = new ArrayList<>();
        batchExecutor.processBatchSafely((List<Message>) null, SampleOrderDto.class, dto -> processed.add(dto.orderId()));
        batchExecutor.processBatchSafely(Arrays.asList(validMsg, null, emptyPropsMsg), SampleOrderDto.class,
                dto -> processed.add(dto.orderId() + "|" + TenantContextHolder.getTenantId()));

        assertThat(processed).containsExactly("O-V|ten-v", "O-NP|null");
    }

    @Test
    @DisplayName("isIdempotentException 准确穿透深层因果链并排除非幂等 BusinessException")
    void shouldInspectCausalChainAccuratelyForIdempotency() {
        assertThat(RabbitBatchExecutor.isIdempotentException(null)).isFalse();
        assertThat(RabbitBatchExecutor.isIdempotentException(new IllegalArgumentException("plain"))).isFalse();
        assertThat(RabbitBatchExecutor.isIdempotentException(
                new BusinessException(CommonResultCode.BAD_REQUEST, "non-idempotent-code"))).isFalse();

        Throwable deepWrapped = new RuntimeException("outer",
                new IllegalStateException("middle",
                        new BusinessException(CommonResultCode.REPEAT_SUBMIT, "dup-in-chain")));
        assertThat(RabbitBatchExecutor.isIdempotentException(deepWrapped)).isTrue();
        assertThat(RabbitBatchExecutor.isIdempotentException(
                new RuntimeException(new IdempotentException("leaf")))).isTrue();
    }

    @Test
    @DisplayName("实例重载 runInContext / callInContext 强类型与双入参形态均保持四维隔离与还原")
    void shouldExecuteTypedAndBiOverloadsWithContextIsolation() {
        MDC.put(MessageContextPropagator.MDC_TRACE_ID_KEY, "outer-trace");
        TenantContextHolder.setTenantId("outer-tenant");
        UserContextHolder.set("outer-user");

        Message msg = buildJsonMessage("{\"orderId\":\"O-OVL\",\"amount\":77}", "t-ovl", "ten-ovl", "u-ovl", true, "O-OVL");

        AtomicReference<String> biRunResult = new AtomicReference<>();
        batchExecutor.runInContext(msg, SampleOrderDto.class, (raw, dto) -> biRunResult.set(
                raw.getMessageProperties().getHeader(MessageHeaderConstants.BIZ_KEY) + "|" + dto.orderId()
                        + "|" + TenantContextHolder.getTenantId() + "|" + TenantContextHolder.isIgnoreTenant()));
        assertThat(biRunResult.get()).isEqualTo("O-OVL|O-OVL|ten-ovl|true");
        assertOuterContextRestored();

        String singleCallResult = batchExecutor.callInContext(msg, SampleOrderDto.class,
                dto -> dto.orderId() + ":" + UserContextHolder.get());
        assertThat(singleCallResult).isEqualTo("O-OVL:u-ovl");
        assertOuterContextRestored();

        String biCallResult = batchExecutor.callInContext(msg, SampleOrderDto.class,
                (raw, dto) -> dto.amount() + ":" + MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY));
        assertThat(biCallResult).isEqualTo("77:t-ovl");
        assertOuterContextRestored();
    }

    @Test
    @DisplayName("多线程并发执行 processBatchSafely 时各线程四维上下文严格隔离互不串味")
    void shouldIsolateContextsUnderConcurrentExecution() throws Exception {
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(4);
        pool.setMaxPoolSize(4);
        pool.setThreadNamePrefix("batch-exec-test-");
        pool.initialize();
        try {
            int workerCount = 4;
            CountDownLatch ready = new CountDownLatch(workerCount);
            CountDownLatch start = new CountDownLatch(1);
            List<Future<List<String>>> futures = new ArrayList<>();

            for (int i = 0; i < workerCount; i++) {
                final int workerIndex = i;
                futures.add(pool.submit(() -> {
                    MDC.put(MessageContextPropagator.MDC_TRACE_ID_KEY, "thread-trace-" + workerIndex);
                    TenantContextHolder.setTenantId("thread-tenant-" + workerIndex);
                    UserContextHolder.set("thread-user-" + workerIndex);
                    try {
                        ready.countDown();
                        assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
                        Message m1 = buildJsonMessage("{\"orderId\":\"W" + workerIndex + "-1\",\"amount\":1}",
                                "tr-" + workerIndex + "-1", "ten-" + workerIndex + "-1", "usr-" + workerIndex + "-1", false, "K1");
                        Message m2 = buildJsonMessage("{\"orderId\":\"W" + workerIndex + "-2\",\"amount\":2}",
                                "tr-" + workerIndex + "-2", null, null, false, "K2");
                        List<String> records = new ArrayList<>();
                        batchExecutor.processBatchSafely(List.of(m1, m2), SampleOrderDto.class, dto -> records.add(
                                dto.orderId() + "|" + MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY) + "|"
                                        + TenantContextHolder.getTenantId() + "|" + UserContextHolder.get()));
                        assertThat(MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY)).isEqualTo("thread-trace-" + workerIndex);
                        assertThat(TenantContextHolder.getTenantId()).isEqualTo("thread-tenant-" + workerIndex);
                        assertThat(UserContextHolder.get()).isEqualTo("thread-user-" + workerIndex);
                        return records;
                    } finally {
                        MDC.clear();
                        TenantContextHolder.clear();
                        UserContextHolder.clear();
                    }
                }));
            }

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            for (int i = 0; i < workerCount; i++) {
                List<String> workerRecords = futures.get(i).get(5, TimeUnit.SECONDS);
                assertThat(workerRecords).containsExactly(
                        "W" + i + "-1|tr-" + i + "-1|ten-" + i + "-1|usr-" + i + "-1",
                        "W" + i + "-2|tr-" + i + "-2|null|null"
                );
            }
        } finally {
            pool.shutdown();
        }
    }

    private Message buildJsonMessage(String json, String traceId, String tenantId, String userId, boolean ignoreTenant, String bizKey) {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setContentEncoding(StandardCharsets.UTF_8.name());
        props.setReceivedExchange("sample.batch.direct");
        props.setReceivedRoutingKey("batch.tenant");
        props.setConsumerQueue("sample.batch.tenant.queue");
        if (traceId != null) {
            props.setHeader(MessageHeaderConstants.TRACE_ID, traceId);
        }
        if (tenantId != null) {
            props.setHeader(MessageHeaderConstants.TENANT_ID, tenantId);
        }
        if (userId != null) {
            props.setHeader(MessageHeaderConstants.USER_ID, userId);
        }
        if (ignoreTenant) {
            props.setHeader(MessageHeaderConstants.IGNORE_TENANT, "true");
        }
        if (bizKey != null) {
            props.setHeader(MessageHeaderConstants.BIZ_KEY, bizKey);
        }
        return new Message(json.getBytes(StandardCharsets.UTF_8), props);
    }

    private void assertOuterContextRestored() {
        assertThat(MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY)).isEqualTo("outer-trace");
        assertThat(TenantContextHolder.getTenantId()).isEqualTo("outer-tenant");
        assertThat(UserContextHolder.get()).isEqualTo("outer-user");
    }

    record SampleOrderDto(String orderId, Integer amount) {
    }
}
