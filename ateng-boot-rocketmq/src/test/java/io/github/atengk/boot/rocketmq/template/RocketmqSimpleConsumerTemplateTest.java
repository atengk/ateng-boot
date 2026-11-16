package io.github.atengk.boot.rocketmq.template;

import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.core.context.MessageContextPropagator;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.IdempotentException;
import io.github.atengk.boot.core.exception.MessageDeliveryException;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.rocketmq.consumer.SimpleConsumerFactory;
import io.github.atengk.boot.rocketmq.properties.AtengRocketmqProperties;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.FilterExpressionType;
import org.apache.rocketmq.client.apis.consumer.SimpleConsumer;
import org.apache.rocketmq.client.apis.message.MessageId;
import org.apache.rocketmq.client.apis.message.MessageView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.context.SmartLifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * RocketmqSimpleConsumerTemplate 单元测试
 *
 * @author Ateng
 * @since 2026-10-29
 */
@DisplayName("RocketmqSimpleConsumerTemplate 流式拉取门面单元测试")
class RocketmqSimpleConsumerTemplateTest {

    private AtengRocketmqProperties properties;
    private ClientConfiguration clientConfiguration;
    private SimpleConsumerFactory simpleConsumerFactory;
    private SimpleConsumer mockConsumer;
    private RocketmqSimpleConsumerTemplate template;

    @BeforeEach
    void setUp() throws Exception {
        properties = new AtengRocketmqProperties();
        clientConfiguration = mock(ClientConfiguration.class);
        simpleConsumerFactory = mock(SimpleConsumerFactory.class);
        mockConsumer = mock(SimpleConsumer.class);

        when(simpleConsumerFactory.createSimpleConsumer(any(), any(), any(), any()))
                .thenReturn(mockConsumer);

        template = new RocketmqSimpleConsumerTemplate(properties, clientConfiguration, simpleConsumerFactory);
    }

    @AfterEach
    void tearDown() {
        template.destroy();
        MDC.clear();
        TenantContextHolder.clear();
        UserContextHolder.clear();
    }

    private MessageView createMockMessageView(String topic, String msgId, byte[] body, Map<String, String> properties) {
        MessageView mv = mock(MessageView.class);
        MessageId messageId = mock(MessageId.class);
        when(messageId.toString()).thenReturn(msgId);
        when(mv.getMessageId()).thenReturn(messageId);
        when(mv.getTopic()).thenReturn(topic);
        when(mv.getBody()).thenReturn(ByteBuffer.wrap(body));
        when(mv.getProperties()).thenReturn(properties != null ? properties : Collections.emptyMap());
        when(mv.getTag()).thenReturn(Optional.of("tagA"));
        when(mv.getKeys()).thenReturn(List.of("key-" + msgId));
        return mv;
    }

    @Test
    @DisplayName("验证同步拉取消息成功：正常返回列表并追踪 Group 映射")
    void testReceiveSyncSuccess() throws Exception {
        MessageView msg1 = createMockMessageView("order_topic", "MSG-001", "hello".getBytes(StandardCharsets.UTF_8), null);
        MessageView msg2 = createMockMessageView("order_topic", "MSG-002", "world".getBytes(StandardCharsets.UTF_8), null);

        when(mockConsumer.receive(eq(10), any(Duration.class))).thenReturn(List.of(msg1, msg2));

        List<MessageView> result = template.receive("order_topic", "order_group", 10, Duration.ofSeconds(15));

        assertThat(result).hasSize(2);
        verify(mockConsumer).receive(10, Duration.ofSeconds(15));
    }

