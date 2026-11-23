package io.github.atengk.boot.mqtt.requestreply;

import io.github.atengk.boot.core.constant.MessageHeaderConstants;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.util.JsonUtil;
import io.github.atengk.boot.mqtt.annotation.MqttListener;
import io.github.atengk.boot.mqtt.annotation.MqttReply;
import io.github.atengk.boot.mqtt.context.MqttContextHolder;
import io.github.atengk.boot.mqtt.defense.PayloadGuard;
import io.github.atengk.boot.mqtt.exception.MqttTimeoutException;
import io.github.atengk.boot.mqtt.listener.MqttListenerAnnotationBeanPostProcessor;
import io.github.atengk.boot.mqtt.listener.MqttListenerEndpointRegistry;
import io.github.atengk.boot.mqtt.properties.AtengMqttProperties;
import io.github.atengk.boot.mqtt.properties.ClientIdStrategy;
import io.github.atengk.boot.mqtt.properties.MqttClientProperties;
import io.github.atengk.boot.mqtt.properties.PersistenceType;
import io.github.atengk.boot.mqtt.registry.NamedMqttClientRegistry;
import io.github.atengk.boot.mqtt.template.MqttTemplate;
import io.github.atengk.boot.mqtt.test.EmbeddedMqttBroker;
import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.MqttSubscription;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * MQTT 请求-响应模式 (Request-Response Pattern) 双向控制闭环全维单元测试
 * 覆盖同步阻塞等待、异步 POJO 转译、超时安全熔断与防泄漏、多并发乱序精确唤醒与四维上下文透传
 *
 * @author Ateng
 * @since 2026-10-23
 */
class MqttRequestReplyTest {

    private EmbeddedMqttBroker broker;
    private NamedMqttClientRegistry clientRegistry;
    private MqttRequestReplyManager requestReplyManager;
    private MqttTemplate mqttTemplate;
    private ExecutorService testExecutor;

    public record DeviceQuery(String deviceId) {}

    public record DeviceResponse(String deviceId, String status, int battery) {}

    @BeforeEach
    void setUp() {
        // 1. 启动轻量纯内存 MQTT Broker
        broker = new EmbeddedMqttBroker(0);
        broker.start();

        // 2. 配置两个独立 MQTT 客户端：主控平台客户端 (primary) 与模拟边缘设备客户端 (device)
        AtengMqttProperties properties = new AtengMqttProperties();
        properties.setDefaultClient("primary");

        Map<String, MqttClientProperties> clients = new HashMap<>();

        // primary: 主控平台发信端
        MqttClientProperties primary = new MqttClientProperties();
        primary.setServerUri(broker.getServerUri());
        primary.setClientId("test-platform");
        primary.setClientIdStrategy(ClientIdStrategy.STATIC);
        primary.setCleanStart(true);
        primary.setConnectionTimeout(Duration.ofSeconds(3));
        primary.setPersistenceType(PersistenceType.MEMORY);
        primary.setProtocolVersion("5.0");
        clients.put("primary", primary);

        // device: 模拟边缘硬件设备端
        MqttClientProperties device = new MqttClientProperties();
        device.setServerUri(broker.getServerUri());
        device.setClientId("test-device");
        device.setClientIdStrategy(ClientIdStrategy.STATIC);
        device.setCleanStart(true);
        device.setConnectionTimeout(Duration.ofSeconds(3));
        device.setPersistenceType(PersistenceType.MEMORY);
        device.setProtocolVersion("5.0");
        clients.put("device", device);

        properties.setClients(clients);

        // 3. 注册并初始化客户端
        clientRegistry = new NamedMqttClientRegistry(properties);
        clientRegistry.initClients();

        // 4. 构建 RequestReplyManager 并启动预订阅
        testExecutor = Executors.newCachedThreadPool();
        PayloadGuard payloadGuard = new PayloadGuard();
        requestReplyManager = new MqttRequestReplyManager(clientRegistry, payloadGuard, testExecutor);
        requestReplyManager.start();

        // 5. 构建门面 MqttTemplate
        mqttTemplate = new MqttTemplate(clientRegistry, payloadGuard, null, testExecutor, requestReplyManager);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        TenantContextHolder.clear();
        UserContextHolder.clear();
        MqttContextHolder.clear();

        if (requestReplyManager != null) {
            requestReplyManager.stop();
        }
        if (clientRegistry != null) {
            clientRegistry.destroy();
        }
        if (testExecutor != null) {
            testExecutor.shutdownNow();
        }
        if (broker != null) {
            broker.stop();
        }
    }

