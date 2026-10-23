package io.github.atengk.boot.notification;

import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.notification.channel.NotificationChannel;
import io.github.atengk.boot.notification.channel.NotificationChannelRegistry;
import io.github.atengk.boot.notification.context.NotificationContextHolder;
import io.github.atengk.boot.notification.event.NotificationSendEvent;
import io.github.atengk.boot.notification.event.NotificationSendFailedEvent;
import io.github.atengk.boot.notification.event.NotificationSendingEvent;
import io.github.atengk.boot.notification.event.NotificationSentEvent;
import io.github.atengk.boot.notification.exception.NotificationRateLimitException;
import io.github.atengk.boot.notification.exception.NotificationSendException;
import io.github.atengk.boot.notification.handler.NotificationSendFailureHandler;
import io.github.atengk.boot.notification.limiter.InMemoryNotificationRateLimiter;
import io.github.atengk.boot.notification.model.CardMessage;
import io.github.atengk.boot.notification.model.MarkdownMessage;
import io.github.atengk.boot.notification.model.NotificationSendResult;
import io.github.atengk.boot.notification.model.Platform;
import io.github.atengk.boot.notification.model.TextMessage;
import io.github.atengk.boot.notification.properties.NotificationProperties;
import io.github.atengk.boot.notification.properties.NotificationRateLimitProperties;
import io.github.atengk.boot.notification.provider.DingTalkNotificationProvider;
import io.github.atengk.boot.notification.provider.FeishuNotificationProvider;
import io.github.atengk.boot.notification.provider.GenericWebhookNotificationProvider;
import io.github.atengk.boot.notification.provider.NotificationProvider;
import io.github.atengk.boot.notification.provider.WeComNotificationProvider;
import io.github.atengk.boot.notification.renderer.SimpleNotificationTemplateRenderer;
import io.github.atengk.boot.notification.template.NotificationTemplate;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 协同通知核心统一门面 NotificationTemplate 端到端单元测试
 * 覆盖同步发送、受管异步透传、事务感知发信、模板动态渲染与生命周期事件发布
 *
 * @author Ateng
 * @since 2026-10-23
 */
@DisplayName("NotificationTemplate 统一门面端到端集成测试")
class NotificationTemplateTest extends BaseNotificationTest {

    private NotificationProperties properties;
    private NotificationChannelRegistry registry;
    private InMemoryNotificationRateLimiter rateLimiter;
    private RestClient restClient;
    private List<NotificationProvider> providers;
    private SimpleNotificationTemplateRenderer templateRenderer;
    private List<ApplicationEvent> publishedEvents;
    private ApplicationEventPublisher eventPublisher;
    private ExecutorService asyncExecutor;
    private NotificationTemplate template;

    @BeforeEach
    void setUpTemplate() {
        properties = new NotificationProperties();
        properties.setDefaultChannel("default-dingtalk");

        registry = new NotificationChannelRegistry("default-dingtalk");
        NotificationChannel defaultChannel = NotificationChannel.builder()
                .name("default-dingtalk")
                .platform(Platform.DINGTALK)
                .webhook(getWebhookUrl("dingtalk/send"))
                .secret("test-secret")
                .permitsPerMinute(20)
                .build();
        NotificationChannel wecomChannel = NotificationChannel.builder()
                .name("wecom-alert")
                .platform(Platform.WECOM)
                .webhook(getWebhookUrl("wecom/send"))
                .permitsPerMinute(20)
                .build();
        NotificationChannel feishuChannel = NotificationChannel.builder()
                .name("feishu-alert")
                .platform(Platform.FEISHU)
                .webhook(getWebhookUrl("feishu/send"))
                .permitsPerMinute(100)
                .build();
        NotificationChannel genericChannel = NotificationChannel.builder()
                .name("generic-alert")
                .platform(Platform.GENERIC)
                .webhook(getWebhookUrl("generic/send"))
                .permitsPerMinute(60)
                .build();

        registry.register(defaultChannel);
        registry.register(wecomChannel);
        registry.register(feishuChannel);
        registry.register(genericChannel);

        rateLimiter = new InMemoryNotificationRateLimiter(new NotificationRateLimitProperties());

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(2000);
        factory.setReadTimeout(3000);
        restClient = RestClient.builder().requestFactory(factory).build();

        providers = List.of(
                new DingTalkNotificationProvider(),
                new WeComNotificationProvider(),
                new FeishuNotificationProvider(),
                new GenericWebhookNotificationProvider()
        );

        templateRenderer = new SimpleNotificationTemplateRenderer();
        publishedEvents = new CopyOnWriteArrayList<>();
        eventPublisher = event -> {
            if (event instanceof ApplicationEvent appEvent) {
                publishedEvents.add(appEvent);
            }
        };

        this.asyncExecutor = Executors.newFixedThreadPool(4);
        template = new NotificationTemplate(
                properties,
                registry,
                providers,
                rateLimiter,
                restClient,
                templateRenderer,
                null,
                eventPublisher,
                this.asyncExecutor
        );
    }

