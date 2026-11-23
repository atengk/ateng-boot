package io.github.atengk.boot.notification;

import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.notification.alert.AlertContext;
import io.github.atengk.boot.notification.alert.AlertStormSuppressor;
import io.github.atengk.boot.notification.annotation.NotifyAlert;
import io.github.atengk.boot.notification.aspect.NotifyAlertAspect;
import io.github.atengk.boot.notification.exception.NotificationResultCode;
import io.github.atengk.boot.notification.properties.NotificationAlertProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.aop.aspectj.annotation.AspectJProxyFactory;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 声明式异常告警切面 NotifyAlertAspect 单元测试
 * 覆盖参数安全脱敏、TraceId 采集、异常类型过滤以及绝不吞咽异常不变量
 *
 * @author Ateng
 * @since 2026-10-23
 */
@DisplayName("NotifyAlertAspect 声明式切面单元测试")
class NotifyAlertAspectTest {

    private AlertStormSuppressor mockSuppressor;
    private NotificationAlertProperties alertProperties;
    private NotifyAlertAspect aspect;
    private TestAlertService proxy;

    @BeforeEach
    void setUp() {
        mockSuppressor = mock(AlertStormSuppressor.class);
        alertProperties = new NotificationAlertProperties();
        alertProperties.setEnabled(true);
        alertProperties.setIncludeArgs(true);
        alertProperties.setSuppressWindow(Duration.ofSeconds(60));

        aspect = new NotifyAlertAspect(mockSuppressor, alertProperties);

        TestAlertService target = new TestAlertService();
        AspectJProxyFactory factory = new AspectJProxyFactory(target);
        factory.addAspect(aspect);
        proxy = factory.getProxy();

        MDC.put("traceId", "trace-aspect-8888");
        UserContextHolder.set("operator-user-1");
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        UserContextHolder.clear();
    }

    @Test
    @DisplayName("验证切面拦截异常、安全脱敏敏感入参并正确装配 AlertContext（异常正常向外抛出）")
    void testAspectInterceptsAndDesensitizesArgs() {
        assertThatThrownBy(() -> proxy.placeOrder("ORD-10086", "super-secret-password", 299.9))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("库存不足");

        ArgumentCaptor<AlertContext> captor = ArgumentCaptor.forClass(AlertContext.class);
        verify(mockSuppressor, times(1)).processAlert(captor.capture());

        AlertContext context = captor.getValue();
        assertNotNull(context);
        assertEquals("order-channel", context.getChannel());
        assertEquals("用户下单异常", context.getTitle());
        assertThat(context.getMethodSignature()).contains("TestAlertService#placeOrder");
        assertEquals("java.lang.IllegalStateException", context.getExceptionClassName());
        assertEquals("库存不足", context.getExceptionMessage());
        assertEquals("trace-aspect-8888", context.getTraceId());
        assertEquals("operator-user-1", context.getUserId());

        // 强断言敏感参数密码脱敏为 ******，且普通入参 ORD-10086 保留
        assertNotNull(context.getDesensitizedArgs());
        assertThat(context.getDesensitizedArgs())
                .contains("\"password\":\"******\"")
                .contains("\"orderId\":\"ORD-10086\"")
                .doesNotContain("super-secret-password");

        // 核心调用栈包含当前方法
        assertThat(context.getTopStackFrames()).contains("TestAlertService.placeOrder");
    }

    @Test
    @DisplayName("验证 ignoreExceptions 忽略指定业务异常不触发告警")
    void testIgnoreExceptions() {
        // 抛出在忽略名单中的 BusinessException -> 不触发告警
        assertThatThrownBy(() -> proxy.withIgnore(true))
                .isInstanceOf(BusinessException.class);
        verify(mockSuppressor, never()).processAlert(any());

        // 抛出不在忽略名单中的 RuntimeException -> 正常触发告警
        assertThatThrownBy(() -> proxy.withIgnore(false))
                .isInstanceOf(RuntimeException.class);
        verify(mockSuppressor, times(1)).processAlert(any());
    }