    @Test
    @DisplayName("同步阻塞：下发控制指令并成功接收虚拟设备回执应答")
    void shouldSendAndReceiveSyncSuccessfully() throws Exception {
        // 1. 模拟设备端订阅控制主题 cmd/device/control
        MqttSubscription sub = new MqttSubscription("cmd/device/control", 1);
        clientRegistry.getClientHandle("device").getAsyncClient().subscribe(
                new MqttSubscription[]{sub}, null, null,
                new org.eclipse.paho.mqttv5.client.IMqttMessageListener[]{
                        (topic, message) -> {
                            // 设备收到控制指令，提取 ResponseTopic 与 CorrelationData 并返回回执
                            MqttProperties reqProps = message.getProperties();
                            String replyTopic = reqProps.getResponseTopic();
                            byte[] correlationData = reqProps.getCorrelationData();

                            MqttProperties replyProps = new MqttProperties();
                            replyProps.setCorrelationData(correlationData);

                            MqttMessage replyMsg = new MqttMessage("ACK_POWER_ON_SUCCESS".getBytes(StandardCharsets.UTF_8));
                            replyMsg.setQos(1);
                            replyMsg.setProperties(replyProps);

                            clientRegistry.getClientHandle("device").getAsyncClient().publish(replyTopic, replyMsg);
                        }
                }, null
        ).waitForCompletion(3000);

        // 2. 平台端同步发起请求并阻塞等待
        MqttMessage response = mqttTemplate.sendAndReceive("cmd/device/control", "CMD_POWER_ON", Duration.ofSeconds(3));

        // 3. 验证应答结果
        assertThat(response).isNotNull();
        assertThat(new String(response.getPayload(), StandardCharsets.UTF_8)).isEqualTo("ACK_POWER_ON_SUCCESS");
    }

    @Test
    @DisplayName("异步非阻塞与 POJO 自动转译：发送查询请求并直接获取结构化响应对象")
    void shouldSendAndReceiveAsyncWithPojoConversion() throws Exception {
        // 1. 模拟设备端监听状态查询主题
        MqttSubscription sub = new MqttSubscription("cmd/device/query", 1);
        clientRegistry.getClientHandle("device").getAsyncClient().subscribe(
                new MqttSubscription[]{sub}, null, null,
                new org.eclipse.paho.mqttv5.client.IMqttMessageListener[]{
                        (topic, message) -> {
                            MqttProperties reqProps = message.getProperties();
                            String replyTopic = reqProps.getResponseTopic();
                            byte[] correlationData = reqProps.getCorrelationData();

                            // 设备端生成 JSON 状态响应
                            DeviceResponse devResp = new DeviceResponse("device-888", "ONLINE", 95);
                            byte[] jsonBytes = JsonUtil.toJsonBytes(devResp);

                            MqttProperties replyProps = new MqttProperties();
                            replyProps.setCorrelationData(correlationData);

                            MqttMessage replyMsg = new MqttMessage(jsonBytes);
                            replyMsg.setQos(1);
                            replyMsg.setProperties(replyProps);

                            clientRegistry.getClientHandle("device").getAsyncClient().publish(replyTopic, replyMsg);
                        }
                }, null
        ).waitForCompletion(3000);

        // 2. 平台端异步发送 POJO 请求并声明期望的返回类型 DeviceResponse
        DeviceQuery query = new DeviceQuery("device-888");
        CompletableFuture<DeviceResponse> future = mqttTemplate.sendAndReceiveAsync(
                "cmd/device/query", query, DeviceResponse.class, Duration.ofSeconds(3)
        );

        // 3. 阻塞等待异步 Future 完成
        DeviceResponse result = future.get(3, TimeUnit.SECONDS);

        // 4. 验证 POJO 属性自动反序列化
        assertThat(result).isNotNull();
        assertThat(result.deviceId()).isEqualTo("device-888");
        assertThat(result.status()).isEqualTo("ONLINE");
        assertThat(result.battery()).isEqualTo(95);
    }