    @AfterEach
    void tearDownContext() {
        if (this.asyncExecutor != null && !this.asyncExecutor.isShutdown()) {
            this.asyncExecutor.shutdownNow();
        }
        MDC.clear();
        UserContextHolder.clear();
        NotificationContextHolder.clear();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    @DisplayName("验证默认渠道同步发信成功、耗时统计与 Spring 完整生命周期事件触发")
    void testSendSuccessAndEventPublishing() throws InterruptedException {
        enqueueOkJson("{\"errcode\":0,\"errmsg\":\"ok\"}");

        TextMessage message = TextMessage.of("系统上线成功通知");
        NotificationSendResult result = template.send(message);

        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertNotNull(result.getMessageId());
        assertEquals("default-dingtalk", result.getChannel());
        assertEquals(Platform.DINGTALK, result.getPlatform());
        assertThat(result.getCostDuration().toNanos()).isPositive();
        assertThat(result.getRawResponse()).contains("\"errcode\":0");

        // 验证 MockWebServer 接收到的网络请求
        RecordedRequest recordedRequest = takeRequest();
        assertNotNull(recordedRequest);
        assertEquals("POST", recordedRequest.getMethod());
        assertTrue(recordedRequest.getPath().startsWith("/dingtalk/send?"));
        assertThat(recordedRequest.getBody().readUtf8()).contains("系统上线成功通知");

        // 验证生命周期事件
        assertEquals(2, publishedEvents.size());
        assertInstanceOf(NotificationSendingEvent.class, publishedEvents.get(0));
        assertInstanceOf(NotificationSentEvent.class, publishedEvents.get(1));

        NotificationSentEvent sentEvent = (NotificationSentEvent) publishedEvents.get(1);
        assertEquals(result, sentEvent.getResult());
        assertEquals("default-dingtalk", sentEvent.getChannel().getName());
    }

    @Test
    @DisplayName("验证指定命名渠道（企业微信）发信顺畅到达对应端点")
    void testSendWithNamedChannel() throws InterruptedException {
        enqueueOkJson("{\"errcode\":0,\"errmsg\":\"ok\"}");

        MarkdownMessage markdown = MarkdownMessage.of("版本发布", "### 发布公告\n已完成 1.0.0 部署");
        NotificationSendResult result = template.send("wecom-alert", markdown);

        assertNotNull(result);
        assertTrue(result.isSuccess());
        assertEquals("wecom-alert", result.getChannel());
        assertEquals(Platform.WECOM, result.getPlatform());

        RecordedRequest recordedRequest = takeRequest();
        assertNotNull(recordedRequest);
        assertEquals("/wecom/send", recordedRequest.getPath());
        assertThat(recordedRequest.getBody().readUtf8()).contains("发布公告");
    }

    @Test
    @DisplayName("验证受管异步发信 (sendAsync) 能够跨工作线程精准透传四维全链路上下文快照")
    void testSendAsyncWithContextPropagation() throws ExecutionException, InterruptedException {
        enqueueOkJson("{\"errcode\":0,\"errmsg\":\"ok\"}");

        // 设置当前主线程四维上下文
        MDC.put("traceId", "trace-async-99999");
        UserContextHolder.set("user-admin-88");

        AtomicReference<String> asyncMdcTraceId = new AtomicReference<>();
        AtomicReference<String> asyncUserId = new AtomicReference<>();

        template.setApplicationEventPublisher(event -> {
            if (event instanceof NotificationSendingEvent) {
                // 在异步工作线程执行事件监听时采集现场上下文
                asyncMdcTraceId.set(MDC.get("traceId"));
                asyncUserId.set(UserContextHolder.get());
            }
        });

        CompletableFuture<NotificationSendResult> future = template.sendAsync(TextMessage.of("异步任务通知"));
        NotificationSendResult result = future.get();

        assertNotNull(result);
        assertTrue(result.isSuccess());

        // 强断言：异步工作线程中 100% 精准还原了主线程的 TraceId 与 UserId 快照
        assertEquals("trace-async-99999", asyncMdcTraceId.get());
        assertEquals("user-admin-88", asyncUserId.get());
    }

    @Test
    @DisplayName("验证本地事务感知发信 (sendAfterCommit)：有活跃物理事务时延期至提交后执行")
    void testSendAfterCommitWithActiveTransaction() throws InterruptedException {
        enqueueOkJson("{\"errcode\":0,\"errmsg\":\"ok\"}");

        // 激活 Spring 事务同步器（模拟开启事务）
        TransactionSynchronizationManager.initSynchronization();

        TextMessage message = TextMessage.of("订单支付成功事务后通知");
        template.sendAfterCommit(message);

        // 事务尚未提交时，断言 MockWebServer 未收到任何网络请求
        assertEquals(0, server.getRequestCount());

        // 模拟物理事务成功提交 (afterCommit)
        for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
            sync.afterCommit();
        }

        // 验证在 afterCommit 触发后网络请求正式发出
        RecordedRequest request = takeRequest();
        assertNotNull(request);
        assertThat(request.getBody().readUtf8()).contains("订单支付成功事务后通知");
    }