    @Test
    @DisplayName("验证 includeExceptions 仅对白名单异常类型触发告警")
    void testIncludeExceptions() {
        // 抛出不在白名单的 IllegalStateException -> 不触发告警
        assertThatThrownBy(() -> proxy.withInclude(false))
                .isInstanceOf(IllegalStateException.class);
        verify(mockSuppressor, never()).processAlert(any());

        // 抛出在白名单的 IllegalArgumentException -> 正常触发告警
        assertThatThrownBy(() -> proxy.withInclude(true))
                .isInstanceOf(IllegalArgumentException.class);
        verify(mockSuppressor, times(1)).processAlert(any());
    }

    @Test
    @DisplayName("验证 includeArgs=false 时不采集方法调用入参")
    void testIncludeArgsFalse() {
        assertThatThrownBy(() -> proxy.noArgs("my-top-secret"))
                .isInstanceOf(RuntimeException.class);

        ArgumentCaptor<AlertContext> captor = ArgumentCaptor.forClass(AlertContext.class);
        verify(mockSuppressor, times(1)).processAlert(captor.capture());

        assertNull(captor.getValue().getDesensitizedArgs());
    }

    @Test
    @DisplayName("验证全局 enabled=false 时切面直接跳过拦截")
    void testDisabledAspect() {
        alertProperties.setEnabled(false);

        assertThatThrownBy(() -> proxy.placeOrder("ORD-001", "pwd", 10.0))
                .isInstanceOf(IllegalStateException.class);

        verify(mockSuppressor, never()).processAlert(any());
    }

    @Test
    @DisplayName("验证非 JSON 格式（如 Map.toString 降级场景）敏感参数依然能够被严密脱敏")
    void testMaskSensitiveWithMapFormat() {
        assertThatThrownBy(() -> proxy.withComplexMap(java.util.Map.of("password", "real-password-123", "secret", "my-secret-key")))
                .isInstanceOf(RuntimeException.class);

        ArgumentCaptor<AlertContext> captor = ArgumentCaptor.forClass(AlertContext.class);
        verify(mockSuppressor).processAlert(captor.capture());
        AlertContext context = captor.getValue();

        assertThat(context.getDesensitizedArgs()).doesNotContain("real-password-123");
        assertThat(context.getDesensitizedArgs()).doesNotContain("my-secret-key");
        assertThat(context.getDesensitizedArgs()).contains("\"******\"");
    }

