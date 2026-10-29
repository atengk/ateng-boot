package io.github.atengk.boot.rabbitmq.interceptor;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.core.context.MessageContextPropagator;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.exception.IdempotentException;
import io.github.atengk.boot.rabbitmq.properties.RabbitmqProperties;
import jakarta.validation.ValidationException;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * RabbitListenerGlobalAdvice 消费端全局环绕拦截链单元测试
 * 验证四维上下文入站激活与确定性清理、@Idempotent 静默确认 (Silent ACK) 与宏日志
 *
 * @author Ateng
 * @since 2026-10-21
 */
@DisplayName("RabbitListenerGlobalAdvice 消费端全局拦截链单元测试")
class RabbitListenerGlobalAdviceTest {

    private RabbitmqProperties properties;
    private RabbitListenerGlobalAdvice advice;

    @BeforeEach
    void setUp() {
        properties = new RabbitmqProperties();
        advice = new RabbitListenerGlobalAdvice(properties);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("四维上下文穿透与现场清理：在监听方法内安全激活并在 finally 中 100% 确定性恢复现场")
    void testContextPropagationAndDeterministicCleanup() throws Throwable {
        MDC.put(MessageContextPropagator.MDC_TRACE_ID_KEY, "outer-trace");
        UserContextHolder.set("outer-user");
        TenantContextHolder.setTenantId("outer-tenant");

        MessageProperties props = new MessageProperties();
        props.setHeader(MessageHeaderConstants.TRACE_ID, "inbound-trace-888");
        props.setHeader(MessageHeaderConstants.USER_ID, "inbound-user-999");
        props.setHeader(MessageHeaderConstants.TENANT_ID, "inbound-tenant-777");
        props.setHeader(MessageHeaderConstants.IGNORE_TENANT, "true");
        props.setHeader(MessageHeaderConstants.BIZ_KEY, "inbound-biz-order-1");
        Message message = new Message("data".getBytes(StandardCharsets.UTF_8), props);

        MethodInvocation invocation = mock(MethodInvocation.class);
        when(invocation.getArguments()).thenReturn(new Object[]{message});

        AtomicBoolean methodExecuted = new AtomicBoolean(false);
        AtomicReference<String> innerTrace = new AtomicReference<>();
        AtomicReference<String> innerUser = new AtomicReference<>();
        AtomicReference<String> innerTenant = new AtomicReference<>();
        AtomicBoolean innerIgnoreTenant = new AtomicBoolean(false);

        when(invocation.proceed()).thenAnswer(inv -> {
            methodExecuted.set(true);
            innerTrace.set(MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY));
            innerUser.set(UserContextHolder.get());
            innerTenant.set(TenantContextHolder.getTenantId());
            innerIgnoreTenant.set(TenantContextHolder.isIgnoreTenant());
            return "SUCCESS_RESULT";
        });

        Object result = advice.invoke(invocation);

        assertThat(methodExecuted.get()).isTrue();
        assertThat(result).isEqualTo("SUCCESS_RESULT");

        // 验证方法内提取到的四维上下文
        assertThat(innerTrace.get()).isEqualTo("inbound-trace-888");
        assertThat(innerUser.get()).isEqualTo("inbound-user-999");
        assertThat(innerTenant.get()).isEqualTo("inbound-tenant-777");
        assertThat(innerIgnoreTenant.get()).isTrue();

        // 验证执行完成后外部现场 100% 确定性恢复
        assertThat(MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY)).isEqualTo("outer-trace");
        assertThat(UserContextHolder.get()).isEqualTo("outer-user");
        assertThat(TenantContextHolder.getTenantId()).isEqualTo("outer-tenant");
    }

    @Test
    @DisplayName("幂等冲突静默确认：抛出 IdempotentException 时捕获并返回 null，触发 AUTO 模式静默 ACK")
    void testIdempotentExceptionSilentAck() throws Throwable {
        MessageProperties props = new MessageProperties();
        props.setHeader(MessageHeaderConstants.BIZ_KEY, "biz-dup-1");
        props.setReceivedExchange("trade.exchange");
        props.setReceivedRoutingKey("trade.order");
        props.setConsumerQueue("trade.order.queue");
        Message message = new Message("dup-payload".getBytes(StandardCharsets.UTF_8), props);

        MethodInvocation invocation = mock(MethodInvocation.class);
        when(invocation.getArguments()).thenReturn(new Object[]{message});
        when(invocation.proceed()).thenThrow(new IdempotentException("订单已处理，请勿重复操作"));

        Object result = advice.invoke(invocation);

        // 验证返回 null，不向外抛出异常，实现静默 ACK
        assertThat(result).isNull();
    }

    @Test
    @DisplayName("幂等冲突静默确认：抛出 REPEAT_SUBMIT 状态码的 BusinessException 时同样静默 ACK")
    void testBusinessExceptionRepeatSubmitSilentAck() throws Throwable {
        MessageProperties props = new MessageProperties();
        props.setHeader(MessageHeaderConstants.BIZ_KEY, "biz-dup-2");
        Message message = new Message("dup-payload".getBytes(StandardCharsets.UTF_8), props);

        MethodInvocation invocation = mock(MethodInvocation.class);
        when(invocation.getArguments()).thenReturn(new Object[]{message});
        when(invocation.proceed()).thenThrow(new BusinessException(CommonResultCode.REPEAT_SUBMIT));

        Object result = advice.invoke(invocation);

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("常规可自愈业务异常：向外抛出以交由 RetryOperationsInterceptor 执行本地重试")
    void testRetryableExceptionRethrown() throws Throwable {
        MessageProperties props = new MessageProperties();
        props.setHeader(MessageHeaderConstants.BIZ_KEY, "biz-retry-1");
        Message message = new Message("payload".getBytes(StandardCharsets.UTF_8), props);

        MethodInvocation invocation = mock(MethodInvocation.class);
        when(invocation.getArguments()).thenReturn(new Object[]{message});
        when(invocation.proceed()).thenThrow(new IOException("数据库网络瞬间不可达"));

        assertThatThrownBy(() -> advice.invoke(invocation))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("数据库网络瞬间不可达");
    }

    @Test
    @DisplayName("毒丸不可自愈异常：向外抛出以交由 FatalExceptionStrategy 直接送入 DLQ")
    void testFatalExceptionRethrown() throws Throwable {
        MessageProperties props = new MessageProperties();
        props.setHeader(MessageHeaderConstants.BIZ_KEY, "biz-fatal-1");
        Message message = new Message("poison-payload".getBytes(StandardCharsets.UTF_8), props);

        MethodInvocation invocation = mock(MethodInvocation.class);
        when(invocation.getArguments()).thenReturn(new Object[]{message});
        when(invocation.proceed()).thenThrow(new ValidationException("用户名不能为空"));

        assertThatThrownBy(() -> advice.invoke(invocation))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("用户名不能为空");
    }

    @Test
    @DisplayName("批量消费拦截：List<Message> 入参安全激活首条消息四维上下文并在 finally 中 100% 确定性恢复现场")
    void testBatchMessagesContextPropagationAndCleanup() throws Throwable {
        MDC.put(MessageContextPropagator.MDC_TRACE_ID_KEY, "outer-batch-trace");
        UserContextHolder.set("outer-batch-user");
        TenantContextHolder.setTenantId("outer-batch-tenant");

        MessageProperties props1 = new MessageProperties();
        props1.setHeader(MessageHeaderConstants.TRACE_ID, "batch-trace-001");
        props1.setHeader(MessageHeaderConstants.USER_ID, "batch-user-001");
        props1.setHeader(MessageHeaderConstants.TENANT_ID, "batch-tenant-001");
        props1.setHeader(MessageHeaderConstants.BIZ_KEY, "biz-item-1");
        Message msg1 = new Message("payload-1".getBytes(StandardCharsets.UTF_8), props1);

        MessageProperties props2 = new MessageProperties();
        props2.setHeader(MessageHeaderConstants.TRACE_ID, "batch-trace-002");
        Message msg2 = new Message("payload-2".getBytes(StandardCharsets.UTF_8), props2);

        MethodInvocation invocation = mock(MethodInvocation.class);
        when(invocation.getArguments()).thenReturn(new Object[]{java.util.List.of(msg1, msg2)});

        AtomicBoolean methodExecuted = new AtomicBoolean(false);
        AtomicReference<String> innerTrace = new AtomicReference<>();
        AtomicReference<String> innerUser = new AtomicReference<>();
        AtomicReference<String> innerTenant = new AtomicReference<>();

        when(invocation.proceed()).thenAnswer(inv -> {
            methodExecuted.set(true);
            innerTrace.set(MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY));
            innerUser.set(UserContextHolder.get());
            innerTenant.set(TenantContextHolder.getTenantId());
            return "BATCH_SUCCESS";
        });

        Object result = advice.invoke(invocation);

        assertThat(methodExecuted.get()).isTrue();
        assertThat(result).isEqualTo("BATCH_SUCCESS");

        // 验证方法内提取到的四维上下文为首条消息的主上下文
        assertThat(innerTrace.get()).isEqualTo("batch-trace-001");
        assertThat(innerUser.get()).isEqualTo("batch-user-001");
        assertThat(innerTenant.get()).isEqualTo("batch-tenant-001");

        // 验证执行完成后外部现场 100% 确定性恢复
        assertThat(MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY)).isEqualTo("outer-batch-trace");
        assertThat(UserContextHolder.get()).isEqualTo("outer-batch-user");
        assertThat(TenantContextHolder.getTenantId()).isEqualTo("outer-batch-tenant");
    }

    @Test
    @DisplayName("批量消费拦截：List<?> POJO 入参无原生 Message 头时自适应生成链路 TraceId 且现场零污染")
    void testBatchPojoListContextHandling() throws Throwable {
        MethodInvocation invocation = mock(MethodInvocation.class);
        when(invocation.getArguments()).thenReturn(new Object[]{java.util.List.of("pojo-1", "pojo-2")});

        AtomicReference<String> generatedTrace = new AtomicReference<>();
        when(invocation.proceed()).thenAnswer(inv -> {
            generatedTrace.set(MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY));
            return "POJO_BATCH_OK";
        });

        Object result = advice.invoke(invocation);

        assertThat(result).isEqualTo("POJO_BATCH_OK");
        assertThat(generatedTrace.get()).isNotBlank(); // 自适应兜底生成唯一 TraceId

        // 执行后外部 MDC 恢复为空
        assertThat(MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY)).isNull();
    }

    @Test
    @DisplayName("批量消费拦截：批量处理过程中抛出 IdempotentException 触发整批静默确认 (Silent ACK)")
    void testBatchIdempotentExceptionSilentAck() throws Throwable {
        MessageProperties props = new MessageProperties();
        props.setHeader(MessageHeaderConstants.BIZ_KEY, "batch-dup-biz");
        Message msg = new Message("data".getBytes(StandardCharsets.UTF_8), props);

        MethodInvocation invocation = mock(MethodInvocation.class);
        when(invocation.getArguments()).thenReturn(new Object[]{java.util.List.of(msg)});
        when(invocation.proceed()).thenThrow(new IdempotentException("批次中已处理，触发静默 ACK"));

        Object result = advice.invoke(invocation);

        assertThat(result).isNull(); // 返回 null 触发 AUTO 模式静默确认
    }

    @Test
    @DisplayName("批量消费拦截：批量处理抛出可自愈异常时原样向外抛出供重试")
    void testBatchRetryableExceptionRethrown() throws Throwable {
        MessageProperties props = new MessageProperties();
        Message msg = new Message("data".getBytes(StandardCharsets.UTF_8), props);

        MethodInvocation invocation = mock(MethodInvocation.class);
        when(invocation.getArguments()).thenReturn(new Object[]{java.util.List.of(msg)});
        when(invocation.proceed()).thenThrow(new IOException("远程服务调用超时"));

        assertThatThrownBy(() -> advice.invoke(invocation))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("远程服务调用超时");
    }
}