    @Test
    @DisplayName("验证本地事务感知发信 (sendAfterCommit)：无活跃事务时平滑降级就地同步执行")
    void testSendAfterCommitWithoutTransactionFallback() throws InterruptedException {
        enqueueOkJson("{\"errcode\":0,\"errmsg\":\"ok\"}");

        assertFalse(TransactionSynchronizationManager.isSynchronizationActive());

        template.sendAfterCommit(TextMessage.of("就地同步退让通知"));

        RecordedRequest request = takeRequest();
        assertNotNull(request);
        assertThat(request.getBody().readUtf8()).contains("就地同步退让通知");
    }

    @Test
    @DisplayName("验证事务提交后发信异常时自动触发 NotificationSendFailureHandler 补偿 SPI")
    void testSendAfterCommitFailureTriggersFailureHandler() {
        // 返回 500 服务器内部错误
        server.enqueue(new okhttp3.mockwebserver.MockResponse().setResponseCode(500).setBody("Internal Server Error"));

        AtomicBoolean failureTriggered = new AtomicBoolean(false);
        AtomicReference<NotificationChannel> failedChannel = new AtomicReference<>();
        AtomicReference<Throwable> failedThrowable = new AtomicReference<>();

        NotificationSendFailureHandler customFailureHandler = (channel, msg, throwable) -> {
            failureTriggered.set(true);
            failedChannel.set(channel);
            failedThrowable.set(throwable);
        };

        NotificationTemplate customTemplate = new NotificationTemplate(
                properties, registry, providers, rateLimiter, restClient,
                templateRenderer, customFailureHandler, eventPublisher, null
        );

        customTemplate.sendAfterCommit(TextMessage.of("失败测试消息"));

        assertTrue(failureTriggered.get());
        assertEquals("default-dingtalk", failedChannel.get().getName());
        assertNotNull(failedThrowable.get());
        assertInstanceOf(NotificationSendException.class, failedThrowable.get());
    }

    @Test
    @DisplayName("验证基于字符串模板的 sendTemplate 动态插值与发送")
    void testSendTemplateString() throws InterruptedException {
        enqueueOkJson("{\"errcode\":0,\"errmsg\":\"ok\"}");

        String templateText = "【告警通知】服务 ${service} 发生异常，错误码: ${code}，请及时处理！";
        Map<String, Object> variables = Map.of("service", "order-center", "code", 5001);

        NotificationSendResult result = template.sendTemplate(templateText, variables);

        assertNotNull(result);
        assertTrue(result.isSuccess());

        RecordedRequest request = takeRequest();
        assertNotNull(request);
        assertThat(request.getBody().readUtf8())
                .contains("【告警通知】服务 order-center 发生异常，错误码: 5001，请及时处理！");
    }