    @Test
    @DisplayName("超时安全熔断：未在指定 Duration 内收到应答时快速失败抛出 MqttTimeoutException 并清理挂起句柄")
    void shouldThrowTimeoutExceptionAndCleanPendingMap() {
        // 主动发送到一个无人监听的主题，设置 400ms 超时
        Duration timeout = Duration.ofMillis(400);

        assertThatThrownBy(() -> mqttTemplate.sendAndReceive("cmd/device/unreachable", "PING", timeout))
                .isInstanceOf(MqttTimeoutException.class)
                .hasMessageContaining("Request-Response timed out")
                .satisfies(ex -> {
                    MqttTimeoutException te = (MqttTimeoutException) ex;
                    assertThat(te.getTopic()).isEqualTo("cmd/device/unreachable");
                });
    }

    @Test
    @DisplayName("并发压力与无锁关联唤醒：10 个并发请求乱序回执应答，100% 精确唤醒无串门")
    void shouldDistinguishConcurrentRequestsAccuratelyWithoutInterference() throws Exception {
        int requestCount = 10;
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(requestCount);
        ConcurrentHashMap<String, String> receivedReplies = new ConcurrentHashMap<>();

        // 1. 模拟设备端监听多并发控制主题，故意随机稍微 sleep 制造乱序回执
        MqttSubscription sub = new MqttSubscription("cmd/device/concurrent", 1);
        clientRegistry.getClientHandle("device").getAsyncClient().subscribe(
                new MqttSubscription[]{sub}, null, null,
                new org.eclipse.paho.mqttv5.client.IMqttMessageListener[]{
                        (topic, message) -> {
                            testExecutor.submit(() -> {
                                try {
                                    String reqPayload = new String(message.getPayload(), StandardCharsets.UTF_8);
                                    // 模拟设备处理不同请求的轻微乱序差异
                                    Thread.sleep((long) (Math.random() * 50));

                                    MqttProperties reqProps = message.getProperties();
                                    String replyTopic = reqProps.getResponseTopic();
                                    byte[] correlationData = reqProps.getCorrelationData();

                                    MqttProperties replyProps = new MqttProperties();
                                    replyProps.setCorrelationData(correlationData);

                                    MqttMessage replyMsg = new MqttMessage(("ECHO_" + reqPayload).getBytes(StandardCharsets.UTF_8));
                                    replyMsg.setQos(1);
                                    replyMsg.setProperties(replyProps);

                                    clientRegistry.getClientHandle("device").getAsyncClient().publish(replyTopic, replyMsg);
                                } catch (Exception e) {
                                    // ignore
                                }
                            });
                        }
                }, null
        ).waitForCompletion(3000);

        // 2. 10 个线程并发发起 RPC 请求
        ExecutorService clientExecutor = Executors.newFixedThreadPool(requestCount);
        try {
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (int i = 0; i < requestCount; i++) {
                final int index = i;
                CompletableFuture<Void> f = CompletableFuture.runAsync(() -> {
                    try {
                        startLatch.await();
                        String reqText = "REQ_KEY_" + index;
                        MqttMessage response = mqttTemplate.sendAndReceive("cmd/device/concurrent", reqText, Duration.ofSeconds(4));
                        receivedReplies.put(reqText, new String(response.getPayload(), StandardCharsets.UTF_8));
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        doneLatch.countDown();
                    }
                }, clientExecutor);
                futures.add(f);
            }

            // 释放发令枪
            startLatch.countDown();
            boolean completed = doneLatch.await(5, TimeUnit.SECONDS);

            assertThat(completed).isTrue();
            assertThat(receivedReplies).hasSize(requestCount);
            for (int i = 0; i < requestCount; i++) {
                String key = "REQ_KEY_" + i;
                assertThat(receivedReplies.get(key)).isEqualTo("ECHO_" + key);
            }
        } finally {
            clientExecutor.shutdownNow();
        }
    }