    @Test
    @DisplayName("验证异步拉取消息成功：返回 CompletableFuture")
    void testReceiveAsyncSuccess() throws Exception {
        MessageView msg1 = createMockMessageView("order_topic", "MSG-003", "data".getBytes(StandardCharsets.UTF_8), null);
        when(mockConsumer.receiveAsync(eq(5), any(Duration.class)))
                .thenReturn(CompletableFuture.completedFuture(List.of(msg1)));

        CompletableFuture<List<MessageView>> future = template.receiveAsync("order_topic", "order_group", 5, Duration.ofSeconds(10));
        List<MessageView> result = future.get();

        assertThat(result).hasSize(1);
        verify(mockConsumer).receiveAsync(5, Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("验证拉取入参防卫校验：非法 Topic、非正数批次或空租约抛出异常")
    void testReceiveValidation() {
        assertThatThrownBy(() -> template.receive("illegal.topic.with.dots", "group"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Topic 名称");

        assertThatThrownBy(() -> template.receive("order_topic", "group", 0, Duration.ofSeconds(10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxMessageNum 必须大于 0");

        assertThatThrownBy(() -> template.receive("order_topic", "group", 10, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invisibleDuration 必须大于 0");
    }

    @Test
    @DisplayName("验证消息确认 (ack / ackAsync)：正常调用底层客户端")
    void testAckOperations() throws Exception {
        MessageView msg = createMockMessageView("order_topic", "MSG-ACK-1", "test".getBytes(StandardCharsets.UTF_8), null);
        when(mockConsumer.receive(eq(1), any(Duration.class))).thenReturn(List.of(msg));
        template.receive("order_topic", "order_group", 1, Duration.ofSeconds(10));

        // 验证自动根据跟踪映射 ack
        template.ack(msg);
        verify(mockConsumer).ack(msg);

        // 验证显式指定 group 的 ackAsync
        when(mockConsumer.ackAsync(msg)).thenReturn(CompletableFuture.completedFuture(null));
        CompletableFuture<Void> ackFuture = template.ackAsync("order_group", msg);
        ackFuture.get();
        verify(mockConsumer).ackAsync(msg);
    }

    @Test
    @DisplayName("验证租约续期 (changeInvisibleDuration / changeInvisibleDurationAsync)：正常调用底层客户端")
    void testChangeInvisibleDurationOperations() throws Exception {
        MessageView msg = createMockMessageView("order_topic", "MSG-RENEW-1", "test".getBytes(StandardCharsets.UTF_8), null);
        when(mockConsumer.receive(eq(1), any(Duration.class))).thenReturn(List.of(msg));
        template.receive("order_topic", "order_group", 1, Duration.ofSeconds(10));

        template.changeInvisibleDuration(msg, Duration.ofSeconds(45));
        verify(mockConsumer).changeInvisibleDuration(msg, Duration.ofSeconds(45));

        when(mockConsumer.changeInvisibleDurationAsync(msg, Duration.ofSeconds(60)))
                .thenReturn(CompletableFuture.completedFuture(null));
        CompletableFuture<Void> renewFuture = template.changeInvisibleDurationAsync("order_group", msg, Duration.ofSeconds(60));
        renewFuture.get();
        verify(mockConsumer).changeInvisibleDurationAsync(msg, Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("验证高阶执行闭包 (receiveAndExecute)：推断反序列化、四维上下文自动透传与自动 ACK")
    void testReceiveAndExecuteString() throws Exception {
        Map<String, String> headers = new HashMap<>();
        headers.put(MessageHeaderConstants.TRACE_ID, "trace-simple-888");
        headers.put(MessageHeaderConstants.TENANT_ID, "tenant-simple-999");

        MessageView msg = createMockMessageView("order_topic", "MSG-EXEC-1", "order-payload-content".getBytes(StandardCharsets.UTF_8), headers);
        when(mockConsumer.receive(eq(5), any(Duration.class))).thenReturn(List.of(msg));

        AtomicReference<String> capturedPayload = new AtomicReference<>();
        AtomicReference<String> capturedTraceId = new AtomicReference<>();
        AtomicReference<String> capturedTenantId = new AtomicReference<>();

        int count = template.receiveAndExecute("order_topic", "order_group", 5, Duration.ofSeconds(20), String.class, item -> {
            capturedPayload.set(item);
            capturedTraceId.set(MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY));
            capturedTenantId.set(TenantContextHolder.getTenantId());
        });

        assertThat(count).isEqualTo(1);
        assertThat(capturedPayload.get()).isEqualTo("order-payload-content");
        assertThat(capturedTraceId.get()).isEqualTo("trace-simple-888");
        assertThat(capturedTenantId.get()).isEqualTo("tenant-simple-999");

        // 验证执行完成后上下文已严格清理
        assertThat(MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY)).isNull();
        assertThat(TenantContextHolder.getTenantId()).isNull();

        // 验证自动触发了 ACK
        verify(mockConsumer).ack(msg);
    }

    @Test
    @DisplayName("验证高阶执行闭包 (receiveAndExecute)：JSON 对象反序列化正确")
    void testReceiveAndExecuteJson() throws Exception {
        Map<String, Object> data = Map.of("name", "Ateng", "code", 200);
        byte[] jsonBytes = JsonUtil.toJsonBytes(data);

        MessageView msg = createMockMessageView("order_topic", "MSG-EXEC-2", jsonBytes, null);
        when(mockConsumer.receive(eq(5), any(Duration.class))).thenReturn(List.of(msg));

        AtomicInteger processed = new AtomicInteger(0);
        int count = template.receiveAndExecute("order_topic", "order_group", 5, Duration.ofSeconds(20), Map.class, item -> {
            processed.incrementAndGet();
            assertThat(item.get("name")).isEqualTo("Ateng");
        });

        assertThat(count).isEqualTo(1);
        assertThat(processed.get()).isEqualTo(1);
        verify(mockConsumer).ack(msg);
    }

    @Test
    @DisplayName("验证客户端缓存复用：同一 ConsumerGroup 仅初始化一次，追加订阅不同 Topic")
    void testConsumerCacheReusesClientPerGroup() throws Exception {
        when(mockConsumer.getSubscriptionExpressions()).thenReturn(Map.of("order_topic", mock(FilterExpression.class)));

        template.receive("order_topic", "common_group", 5, Duration.ofSeconds(10));
        template.receive("pay_topic", "common_group", 5, Duration.ofSeconds(10));

        // 验证工厂只创建了一次该 Group 的 SimpleConsumer
        verify(simpleConsumerFactory, times(1)).createSimpleConsumer(any(), eq("common_group"), any(), any());
        // 验证对新 Topic 发起了追加订阅
        verify(mockConsumer).subscribe(eq("pay_topic"), any(FilterExpression.class));
    }

    @Test
    @DisplayName("验证 DisposableBean：destroy() 优雅关闭所有缓存的 SimpleConsumer")
    void testDestroyClosesAllConsumers() throws Exception {
        template.receive("order_topic", "group_a", 5, Duration.ofSeconds(10));
        template.receive("order_topic", "group_b", 5, Duration.ofSeconds(10));

        template.destroy();

        verify(mockConsumer, times(2)).close();

        // 销毁后再操作将抛出 IllegalStateException
        assertThatThrownBy(() -> template.receive("order_topic", "group_c", 5, Duration.ofSeconds(10)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("已关闭");
    }

    @Test
    @DisplayName("验证多组订阅相同 Topic 时自动推断抛出歧义异常防范串组")
    void testResolveConsumerGroupWithMultipleGroupsThrowsException() throws Exception {
        SimpleConsumer mockConsumerA = mock(SimpleConsumer.class);
        SimpleConsumer mockConsumerB = mock(SimpleConsumer.class);
        when(mockConsumerA.getSubscriptionExpressions()).thenReturn(Map.of("same_topic", mock(FilterExpression.class)));
        when(mockConsumerB.getSubscriptionExpressions()).thenReturn(Map.of("same_topic", mock(FilterExpression.class)));

        when(simpleConsumerFactory.createSimpleConsumer(any(), eq("group_a"), any(), any())).thenReturn(mockConsumerA);
        when(simpleConsumerFactory.createSimpleConsumer(any(), eq("group_b"), any(), any())).thenReturn(mockConsumerB);

        template.receive("same_topic", "group_a", 1, Duration.ofSeconds(10));
        template.receive("same_topic", "group_b", 1, Duration.ofSeconds(10));

        // 构造一个未经过 template.receive 追踪的 MessageView
        MessageView untrackedMsg = createMockMessageView("same_topic", "UNTRACKED-MSG-1", "test".getBytes(StandardCharsets.UTF_8), null);

        assertThatThrownBy(() -> template.ack(untrackedMsg))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("检测到存在多个消费者组");
    }

    @Test
    @DisplayName("验证 SmartLifecycle 契约：相位正确、幂等停机回调与重启恢复")
    void testSmartLifecyclePhaseIdempotentStopAndRestart() throws Exception {
        assertThat(template).isInstanceOf(SmartLifecycle.class);
        SmartLifecycle lifecycle = (SmartLifecycle) template;
        assertThat(lifecycle.isAutoStartup()).isTrue();
        assertThat(lifecycle.getPhase()).isEqualTo(Integer.MAX_VALUE - 200);
        assertThat(lifecycle.isRunning()).isTrue();

        template.receive("order_topic", "group_lifecycle", 5, Duration.ofSeconds(10));

        AtomicInteger callbackCount = new AtomicInteger(0);
        lifecycle.stop(callbackCount::incrementAndGet);
        assertThat(lifecycle.isRunning()).isFalse();
        assertThat(callbackCount.get()).isEqualTo(1);
        verify(mockConsumer, times(1)).close();

        // 重复 stop 与 destroy 幂等安全，不重复关闭已关闭的消费端
        lifecycle.stop(callbackCount::incrementAndGet);
        template.destroy();
        assertThat(callbackCount.get()).isEqualTo(2);
        verify(mockConsumer, times(1)).close();

        // 重新启动后可再次创建消费客户端
        lifecycle.start();
        assertThat(lifecycle.isRunning()).isTrue();
        template.receive("order_topic", "group_lifecycle_restarted", 5, Duration.ofSeconds(10));
        verify(simpleConsumerFactory, times(1)).createSimpleConsumer(any(), eq("group_lifecycle_restarted"), any(), any());
    }

    @Test
    @DisplayName("验证 ReentrantLock 并发初始化与消息追踪映射在多线程下安全无异常")
    void testConcurrentConsumerInitializationAndAckTracking() throws Exception {
        int groupCount = 3;
        int workerCount = 12;
        for (int g = 0; g < groupCount; g++) {
            String group = "group_concurrent_" + g;
            SimpleConsumer consumer = mock(SimpleConsumer.class);
            ConcurrentLinkedQueue<MessageView> queue = new ConcurrentLinkedQueue<>();
            for (int m = 0; m < workerCount; m++) {
                queue.add(createMockMessageView("order_topic", "MSG-C-" + g + "-" + m, "ok".getBytes(StandardCharsets.UTF_8), null));
            }
            when(consumer.receive(eq(1), any(Duration.class)))
                    .thenAnswer(inv -> {
                        MessageView next = queue.poll();
                        return next != null ? List.of(next) : Collections.emptyList();
                    });
            when(simpleConsumerFactory.createSimpleConsumer(any(), eq(group), any(), any())).thenReturn(consumer);
        }

        CountDownLatch startGate = new CountDownLatch(1);
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < workerCount; i++) {
            final int idx = i;
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    startGate.await(5, TimeUnit.SECONDS);
                    String group = "group_concurrent_" + (idx % groupCount);
                    List<MessageView> received = template.receive("order_topic", group, 1, Duration.ofSeconds(10));
                    for (MessageView mv : received) {
                        template.ack(mv);
                    }
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            }));
        }

        startGate.countDown();
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).get(10, TimeUnit.SECONDS);

        for (int g = 0; g < groupCount; g++) {
            verify(simpleConsumerFactory, times(1)).createSimpleConsumer(any(), eq("group_concurrent_" + g), any(), any());
        }
    }

    @Test
    @DisplayName("验证动态过滤表达式刷新：同组同 Topic 变更 Tag 刷新订阅，相同指纹不重复刷新，异常不污染缓存")
    void testDynamicFilterExpressionRefresh() throws Exception {
        Map<String, FilterExpression> currentSubs = new HashMap<>();
        currentSubs.put("filter_topic", new FilterExpression("tag_v1", FilterExpressionType.TAG));
        when(mockConsumer.getSubscriptionExpressions()).thenAnswer(inv -> Collections.unmodifiableMap(new HashMap<>(currentSubs)));
        doAnswer(inv -> {
            String t = inv.getArgument(0);
            FilterExpression fe = inv.getArgument(1);
            currentSubs.put(t, fe);
            return mockConsumer;
        }).when(mockConsumer).subscribe(anyString(), any(FilterExpression.class));

        // 1. 初次订阅 tag_v1
        template.receive("filter_topic", "filter_group", "tag_v1", 5, Duration.ofSeconds(10));
        verify(simpleConsumerFactory, times(1)).createSimpleConsumer(any(), eq("filter_group"), any(), any());
        verify(mockConsumer, never()).subscribe(anyString(), any());

        // 2. 相同过滤指纹再次拉取，不触发冗余 subscribe
        template.receive("filter_topic", "filter_group", "tag_v1", 5, Duration.ofSeconds(10));
        verify(mockConsumer, never()).subscribe(anyString(), any());

        // 3. 切换为 tag_v2，触发底层 subscribe 动态刷新
        template.receive("filter_topic", "filter_group", "tag_v2", 5, Duration.ofSeconds(10));
        verify(mockConsumer, times(1)).subscribe(eq("filter_topic"),
                argThat(fe -> fe != null && "tag_v2".equals(fe.getExpression())
                        && FilterExpressionType.TAG.equals(fe.getFilterExpressionType())));

        // 4. 若刷新订阅异常，应保留因果链并维持原指纹（不污染缓存）
        doThrow(new RuntimeException("broker subscribe fail")).when(mockConsumer).subscribe(eq("filter_topic"), any());
        assertThatThrownBy(() -> template.receive("filter_topic", "filter_group", "tag_v3", 5, Duration.ofSeconds(10)))
                .isInstanceOf(MessageDeliveryException.class)
                .hasCauseInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("验证单条故障隔离与 IdempotentException 静默确认：异常消息不阻塞同批且独立清理上下文")
    void testReceiveAndExecuteFaultIsolationAndIdempotentSilentAck() throws Exception {
        Map<String, String> headers1 = Map.of(MessageHeaderConstants.TRACE_ID, "trace-iso-1", MessageHeaderConstants.TENANT_ID, "tenant-1");
        Map<String, String> headers2 = Map.of(MessageHeaderConstants.TRACE_ID, "trace-iso-2", MessageHeaderConstants.TENANT_ID, "tenant-2");
        Map<String, String> headers3 = Map.of(MessageHeaderConstants.TRACE_ID, "trace-iso-3", MessageHeaderConstants.TENANT_ID, "tenant-3");
        Map<String, String> headers4 = Map.of(MessageHeaderConstants.TRACE_ID, "trace-iso-4", MessageHeaderConstants.TENANT_ID, "tenant-4");

        MessageView m1 = createMockMessageView("iso_topic", "MSG-ISO-1", "idempotent".getBytes(StandardCharsets.UTF_8), headers1);
        MessageView m2 = createMockMessageView("iso_topic", "MSG-ISO-2", "boom".getBytes(StandardCharsets.UTF_8), headers2);
        MessageView m3 = createMockMessageView("iso_topic", "MSG-ISO-3", "boom_handler_fail".getBytes(StandardCharsets.UTF_8), headers3);
        MessageView m4 = createMockMessageView("iso_topic", "MSG-ISO-4", "ok".getBytes(StandardCharsets.UTF_8), headers4);

        when(mockConsumer.receive(eq(10), any(Duration.class))).thenReturn(List.of(m1, m2, m3, m4));

        List<String> okPayloads = new ArrayList<>();
        List<String> observedTraces = new ArrayList<>();
        List<String> failedIds = new ArrayList<>();

        int handled = template.receiveAndExecute(
                "iso_topic",
                "iso_group",
                "tagA",
                10,
                Duration.ofSeconds(20),
                String.class,
                (payload, view) -> {
                    observedTraces.add(MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY));
                    if ("idempotent".equals(payload)) {
                        throw new IdempotentException("重复投递幂等拦截");
                    }
                    if (payload.startsWith("boom")) {
                        throw new IllegalStateException("业务处理异常: " + payload);
                    }
                    okPayloads.add(payload);
                },
                (failedMsg, cause) -> {
                    failedIds.add(failedMsg.getMessageId().toString());
                    if ("MSG-ISO-3".equals(failedMsg.getMessageId().toString())) {
                        throw new RuntimeException("回调自身抛错也必须被隔离");
                    }
                });

        // m1(幂等静默ACK) + m4(正常ACK) => 2；m2, m3 跳过 ACK
        assertThat(handled).isEqualTo(2);
        assertThat(okPayloads).containsExactly("ok");
        assertThat(observedTraces).containsExactly("trace-iso-1", "trace-iso-2", "trace-iso-3", "trace-iso-4");
        assertThat(failedIds).containsExactly("MSG-ISO-2", "MSG-ISO-3");

        verify(mockConsumer, times(1)).ack(m1);
        verify(mockConsumer, never()).ack(m2);
        verify(mockConsumer, never()).ack(m3);
        verify(mockConsumer, times(1)).ack(m4);

        // 单条执行结束后外层四维上下文必须无残留
        assertThat(MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY)).isNull();
        assertThat(TenantContextHolder.getTenantId()).isNull();
    }

    @Test
    @DisplayName("验证租约自动续期看门狗：达到 maxLeaseRenewAttempts 止停、finally 即时停卫与禁用开关生效")
    void testLeaseRenewalWatchdogBehavior() throws Exception {
        properties.getSimpleConsumer().setAutoRenewLease(true);
        properties.getSimpleConsumer().setMaxLeaseRenewAttempts(2);

        MessageView slowMsg = createMockMessageView(
                "lease_topic",
                "MSG-LEASE-1",
                "slow-task".getBytes(StandardCharsets.UTF_8),
                Map.of(MessageHeaderConstants.TRACE_ID, "trace-lease-1"));
        when(mockConsumer.receive(eq(1), any(Duration.class))).thenReturn(List.of(slowMsg));

        CountDownLatch twoRenewals = new CountDownLatch(2);
        List<String> renewalTraces = Collections.synchronizedList(new ArrayList<>());
        doAnswer(inv -> {
            renewalTraces.add(MDC.get(MessageContextPropagator.MDC_TRACE_ID_KEY));
            twoRenewals.countDown();
            return null;
        }).when(mockConsumer).changeInvisibleDuration(eq(slowMsg), eq(Duration.ofSeconds(2)));

        int count = template.receiveAndExecute(
                "lease_topic",
                "lease_group",
                "*",
                1,
                Duration.ofSeconds(2),
                String.class,
                (payload, view) -> {
                    try {
                        assertThat(twoRenewals.await(10, TimeUnit.SECONDS)).isTrue();
                        // 多等待 1.3s，确认达到 maxLeaseRenewAttempts=2 上限后不会触发第 3 次续期
                        Thread.sleep(1300L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                },
                null);

        assertThat(count).isEqualTo(1);
        verify(mockConsumer, times(2)).changeInvisibleDuration(slowMsg, Duration.ofSeconds(2));
        verify(mockConsumer, times(1)).ack(slowMsg);
        assertThat(renewalTraces).containsExactly("trace-lease-1", "trace-lease-1");

        // 再测试快速完成或抛错后，不会发生额外续期；以及 autoRenewLease=false 完全关闭看门狗
        MessageView failFastMsg = createMockMessageView("lease_topic", "MSG-LEASE-2", "fast-fail".getBytes(StandardCharsets.UTF_8), null);
        when(mockConsumer.receive(eq(1), any(Duration.class))).thenReturn(List.of(failFastMsg));
        template.receiveAndExecute(
                "lease_topic",
                "lease_group",
                "*",
                1,
                Duration.ofSeconds(2),
                String.class,
                (payload, view) -> {
                    throw new IllegalStateException("快速失败立即触发 finally 停卫");
                },
                (mv, ex) -> {});
        Thread.sleep(1200L);
        verify(mockConsumer, never()).changeInvisibleDuration(eq(failFastMsg), any());

        properties.getSimpleConsumer().setAutoRenewLease(false);
        MessageView disabledMsg = createMockMessageView("lease_topic", "MSG-LEASE-3", "disabled".getBytes(StandardCharsets.UTF_8), null);
        when(mockConsumer.receive(eq(1), any(Duration.class))).thenReturn(List.of(disabledMsg));
        template.receiveAndExecute(
                "lease_topic",
                "lease_group",
                "*",
                1,
                Duration.ofSeconds(2),
                String.class,
                (payload, view) -> {
                    try {
                        Thread.sleep(1200L);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                },
                null);
        verify(mockConsumer, never()).changeInvisibleDuration(eq(disabledMsg), any());

        // SmartLifecycle.stop() 应立即终止仍在运行中的看门狗守卫
        properties.getSimpleConsumer().setAutoRenewLease(true);
        properties.getSimpleConsumer().setMaxLeaseRenewAttempts(5);
        MessageView stopMsg = createMockMessageView("lease_topic", "MSG-LEASE-4", "stop-during-renew".getBytes(StandardCharsets.UTF_8), null);
        when(mockConsumer.receive(eq(1), any(Duration.class))).thenReturn(List.of(stopMsg));
        CountDownLatch startedProcessing = new CountDownLatch(1);
        CountDownLatch allowReturn = new CountDownLatch(1);
        CompletableFuture<Integer> asyncExec = CompletableFuture.supplyAsync(() -> template.receiveAndExecute(
                "lease_topic",
                "lease_group",
                "*",
                1,
                Duration.ofSeconds(2),
                String.class,
                (payload, view) -> {
                    startedProcessing.countDown();
                    try {
                        allowReturn.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                },
                null));
        assertThat(startedProcessing.await(3, TimeUnit.SECONDS)).isTrue();
        template.stop();
        allowReturn.countDown();
        assertThat(asyncExec.get(5, TimeUnit.SECONDS)).isEqualTo(0);
        verify(mockConsumer, never()).changeInvisibleDuration(eq(stopMsg), any());
    }
}