    @Test
    @DisplayName("验证基于卡片领域消息 CardMessage 的 sendTemplate 复合动态插值与发送")
    void testSendTemplateCardMessage() throws InterruptedException {
        enqueueOkJson("{\"errcode\":0,\"errmsg\":\"ok\"}");

        CardMessage cardTemplate = new CardMessage("单号: ${orderNo}", "用户 ${username} 于 ${time} 支付完成");

        Map<String, Object> variables = Map.of(
                "orderNo", "ORD-20261023",
                "username", "张三",
                "time", "09:30:00"
        );

        NotificationSendResult result = template.sendTemplate("default-dingtalk", cardTemplate, variables);

        assertNotNull(result);
        assertTrue(result.isSuccess());

        RecordedRequest request = takeRequest();
        assertNotNull(request);
        String body = request.getBody().readUtf8();
        assertThat(body).contains("单号: ORD-20261023");
        assertThat(body).contains("用户 张三 于 09:30:00 支付完成");
    }

    @Test
    @DisplayName("验证频控超限时阻断物理发信并发布 NotificationSendFailedEvent 事件")
    void testSendRateLimitExceeded() {
        // 创建配额仅为 1 的限流渠道
        NotificationChannel strictChannel = NotificationChannel.builder()
                .name("strict-channel")
                .platform(Platform.DINGTALK)
                .webhook(getWebhookUrl("dingtalk/send"))
                .permitsPerMinute(1)
                .build();
        registry.register(strictChannel);

        enqueueOkJson("{\"errcode\":0,\"errmsg\":\"ok\"}");
        template.send("strict-channel", TextMessage.of("第1条顺利放行"));

        // 第 2 条耗尽，频控抛出 NotificationRateLimitException
        assertThatThrownBy(() -> template.send("strict-channel", TextMessage.of("第2条被频控阻断")))
                .isInstanceOf(NotificationRateLimitException.class);

        // 验证失败事件已发布
        assertThat(publishedEvents)
                .filteredOn(event -> event instanceof NotificationSendFailedEvent)
                .hasSize(1);
    }

    @Test
    @DisplayName("验证平台返回错误码时抛出 NotificationSendException 并保留原始响应与因果链")
    void testSendPlatformErrorThrowsException() {
        // 钉钉返回 IP 白名单受限 40035
        enqueueOkJson("{\"errcode\":40035,\"errmsg\":\"ip not in whitelist\"}");

        assertThatThrownBy(() -> template.send(TextMessage.of("测试 IP 受限")))
                .isInstanceOf(NotificationSendException.class)
                .satisfies(ex -> {
                    NotificationSendException sendEx = (NotificationSendException) ex;
                    assertEquals("default-dingtalk", sendEx.getChannelName());
                    assertEquals("DINGTALK", sendEx.getPlatform());
                    assertThat(sendEx.getRawResponse()).contains("40035");
                    assertThat(sendEx.getMessage()).contains("钉钉平台发信失败");
                });

        assertThat(publishedEvents)
                .filteredOn(event -> event instanceof NotificationSendFailedEvent)
                .hasSize(1);
    }

    @Test
    @DisplayName("验证多层级渠道路由解析优先级：显式入参 > ContextHolder > 默认渠道")
    void testResolveChannelNameHierarchy() {
        // 1. 显式入参最高优先级
        assertEquals("explicit-channel", template.resolveChannelName("explicit-channel"));

        // 2. 线程动态上下文优先于默认渠道
        NotificationContextHolder.setChannel("context-channel");
        try {
            assertEquals("context-channel", template.resolveChannelName(null));
            assertEquals("context-channel", template.resolveChannelName("  "));
        } finally {
            NotificationContextHolder.clear();
        }

        // 3. 无上下文时兜底默认渠道
        assertEquals("default-dingtalk", template.resolveChannelName(null));
    }
}