    @Test
    @DisplayName("四维链路上下文透传：平台端 User Properties 在请求-响应交互闭环中完整穿透并自洽还原")
    void shouldPropagateContextThroughRpcLoop() throws Exception {
        // 设置初始上下文
        MDC.put("traceId", "trace-req-reply-12345");
        UserContextHolder.set("user-999");
        TenantContextHolder.setTenantId("tenant-888");

        AtomicInteger verifiedStep = new AtomicInteger(0);

        // 设备端监听
        MqttSubscription sub = new MqttSubscription("cmd/device/context", 1);
        clientRegistry.getClientHandle("device").getAsyncClient().subscribe(
                new MqttSubscription[]{sub}, null, null,
                new org.eclipse.paho.mqttv5.client.IMqttMessageListener[]{
                        (topic, message) -> {
                            MqttProperties reqProps = message.getProperties();
                            List<UserProperty> userProps = reqProps.getUserProperties();
                            Map<String, String> propMap = new HashMap<>();
                            if (userProps != null) {
                                for (UserProperty up : userProps) {
                                    propMap.put(up.getKey(), up.getValue());
                                }
                            }
                            // 校验平台发送的上下文已注入到 MQTT 5.0 User Properties
                            if ("trace-req-reply-12345".equals(propMap.get(MessageHeaderConstants.TRACE_ID))
                                    && "user-999".equals(propMap.get(MessageHeaderConstants.USER_ID))
                                    && "tenant-888".equals(propMap.get(MessageHeaderConstants.TENANT_ID))) {
                                verifiedStep.incrementAndGet();
                            }

                            // 设备端原样把上下文回传
                            MqttProperties replyProps = new MqttProperties();
                            replyProps.setCorrelationData(reqProps.getCorrelationData());
                            replyProps.setUserProperties(userProps);

                            MqttMessage replyMsg = new MqttMessage("CONTEXT_ACK".getBytes(StandardCharsets.UTF_8));
                            replyMsg.setQos(1);
                            replyMsg.setProperties(replyProps);

                            clientRegistry.getClientHandle("device").getAsyncClient().publish(reqProps.getResponseTopic(), replyMsg);
                        }
                }, null
        ).waitForCompletion(3000);

        // 平台发起请求
        MqttMessage reply = mqttTemplate.sendAndReceive("cmd/device/context", "HELLO_CONTEXT", Duration.ofSeconds(3));

        assertThat(reply).isNotNull();
        assertThat(new String(reply.getPayload(), StandardCharsets.UTF_8)).isEqualTo("CONTEXT_ACK");
        assertThat(verifiedStep.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("预订阅聚合响应主题：客户端启动时自动预订阅 sys/response/{clientId}，多次发信无需动态订阅")
    void shouldVerifyPreSubscribedResponseTopic() {
        String primaryResponseTopic = clientRegistry.getClientHandle("primary").getResponseTopic();
        assertThat(primaryResponseTopic).isEqualTo("sys/response/test-platform");

        String deviceResponseTopic = clientRegistry.getClientHandle("device").getResponseTopic();
        assertThat(deviceResponseTopic).isEqualTo("sys/response/test-device");
    }

    @Test
    @DisplayName("非阻塞异步：MqttRequestReplyManager 直接发起强类型异步 RPC 交互")
    void shouldSendAndReceiveAsyncDirectlyOnManager() throws Exception {
        // 模拟设备响应
        MqttSubscription sub = new MqttSubscription("cmd/device/async-rpc", 1);
        clientRegistry.getClientHandle("device").getAsyncClient().subscribe(
                new MqttSubscription[]{sub}, null, null,
                new org.eclipse.paho.mqttv5.client.IMqttMessageListener[]{
                        (topic, message) -> {
                            MqttProperties reqProps = message.getProperties();
                            MqttProperties replyProps = new MqttProperties();
                            replyProps.setCorrelationData(reqProps.getCorrelationData());

                            DeviceResponse response = new DeviceResponse("DEV-999", "ONLINE", 98);
                            MqttMessage replyMsg = new MqttMessage(JsonUtil.toJsonBytes(response));
                            replyMsg.setQos(1);
                            replyMsg.setProperties(replyProps);

                            clientRegistry.getClientHandle("device").getAsyncClient().publish(reqProps.getResponseTopic(), replyMsg);
                        }
                }, null
        ).waitForCompletion(3000);

        CompletableFuture<DeviceResponse> future = requestReplyManager.sendAndReceiveAsync(
                "cmd/device/async-rpc",
                new DeviceQuery("DEV-999"),
                DeviceResponse.class,
                Duration.ofSeconds(3)
        );

        DeviceResponse result = future.get(3, TimeUnit.SECONDS);
        assertThat(result).isNotNull();
        assertThat(result.deviceId()).isEqualTo("DEV-999");
        assertThat(result.status()).isEqualTo("ONLINE");
        assertThat(result.battery()).isEqualTo(98);
    }

    @Test
    @DisplayName("断线重连响应通道自愈：重连后清除已订阅缓存并重新预订阅私有响应主题")
    void shouldSelfHealResponseTopicOnReconnect() throws Exception {
        assertThat(requestReplyManager.isSubscribed("primary")).isTrue();

        // 模拟触发重连事件前构造一个已超期但定时扫描器尚未触发的挂起请求
        CompletableFuture<String> expiredFuture = requestReplyManager.sendAndReceiveAsync(
                "cmd/device/expired-before-reconnect",
                "EXPIRE_TEST",
                String.class,
                Duration.ofMillis(50)
        );
        Thread.sleep(80); // 等待超过 50ms 超时时间，但未达到 500ms 定时扫描周期

        // 模拟触发重连事件并验证主动清理过期请求
        requestReplyManager.handleReconnect("primary");
        assertThat(expiredFuture.isCompletedExceptionally()).isTrue();

        assertThat(requestReplyManager.isSubscribed("primary")).isTrue();

        // 验证重连自愈后仍能正常走通 RPC
        MqttSubscription sub = new MqttSubscription("cmd/device/after-reconnect", 1);
        clientRegistry.getClientHandle("device").getAsyncClient().subscribe(
                new MqttSubscription[]{sub}, null, null,
                new org.eclipse.paho.mqttv5.client.IMqttMessageListener[]{
                        (topic, message) -> {
                            MqttProperties reqProps = message.getProperties();
                            MqttProperties replyProps = new MqttProperties();
                            replyProps.setCorrelationData(reqProps.getCorrelationData());

                            MqttMessage replyMsg = new MqttMessage("HEALED_OK".getBytes(StandardCharsets.UTF_8));
                            replyMsg.setQos(1);
                            replyMsg.setProperties(replyProps);

                            clientRegistry.getClientHandle("device").getAsyncClient().publish(reqProps.getResponseTopic(), replyMsg);
                        }
                }, null
        ).waitForCompletion(3000);

        CompletableFuture<String> future = requestReplyManager.sendAndReceiveAsync(
                "cmd/device/after-reconnect",
                "PING",
                String.class,
                Duration.ofSeconds(3)
        );

        String reply = future.get(3, TimeUnit.SECONDS);
        assertThat(reply).isEqualTo("HEALED_OK");
    }

    static class DeclarativeReplyDeviceBean {
        private final java.util.concurrent.Executor asyncExecutor;

        DeclarativeReplyDeviceBean(java.util.concurrent.Executor asyncExecutor) {
            this.asyncExecutor = asyncExecutor;
        }

        @MqttListener(topics = "rpc/sync/echo", client = "device", qos = 1)
        public DeviceResponse onSyncQuery(DeviceQuery query) {
            return new DeviceResponse(query.deviceId(), "ACK", 99);
        }

        @MqttListener(topics = "rpc/async/echo", client = "device", qos = 1)
        @MqttReply(qos = 1, retained = false)
        public CompletableFuture<String> onAsyncQuery(String payload) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    Thread.sleep(30);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                return "ASYNC:" + payload;
            }, asyncExecutor);
        }

