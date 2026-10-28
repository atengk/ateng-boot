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
}