    @Test
    @DisplayName("验证 @NotifyAlert(detailUrl) 优先级高于全局配置并支持 ${traceId} 插值及汇总上下文还原")
    void testDetailUrlPriorityAndEndToEndStormContextRestore() throws InterruptedException {
        alertProperties.setDetailUrl("https://global-trace.example.com/view/${traceId}");

        // 1. 注解显式配置 detailUrl 优先覆盖全局配置并替换 ${traceId}
        assertThatThrownBy(() -> proxy.withDetailUrl())
                .isInstanceOf(RuntimeException.class);
        ArgumentCaptor<AlertContext> explicitCaptor = ArgumentCaptor.forClass(AlertContext.class);
        verify(mockSuppressor, times(1)).processAlert(explicitCaptor.capture());
        assertEquals("https://custom-apm.example.com/trace/trace-aspect-8888",
                explicitCaptor.getValue().getDetailUrl());

        // 2. 注解未配置 detailUrl 时平滑回退全局配置并插值 ${traceId}
        assertThatThrownBy(() -> proxy.placeOrder("ORD-2", "pwd", 10.0))
                .isInstanceOf(IllegalStateException.class);
        ArgumentCaptor<AlertContext> fallbackCaptor = ArgumentCaptor.forClass(AlertContext.class);
        verify(mockSuppressor, times(2)).processAlert(fallbackCaptor.capture());
        assertEquals("https://global-trace.example.com/view/trace-aspect-8888",
                fallbackCaptor.getValue().getDetailUrl());

        // 3. 串联真实 AlertStormSuppressor（受管 TaskScheduler）端到端验证阶段三汇总卡片携带详情按钮及四维上下文
        io.github.atengk.boot.notification.template.NotificationTemplate mockTemplate =
                mock(io.github.atengk.boot.notification.template.NotificationTemplate.class);
        java.util.List<io.github.atengk.boot.notification.model.CardMessage> dispatchedCards =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.atomic.AtomicReference<String> summaryMdcTrace = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> summaryUserId = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.CountDownLatch summaryLatch = new java.util.concurrent.CountDownLatch(1);

        when(mockTemplate.send(any(), any())).thenAnswer(inv -> {
            dispatchedCards.add((io.github.atengk.boot.notification.model.CardMessage) inv.getArgument(1));
            return io.github.atengk.boot.notification.model.NotificationSendResult.success(
                    "m1", "order-channel", io.github.atengk.boot.notification.model.Platform.DINGTALK, Duration.ofMillis(1), "ok");
        });
        when(mockTemplate.sendAsync(any(), any())).thenAnswer(inv -> {
            summaryMdcTrace.set(MDC.get("traceId"));
            summaryUserId.set(UserContextHolder.get());
            dispatchedCards.add((io.github.atengk.boot.notification.model.CardMessage) inv.getArgument(1));
            summaryLatch.countDown();
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        });

        org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler realScheduler =
                new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        realScheduler.setPoolSize(1);
        realScheduler.setThreadNamePrefix("ateng-notif-alert-");
        realScheduler.initialize();
        NotificationAlertProperties shortWindowProps = new NotificationAlertProperties();
        shortWindowProps.setSuppressWindow(Duration.ofMillis(80));
        shortWindowProps.setDetailUrl("https://global-trace.example.com/view/${traceId}");
        AlertStormSuppressor realSuppressor = new AlertStormSuppressor(mockTemplate, shortWindowProps, realScheduler);
        try {
            TestAlertService target = new TestAlertService();
            AspectJProxyFactory factory = new AspectJProxyFactory(target);
            factory.addAspect(new NotifyAlertAspect(realSuppressor, shortWindowProps));
            TestAlertService realProxy = factory.getProxy();

            // 连续触发 2 次相同异常，立即清空主线程上下文模拟请求结束
            assertThatThrownBy(realProxy::withDetailUrl).isInstanceOf(RuntimeException.class);
            assertThatThrownBy(realProxy::withDetailUrl).isInstanceOf(RuntimeException.class);
            MDC.clear();
            UserContextHolder.clear();

            assertTrue(summaryLatch.await(3, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals("trace-aspect-8888", summaryMdcTrace.get());
            assertEquals("operator-user-1", summaryUserId.get());
            assertEquals(2, dispatchedCards.size());
            assertEquals("查看链路详情", dispatchedCards.get(0).getSingleActionText());
            assertEquals("https://custom-apm.example.com/trace/trace-aspect-8888", dispatchedCards.get(0).getSingleActionUrl());
            assertEquals("查看链路详情", dispatchedCards.get(1).getSingleActionText());
            assertEquals("https://custom-apm.example.com/trace/trace-aspect-8888", dispatchedCards.get(1).getSingleActionUrl());
        } finally {
            realSuppressor.destroy();
            realScheduler.destroy();
        }
    }

    static class TestAlertService {

        @NotifyAlert(title = "用户下单异常", channel = "order-channel")
        public void placeOrder(String orderId, String password, double amount) {
            throw new IllegalStateException("库存不足");
        }

        @NotifyAlert(ignoreExceptions = {BusinessException.class})
        public void withIgnore(boolean throwBiz) {
            if (throwBiz) {
                throw new BusinessException(NotificationResultCode.NOTIFICATION_SEND_ERROR, "忽略业务异常");
            }
            throw new RuntimeException("系统崩溃异常");
        }

        @NotifyAlert(includeExceptions = {IllegalArgumentException.class})
        public void withInclude(boolean throwLegal) {
            if (throwLegal) {
                throw new IllegalArgumentException("参数异常");
            }
            throw new IllegalStateException("状态异常");
        }

        @NotifyAlert(includeArgs = false)
        public void noArgs(String secret) {
            throw new RuntimeException("无入参测试");
        }

        @NotifyAlert(title = "复杂 Map 入参测试")
        public void withComplexMap(java.util.Map<String, Object> params) {
            throw new RuntimeException("Map 入参异常测试");
        }

        @NotifyAlert(title = "带链路跳转告警", detailUrl = "https://custom-apm.example.com/trace/${traceId}", suppressWindow = 1)
        public void withDetailUrl() {
            throw new RuntimeException("链路跳转测试异常");
        }
    }
}