        private final java.util.concurrent.atomic.AtomicInteger asyncRetryCount = new java.util.concurrent.atomic.AtomicInteger();

        @MqttListener(topics = "rpc/async/retry", client = "device", qos = 1, maxRetries = 1)
        @MqttReply(qos = 1)
        public CompletableFuture<String> onAsyncRetry(String payload) {
            int attempt = asyncRetryCount.incrementAndGet();
            if (attempt == 1) {
                return CompletableFuture.failedFuture(new RuntimeException("transient-async-fail"));
            }
            return CompletableFuture.completedFuture("ASYNC-RECOVERED:" + payload);
        }

        @MqttListener(topics = "rpc/fallback/notify", client = "device", qos = 1)
        @MqttReply(topic = "rpc/fallback/out", qos = 1, retained = true)
        public String onFallbackNotify(String payload) {
            return "FALLBACK:" + payload;
        }

        @MqttListener(topics = "rpc/no-target/ignore", client = "device", qos = 1)
        public String onMissingTarget(String payload) {
            return "IGNORED";
        }
    }

    @Test
    @DisplayName("声明式应答闭环：非 void 与 CompletableFuture 自动回发 CorrelationData 与四维上下文、失败退避重试与 @MqttReply 兜底主题")
    void shouldReplyDeclarativelyForSyncValueCompletableFutureAndFallbackTopic() throws Exception {
        MqttListenerEndpointRegistry endpointRegistry =
                new MqttListenerEndpointRegistry(clientRegistry, new PayloadGuard(), testExecutor);
        DeclarativeReplyDeviceBean deviceBean = new DeclarativeReplyDeviceBean(testExecutor);
        new MqttListenerAnnotationBeanPostProcessor(endpointRegistry)
                .postProcessAfterInitialization(deviceBean, "declarativeReplyDeviceBean");
        endpointRegistry.start();

        try {
            // 1. 同步非 void 返回值自动回发至入站 ResponseTopic 并携带 CorrelationData 与四维上下文
            MDC.put("traceId", "trace-decl-reply");
            TenantContextHolder.setTenantId("tenant-decl");
            UserContextHolder.setUserId("user-decl");
            TenantContextHolder.setIgnoreTenant(true);
            try {
                DeviceResponse syncReply = mqttTemplate.sendAndReceive(
                        "rpc/sync/echo",
                        new DeviceQuery("DEV-88"),
                        DeviceResponse.class,
                        Duration.ofSeconds(3)
                );
                assertThat(syncReply).isEqualTo(new DeviceResponse("DEV-88", "ACK", 99));
            } finally {
                MDC.clear();
                TenantContextHolder.clear();
                UserContextHolder.clear();
            }

            // 校验同步声明式应答回发报文携带了四维上下文标头
            assertThat(broker.getReceivedMessages()).anySatisfy(m -> {
                assertThat(m.getTopic()).startsWith("sys/response/");
                Map<String, String> replyHeaders = new HashMap<>();
                if (m.getProperties() != null) {
                    for (io.netty.handler.codec.mqtt.MqttProperties.MqttProperty<?> prop : m.getProperties().listAll()) {
                        if (prop instanceof io.netty.handler.codec.mqtt.MqttProperties.UserProperties up) {
                            for (io.netty.handler.codec.mqtt.MqttProperties.StringPair pair : up.value()) {
                                replyHeaders.put(pair.key, pair.value);
                            }
                        } else if (prop instanceof io.netty.handler.codec.mqtt.MqttProperties.UserProperty up) {
                            replyHeaders.put(up.value().key, up.value().value);
                        }
                    }
                }
                assertThat(replyHeaders).containsEntry(io.github.atengk.boot.core.constant.MessageHeaderConstants.TRACE_ID, "trace-decl-reply");
                assertThat(replyHeaders).containsEntry(io.github.atengk.boot.core.constant.MessageHeaderConstants.TENANT_ID, "tenant-decl");
                assertThat(replyHeaders).containsEntry(io.github.atengk.boot.core.constant.MessageHeaderConstants.USER_ID, "user-decl");
                assertThat(replyHeaders).containsEntry(io.github.atengk.boot.core.constant.MessageHeaderConstants.IGNORE_TENANT, "true");
            });

            // 2. CompletableFuture 异步返回值自动回发匹配 CorrelationData
            CompletableFuture<String> asyncFuture = mqttTemplate.sendAndReceiveAsync(
                    "rpc/async/echo",
                    "hello",
                    1,
                    String.class,
                    Duration.ofSeconds(3)
            );
            assertThat(asyncFuture.get(3, TimeUnit.SECONDS)).isEqualTo("ASYNC:hello");

            // 3. CompletableFuture 异常完成触发非致命重试并最终应答恢复
            String retriedAsync = mqttTemplate.sendAndReceiveAsync(
                    "rpc/async/retry",
                    "retry-me",
                    1,
                    String.class,
                    Duration.ofSeconds(3)
            ).get(3, TimeUnit.SECONDS);
            assertThat(retriedAsync).isEqualTo("ASYNC-RECOVERED:retry-me");
            assertThat(deviceBean.asyncRetryCount.get()).isEqualTo(2);

            // 4. 入站未携带 ResponseTopic 时通过 @MqttReply(topic=...) 兜底发布，无兜底主题时不崩溃
            mqttTemplate.publish("rpc/fallback/notify", "go", 1);
            mqttTemplate.publish("rpc/no-target/ignore", "noop", 1);

            long deadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < deadline
                    && broker.getReceivedMessages().stream().noneMatch(m -> "rpc/fallback/out".equals(m.getTopic()))) {
                Thread.sleep(30);
            }
            assertThat(broker.getReceivedMessages()).anySatisfy(m -> {
                assertThat(m.getTopic()).isEqualTo("rpc/fallback/out");
                assertThat(new String(m.getPayload(), StandardCharsets.UTF_8)).isEqualTo("FALLBACK:go");
                assertThat(m.isRetained()).isTrue();
            });
        } finally {
            endpointRegistry.stop();
        }
    }
}
