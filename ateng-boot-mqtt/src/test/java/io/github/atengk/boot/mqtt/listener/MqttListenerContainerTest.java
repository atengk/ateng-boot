package io.github.atengk.boot.mqtt.listener;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.IdempotentException;
import io.github.atengk.boot.mqtt.annotation.MqttHeader;
import io.github.atengk.boot.mqtt.annotation.MqttListener;
import io.github.atengk.boot.mqtt.annotation.MqttTopic;
import io.github.atengk.boot.mqtt.annotation.MqttVariable;
import io.github.atengk.boot.mqtt.context.MqttContextHolder;
import io.github.atengk.boot.mqtt.defense.PayloadGuard;
import io.github.atengk.boot.mqtt.exception.SilentAckException;
import io.github.atengk.boot.mqtt.properties.AckMode;
import io.github.atengk.boot.mqtt.properties.AtengMqttProperties;
import io.github.atengk.boot.mqtt.properties.ClientIdStrategy;
import io.github.atengk.boot.mqtt.properties.MqttClientProperties;
import io.github.atengk.boot.mqtt.properties.MqttConsumerProperties;
import io.github.atengk.boot.mqtt.properties.PersistenceType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import io.github.atengk.boot.mqtt.registry.MqttClientHandle;
import io.github.atengk.boot.mqtt.registry.NamedMqttClientRegistry;
import io.github.atengk.boot.mqtt.template.MqttTemplate;
import io.github.atengk.boot.mqtt.test.EmbeddedMqttBroker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.MDC;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.unit.DataSize;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 声明式 @MqttListener 容器、共享订阅与受管线程池隔离调度测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
class MqttListenerContainerTest {

    private EmbeddedMqttBroker broker;
    private NamedMqttClientRegistry clientRegistry;
    private PayloadGuard payloadGuard;
    private ThreadPoolTaskExecutor taskExecutor;
    private MqttTemplate mqttTemplate;
    private MqttListenerEndpointRegistry endpointRegistry;
    private MqttListenerAnnotationBeanPostProcessor postProcessor;
    private MqttContainerManager containerManager;

    public record DeviceMetricPayload(double value, String unit) {}

    public static class SampleListenerBean {
        final List<String> receivedWildcard = new CopyOnWriteArrayList<>();
        final List<String> receivedShared = new CopyOnWriteArrayList<>();
        final List<VariableRecord> receivedVariables = new CopyOnWriteArrayList<>();
        final List<ContextRecord> receivedContexts = new CopyOnWriteArrayList<>();

        final CountDownLatch wildcardLatch = new CountDownLatch(1);
        final CountDownLatch sharedLatch = new CountDownLatch(1);
        final CountDownLatch variableLatch = new CountDownLatch(1);
        final CountDownLatch contextLatch = new CountDownLatch(1);

        record VariableRecord(String deviceId, String metric, String topic, DeviceMetricPayload payload) {}
        record ContextRecord(String traceId, String tenantId, String userId, String threadName) {}

        @MqttListener(topics = "sensor/+/temperature", qos = 1)
        public void onWildcardMessage(String content) {
            receivedWildcard.add(content);
            wildcardLatch.countDown();
        }

        @MqttListener(topics = "cluster/order/event", qos = 1, sharedGroup = "order-group")
        public void onSharedMessage(String content) {
            receivedShared.add(content);
            sharedLatch.countDown();
        }

        @MqttListener(topics = "device/{deviceId}/metric/{metricName}", qos = 1)
        public void onVariableMessage(@MqttVariable("deviceId") String deviceId,
                                      @MqttVariable("metricName") String metric,
                                      @MqttTopic String topic,
                                      DeviceMetricPayload payload) {
            receivedVariables.add(new VariableRecord(deviceId, metric, topic, payload));
            variableLatch.countDown();
        }

        @MqttListener(topics = "app/audit/trace", qos = 1)
        public void onContextMessage(@MqttHeader("X-Trace-Id") String traceHeader,
                                     @MqttHeader("X-Tenant-Id") String tenantHeader) {
            String mdcTrace = MDC.get("traceId");
            String tenant = TenantContextHolder.getTenantId();
            String user = UserContextHolder.get();
            String thread = Thread.currentThread().getName();
            receivedContexts.add(new ContextRecord(mdcTrace, tenant, user, thread));
            contextLatch.countDown();
        }
    }

    private SampleListenerBean listenerBean;

    @BeforeEach
    void setUp(@TempDir Path tempDir) {
        // 1. 启动轻量纯内存 MQTT Broker
        broker = new EmbeddedMqttBroker(0);
        broker.start();

        // 2. 配置 MQTT 客户端
        AtengMqttProperties properties = new AtengMqttProperties();
        properties.setDefaultClient("primary");

        Map<String, MqttClientProperties> clients = new HashMap<>();
        MqttClientProperties primary = new MqttClientProperties();
        primary.setServerUri(broker.getServerUri());
        primary.setClientId("listener-test-primary");
        primary.setClientIdStrategy(ClientIdStrategy.STATIC);
        primary.setCleanStart(true);
        primary.setConnectionTimeout(Duration.ofSeconds(3));
        primary.setPublishTimeout(Duration.ofSeconds(3));
        primary.setMaxPayloadSize(DataSize.ofMegabytes(1));
        primary.setPersistenceType(PersistenceType.MEMORY);
        primary.setProtocolVersion("5.0");
        clients.put("primary", primary);

        properties.setClients(clients);

        // 3. 初始化 ClientRegistry
        clientRegistry = new NamedMqttClientRegistry(properties);
        clientRegistry.initClients();

        // 4. 创建受管线程池
        taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setCorePoolSize(4);
        taskExecutor.setMaxPoolSize(8);
        taskExecutor.setQueueCapacity(500);
        taskExecutor.setThreadNamePrefix("ateng-mqtt-worker-");
        taskExecutor.initialize();

        payloadGuard = new PayloadGuard();
        mqttTemplate = new MqttTemplate(clientRegistry, payloadGuard, null, taskExecutor);

        // 5. 初始化 EndpointRegistry、ContainerManager 与 BeanPostProcessor
        endpointRegistry = new MqttListenerEndpointRegistry(clientRegistry, payloadGuard, taskExecutor);
        containerManager = new MqttContainerManager(endpointRegistry);
        postProcessor = new MqttListenerAnnotationBeanPostProcessor(endpointRegistry);

        // 6. 注册业务监听器 Bean
        listenerBean = new SampleListenerBean();
        postProcessor.postProcessAfterInitialization(listenerBean, "sampleListenerBean");

        // 7. 启动监听器生命周期容器
        endpointRegistry.start();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        TenantContextHolder.clear();
        UserContextHolder.clear();
        MqttContextHolder.clear();

        if (endpointRegistry != null) {
            endpointRegistry.stop();
        }
        if (clientRegistry != null) {
            clientRegistry.destroy();
        }
        if (taskExecutor != null) {
            taskExecutor.shutdown();
        }
        if (broker != null) {
            broker.stop();
        }
    }

    @Test
    @DisplayName("验证通配符主题订阅与消息异步消费")
    void shouldConsumeWildcardTopicMessages() throws Exception {
        mqttTemplate.publish("sensor/living-room/temperature", "23.5");

        boolean completed = listenerBean.wildcardLatch.await(3, TimeUnit.SECONDS);
        assertThat(completed).isTrue();
        assertThat(listenerBean.receivedWildcard).containsExactly("23.5");
    }

    @Test
    @DisplayName("验证声明式共享订阅正确拼接 $share 前缀并消费")
    void shouldSubscribeAndConsumeSharedTopic() throws Exception {
        mqttTemplate.publish("cluster/order/event", "order-payload-1001");

        boolean completed = listenerBean.sharedLatch.await(3, TimeUnit.SECONDS);
        assertThat(completed).isTrue();
        assertThat(listenerBean.receivedShared).containsExactly("order-payload-1001");
    }

    @Test
    @DisplayName("验证 @MqttVariable 路径变量提取与 POJO 载荷智能解析注入")
    void shouldExtractPathVariablesAndPojoPayload() throws Exception {
        DeviceMetricPayload payload = new DeviceMetricPayload(220.5, "V");
        mqttTemplate.publish("device/DEV-007/metric/voltage", payload);

        boolean completed = listenerBean.variableLatch.await(3, TimeUnit.SECONDS);
        assertThat(completed).isTrue();

        assertThat(listenerBean.receivedVariables).hasSize(1);
        SampleListenerBean.VariableRecord record = listenerBean.receivedVariables.get(0);
        assertThat(record.deviceId()).isEqualTo("DEV-007");
        assertThat(record.metric()).isEqualTo("voltage");
        assertThat(record.topic()).isEqualTo("device/DEV-007/metric/voltage");
        assertThat(record.payload().value()).isEqualTo(220.5);
        assertThat(record.payload().unit()).isEqualTo("V");
    }

    @Test
    @DisplayName("验证受管线程池隔离调度与四维上下文透传还原")
    void shouldExecuteInManagedPoolAndRestoreContext() throws Exception {
        MDC.put("traceId", "trace-audit-8888");
        TenantContextHolder.setTenantId("tenant-iot");
        UserContextHolder.set("operator-admin");

        mqttTemplate.publish("app/audit/trace", "trace-msg");

        boolean completed = listenerBean.contextLatch.await(3, TimeUnit.SECONDS);
        assertThat(completed).isTrue();

        assertThat(listenerBean.receivedContexts).hasSize(1);
        SampleListenerBean.ContextRecord record = listenerBean.receivedContexts.get(0);
        assertThat(record.traceId()).isEqualTo("trace-audit-8888");
        assertThat(record.tenantId()).isEqualTo("tenant-iot");
        assertThat(record.userId()).isEqualTo("operator-admin");
        // 验证执行线程属于受管线程池，彻底解放 Paho 网络循环
        assertThat(record.threadName()).startsWith("ateng-mqtt-worker-");
    }

    @Test
    @DisplayName("验证 MqttTemplate.subscribe 与 unsubscribe 编程式动态管理")
    void shouldManageDynamicSubscriptionAndUnsubscription() throws Exception {
        List<String> dynamicMessages = new CopyOnWriteArrayList<>();
        CountDownLatch dynamicLatch = new CountDownLatch(1);

        // 1. 动态订阅
        mqttTemplate.subscribe("dynamic/sensor/+", 1, (topic, payload) -> {
            dynamicMessages.add(new String(payload, StandardCharsets.UTF_8));
            dynamicLatch.countDown();
        });

        Thread.sleep(150);

        // 2. 发送首条消息
        mqttTemplate.publish("dynamic/sensor/temp", "dynamic-temp-1");
        boolean completed = dynamicLatch.await(3, TimeUnit.SECONDS);
        assertThat(completed).isTrue();
        assertThat(dynamicMessages).containsExactly("dynamic-temp-1");
        Thread.sleep(100);

        // 3. 动态退订
        mqttTemplate.unsubscribe("dynamic/sensor/+");
        Thread.sleep(150);

        // 4. 再次发送消息，验证不再触发回调
        mqttTemplate.publish("dynamic/sensor/temp", "dynamic-temp-2");
        Thread.sleep(250);
        assertThat(dynamicMessages).hasSize(1); // 仍旧只有 1 条
    }

    @Test
    @DisplayName("动态流控运维：MqttContainerManager 暂停与恢复指定端点消费调度")
    void shouldPauseAndResumeEndpointViaContainerManager() throws Exception {
        String endpointId = "sampleListenerBean#onWildcardMessage";

        // 1. 验证初始状态为未暂停
        assertThat(containerManager.isPaused(endpointId)).isFalse();

        // 2. 暂停端点消费
        containerManager.pause(endpointId);
        assertThat(containerManager.isPaused(endpointId)).isTrue();

        // 3. 发送消息，验证被暂停忽略，未进入接收列表
        mqttTemplate.publish("sensor/living-room/temperature", "PAUSED_VALUE_99");
        Thread.sleep(300);
        assertThat(listenerBean.receivedWildcard).doesNotContain("PAUSED_VALUE_99");

        // 4. 恢复端点消费
        containerManager.resume(endpointId);
        assertThat(containerManager.isPaused(endpointId)).isFalse();

        // 5. 再次发送消息，验证正常恢复消费
        mqttTemplate.publish("sensor/living-room/temperature", "RESUMED_VALUE_100");
        boolean completed = listenerBean.wildcardLatch.await(3, TimeUnit.SECONDS);
        assertThat(completed).isTrue();
        assertThat(listenerBean.receivedWildcard).contains("RESUMED_VALUE_100");

        // 6. 验证非法入参与不存在端点的快速失败防卫 (Fail-Fast)
        assertThatThrownBy(() -> containerManager.pause(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endpointId must not be blank");
        assertThatThrownBy(() -> containerManager.pause("nonExistingEndpoint"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MQTT listener endpoint not found");
        assertThatThrownBy(() -> containerManager.resume(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("endpointId must not be blank");
        assertThatThrownBy(() -> containerManager.resume("nonExistingEndpoint"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MQTT listener endpoint not found");
    }

    @Test
    @DisplayName("端点运行态巡检：MqttContainerManager 查询订阅主题拓扑与端点健康状态")
    void shouldInspectEndpointsAndSubscribedTopicsViaContainerManager() {
        // 1. 查询端点状态快照
        List<MqttEndpointStatus> statuses = containerManager.getEndpointStatuses();
        assertThat(statuses).isNotEmpty();

        MqttEndpointStatus wildcardStatus = containerManager.getEndpointStatus("sampleListenerBean#onWildcardMessage");
        assertThat(wildcardStatus).isNotNull();
        assertThat(wildcardStatus.endpointId()).isEqualTo("sampleListenerBean#onWildcardMessage");
        assertThat(wildcardStatus.clientName()).isEqualTo("primary");
        assertThat(wildcardStatus.topics()).containsExactly("sensor/+/temperature");
        assertThat(wildcardStatus.qos()).isEqualTo(1);
        assertThat(wildcardStatus.paused()).isFalse();
        assertThat(wildcardStatus.running()).isTrue();
        assertThat(wildcardStatus.targetMethod()).contains("SampleListenerBean#onWildcardMessage");

        // 2. 查询客户端订阅主题列表
        List<String> primaryTopics = containerManager.getSubscribedTopics("primary");
        assertThat(primaryTopics).contains("sensor/+/temperature");

        Map<String, List<String>> allTopics = containerManager.getAllSubscribedTopics();
        assertThat(allTopics).containsKey("primary");
        assertThat(allTopics.get("primary")).contains("sensor/+/temperature");

        assertThat(containerManager.containsEndpoint("sampleListenerBean#onWildcardMessage")).isTrue();
        assertThat(containerManager.containsEndpoint("nonExistingEndpoint")).isFalse();
    }

    @Test
    @DisplayName("断网重连全自愈：MqttListenerEndpointRegistry 自动恢复 Broker 订阅拓扑")
    void shouldResubscribeEndpointsOnClientReconnect() throws Exception {
        // 1. 验证重连自愈能够正常被触发且订阅不丢失
        List<String> topicsBefore = containerManager.getSubscribedTopics("primary");
        assertThat(topicsBefore).isNotEmpty();

        // 2. 模拟断网重连通知 (reconnect = true)
        MqttClientHandle handle = clientRegistry.getClientHandle("primary");
        endpointRegistry.handleConnectComplete(handle, true, broker.getServerUri());

        // 3. 验证重连后订阅列表自愈且不重复膨胀
        List<String> topicsAfter = containerManager.getSubscribedTopics("primary");
        assertThat(topicsAfter).containsExactlyInAnyOrderElementsOf(topicsBefore);

        // 4. 验证重连后消息依然能够顺利推入端点
        mqttTemplate.publish("sensor/balcony/temperature", "HEALED_TEMP_28");
        Thread.sleep(300);
        assertThat(listenerBean.receivedWildcard).contains("HEALED_TEMP_28");
    }

    static class SlowDrainListenerBean {
        final CountDownLatch started = new CountDownLatch(1);
        final AtomicBoolean finished = new AtomicBoolean(false);

        @MqttListener(topics = "drain/virtual/test", qos = 1)
        public void onSlowMessage(String payload) throws InterruptedException {
            started.countDown();
            Thread.sleep(250);
            finished.set(true);
        }
    }

    @Test
    @DisplayName("执行器无关停机排空：SimpleAsyncTaskExecutor 虚拟线程执行器下 stop() 仍阻塞等待在途任务完成")
    void shouldDrainInFlightTasksWithSimpleAsyncTaskExecutor() throws Exception {
        AtengMqttProperties props = new AtengMqttProperties();
        props.setDefaultClient("drain");
        MqttClientProperties clientProps = new MqttClientProperties();
        clientProps.setServerUri(broker.getServerUri());
        clientProps.setClientId("drain-virtual-client");
        clientProps.setClientIdStrategy(ClientIdStrategy.STATIC);
        clientProps.setShutdownTimeout(Duration.ofSeconds(3));
        props.getClients().put("drain", clientProps);

        NamedMqttClientRegistry drainClientRegistry = new NamedMqttClientRegistry(props);
        drainClientRegistry.initClients();

        org.springframework.core.task.SimpleAsyncTaskExecutor virtualLikeExecutor =
                new org.springframework.core.task.SimpleAsyncTaskExecutor("ateng-vt-");
        MqttListenerEndpointRegistry drainEndpointRegistry =
                new MqttListenerEndpointRegistry(drainClientRegistry, new PayloadGuard(), virtualLikeExecutor);
        SlowDrainListenerBean slowBean = new SlowDrainListenerBean();
        new MqttListenerAnnotationBeanPostProcessor(drainEndpointRegistry)
                .postProcessAfterInitialization(slowBean, "slowDrainListenerBean");
        drainEndpointRegistry.start();

        try {
            MqttTemplate template = new MqttTemplate(drainClientRegistry, new PayloadGuard());
            template.publish("drain/virtual/test", "slow-payload", 1);

            assertThat(slowBean.started.await(3, TimeUnit.SECONDS)).isTrue();
            drainEndpointRegistry.stop();
            assertThat(slowBean.finished.get()).isTrue();
        } finally {
            drainEndpointRegistry.stop();
            drainClientRegistry.destroy();
        }
    }

    static class EdgeListenerBean {
        @MqttListener(topics = "edge/guard/test", client = "edge", qos = 1)
        public void onEdgeMessage(String payload) {}
    }

    @Test
    @DisplayName("动态注销守卫：仍被活跃 @MqttListener 绑定的命名客户端默认拒绝注销，暂停或 force=true 后可安全注销")
    void shouldGuardUnregisterWhenActiveListenerBound() {
        MqttClientProperties edgeProps = new MqttClientProperties();
        edgeProps.setServerUri(broker.getServerUri());
        edgeProps.setClientId("edge-bound-client");
        edgeProps.setClientIdStrategy(ClientIdStrategy.STATIC);
        clientRegistry.registerClient("edge", edgeProps);

        EdgeListenerBean edgeBean = new EdgeListenerBean();
        postProcessor.postProcessAfterInitialization(edgeBean, "edgeListenerBean");

        assertThatThrownBy(() -> clientRegistry.unregisterClient("edge"))
                .isInstanceOf(io.github.atengk.boot.core.exception.BusinessException.class)
                .hasMessageContaining("active listener");

        containerManager.pause("edgeListenerBean#onEdgeMessage");
        clientRegistry.unregisterClient("edge");
        assertThat(clientRegistry.containsClient("edge")).isFalse();
    }

    public record ValidDeviceCommand(@NotBlank(message = "deviceId不能为空") String deviceId) {}

    static class ReliabilityListenerBean {
        final List<String> trace = new CopyOnWriteArrayList<>();
        final CountDownLatch orderedDone = new CountDownLatch(4); // msg-1#0, #1, #2 + msg-2#0
        final AtomicInteger msg1Failures = new AtomicInteger(0);
        final AtomicInteger exhaustCount = new AtomicInteger(0);
        final CountDownLatch exhaustDone = new CountDownLatch(3);
        final AtomicInteger validInvoked = new AtomicInteger(0);
        final AtomicInteger silentCount = new AtomicInteger(0);
        final CountDownLatch silentDone = new CountDownLatch(2);

        @MqttListener(topics = "retry/order", qos = 1, concurrency = 1, dltTopic = "retry/order/DLT")
        public void onOrderedMessage(String payload) {
            if ("msg-1".equals(payload)) {
                int attempt = msg1Failures.getAndIncrement();
                trace.add("msg-1#" + attempt);
                orderedDone.countDown();
                if (attempt < 2) {
                    throw new RuntimeException("transient error on attempt " + attempt);
                }
                return;
            }
            if ("msg-exhaust".equals(payload)) {
                int attempt = exhaustCount.getAndIncrement();
                trace.add("msg-exhaust#" + attempt);
                exhaustDone.countDown();
                throw new RuntimeException("persistent transient failure");
            }
            trace.add(payload + "#0");
            orderedDone.countDown();
        }

        @MqttListener(topics = "valid/check", qos = 1, maxRetries = 3, dltTopic = "valid/check/DLT")
        public void onValidCommand(@Valid ValidDeviceCommand command) {
            validInvoked.incrementAndGet();
        }

        @MqttListener(topics = "silent/check", qos = 1, maxRetries = 2, dltTopic = "silent/check/DLT")
        public void onSilent(String payload) {
            silentCount.incrementAndGet();
            silentDone.countDown();
            if ("silent".equals(payload)) {
                throw new SilentAckException("already processed");
            }
            throw new IdempotentException("duplicate idempotent event");
        }
    }

    @Test
    @DisplayName("消费状态机：指数退避重试保序、@Valid 零重试转死信及 SilentAck/Idempotent 静默确认")
    void shouldRetryWithBackoffKeepingStrictOrderingAndRouteValidOrSilentAck() throws Exception {
        MqttConsumerProperties consumerProps = new MqttConsumerProperties();
        consumerProps.setMaxRetries(2);
        consumerProps.setInitialBackoff(Duration.ofMillis(80));
        consumerProps.setBackoffMultiplier(2.0);
        consumerProps.setMaxBackoff(Duration.ofMillis(500));

        ThreadPoolTaskExecutor isolatedExecutor = new ThreadPoolTaskExecutor();
        isolatedExecutor.setCorePoolSize(4);
        isolatedExecutor.setMaxPoolSize(8);
        isolatedExecutor.setThreadNamePrefix("ateng-retry-test-");
        isolatedExecutor.initialize();

        MqttListenerEndpointRegistry retryRegistry = new MqttListenerEndpointRegistry(
                clientRegistry, new PayloadGuard(), isolatedExecutor, null, null, Collections.emptyList(), consumerProps, null);
        ReliabilityListenerBean bean = new ReliabilityListenerBean();
        new MqttListenerAnnotationBeanPostProcessor(retryRegistry).postProcessAfterInitialization(bean, "reliabilityBean");
        retryRegistry.start();

        try {
            // 1. msg-1 失败 2 次并在第 3 次成功，紧跟发送 msg-2 必须在 msg-1 重试全部完成前被信号量保序阻塞
            mqttTemplate.publish("retry/order", "msg-1", 1);
            Thread.sleep(20);
            mqttTemplate.publish("retry/order", "msg-2", 1);

            assertThat(bean.orderedDone.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(bean.trace).containsExactly("msg-1#0", "msg-1#1", "msg-1#2", "msg-2#0");

            // 2. msg-exhaust 耗尽 2 次重试（共 3 次尝试）后转入 DLT
            mqttTemplate.publish("retry/order", "msg-exhaust", 1);
            assertThat(bean.exhaustDone.await(5, TimeUnit.SECONDS)).isTrue();
            long dltDeadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < dltDeadline
                    && broker.getReceivedMessages().stream().noneMatch(m -> "retry/order/DLT".equals(m.getTopic()))) {
                Thread.sleep(30);
            }
            assertThat(broker.getReceivedMessages()).anyMatch(m -> "retry/order/DLT".equals(m.getTopic()));

            // 3. @Valid 校验失败 0 次重试直接转入 DLT
            mqttTemplate.publish("valid/check", "{\"deviceId\":\"\"}", 1);
            long validDltDeadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < validDltDeadline
                    && broker.getReceivedMessages().stream().noneMatch(m -> "valid/check/DLT".equals(m.getTopic()))) {
                Thread.sleep(30);
            }
            assertThat(bean.validInvoked.get()).isZero();
            assertThat(broker.getReceivedMessages()).anyMatch(m -> "valid/check/DLT".equals(m.getTopic()));

            // 4. SilentAckException 与 IdempotentException 0 重试且不转死信
            mqttTemplate.publish("silent/check", "silent", 1);
            mqttTemplate.publish("silent/check", "idempotent", 1);
            assertThat(bean.silentDone.await(3, TimeUnit.SECONDS)).isTrue();
            Thread.sleep(200);
            assertThat(bean.silentCount.get()).isEqualTo(2);
            assertThat(broker.getReceivedMessages()).noneMatch(m -> "silent/check/DLT".equals(m.getTopic()));
        } finally {
            retryRegistry.stop();
            isolatedExecutor.shutdown();
        }
    }

    static class ManualAckListenerBean {
        final List<String> states = new CopyOnWriteArrayList<>();
        final CountDownLatch latch = new CountDownLatch(3);

        @MqttListener(topics = "manual/ack/test", qos = 1, ackMode = AckMode.MANUAL, dltTopic = "manual/ack/DLT")
        public void onManualMessage(String cmd, MqttAcknowledgment ack) {
            if ("ack".equals(cmd)) {
                ack.ack();
                ack.ack();
                ack.nackToDlt();
                states.add("ack:" + ack.isAcknowledged());
            } else if ("nack".equals(cmd)) {
                ack.nackToDlt();
                ack.ack();
                states.add("nack:" + ack.isAcknowledged());
            } else if ("abandon".equals(cmd)) {
                ack.abandonWithoutAck();
                ack.ack();
                states.add("abandon:" + ack.isAcknowledged());
            }
            latch.countDown();
        }
    }

    @Test
    @DisplayName("MANUAL 确认模式：MqttAcknowledgment.ack/nackToDlt/abandonWithoutAck 严格单次幂等生效")
    void shouldSupportManualAckModeWithStrictIdempotency() throws Exception {
        ManualAckListenerBean manualBean = new ManualAckListenerBean();
        postProcessor.postProcessAfterInitialization(manualBean, "manualAckBean");
        // 注册并立即触发端点订阅
        endpointRegistry.resume("manualAckBean#onManualMessage");

        mqttTemplate.publish("manual/ack/test", "ack", 1);
        mqttTemplate.publish("manual/ack/test", "nack", 1);
        mqttTemplate.publish("manual/ack/test", "abandon", 1);

        assertThat(manualBean.latch.await(4, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(250);

        assertThat(manualBean.states).containsExactlyInAnyOrder("ack:true", "nack:true", "abandon:false");
        long dltCount = broker.getReceivedMessages().stream()
                .filter(m -> "manual/ack/DLT".equals(m.getTopic()))
                .count();
        assertThat(dltCount).isEqualTo(1L);
    }

    @Test
    @DisplayName("主题通配符防呆：MqttTopicPattern 对非法位置的 '#' 多级通配符前置快速失败")
    void shouldRejectIllegalHashWildcardPositionInTopicPattern() {
        assertThatThrownBy(() -> new io.github.atengk.boot.mqtt.routing.MqttTopicPattern("devices/#/data"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new io.github.atengk.boot.mqtt.routing.MqttTopicPattern("devices/dev#"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new io.github.atengk.boot.mqtt.routing.MqttTopicPattern("devices/#sub/data"))
                .isInstanceOf(IllegalArgumentException.class);

        io.github.atengk.boot.mqtt.routing.MqttTopicPattern validRoot =
                new io.github.atengk.boot.mqtt.routing.MqttTopicPattern("devices/#");
        assertThat(validRoot.matches("devices/a/b")).isTrue();
    }

    static class OptionFilteredListenerBean {
        final List<String> received = new CopyOnWriteArrayList<>();
        final CountDownLatch peerLatch = new CountDownLatch(1);

        @MqttListener(topics = "nolocal/loop/test", qos = 1, noLocal = true, retainHandling = 2)
        public void onNoLocalMessage(String payload) {
            received.add(payload);
            if ("from-peer".equals(payload)) {
                peerLatch.countDown();
            }
        }
    }

    @Test
    @DisplayName("MQTT 5.0 订阅选项：noLocal=true 过滤同连接自发回环与 retainHandling=2 抑制历史保留重推")
    void shouldEnforceNoLocalAndRetainHandlingOptionsEndToEnd() throws Exception {
        // 1. 先发送历史保留消息，随后订阅 retainHandling=2 端点，应抑制历史保留消息推送
        mqttTemplate.publishRetained("nolocal/loop/test", "pre-retained", 1);
        Thread.sleep(100);

        MqttClientProperties peerProps = new MqttClientProperties();
        peerProps.setServerUri(broker.getServerUri());
        peerProps.setClientId("nolocal-peer");
        peerProps.setClientIdStrategy(ClientIdStrategy.STATIC);
        clientRegistry.registerClient("peer", peerProps);

        OptionFilteredListenerBean optionBean = new OptionFilteredListenerBean();
        postProcessor.postProcessAfterInitialization(optionBean, "optionFilteredBean");

        try {
            // 2. 同连接 primary 自发消息因 noLocal=true 不应回环给自身
            mqttTemplate.publish("nolocal/loop/test", "self-echo", 1);
            Thread.sleep(200);
            assertThat(optionBean.received).doesNotContain("pre-retained", "self-echo");

            // 3. 对端连接 peer 发布消息，应正常被 primary 接收
            mqttTemplate.use("peer").publish("nolocal/loop/test", "from-peer", 1, false);
            assertThat(optionBean.peerLatch.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(optionBean.received).containsExactly("from-peer");
        } finally {
            clientRegistry.unregisterClient("peer");
        }
    }

    static class FlowControlListenerBean {
        final List<String> autoStartMsgs = new CopyOnWriteArrayList<>();
        final List<String> sharedAMsgs = new CopyOnWriteArrayList<>();
        final List<String> sharedBMsgs = new CopyOnWriteArrayList<>();
        final CountDownLatch autoStartLatch = new CountDownLatch(1);
        final CountDownLatch sharedBLatch = new CountDownLatch(1);
        final CountDownLatch sharedALatch = new CountDownLatch(1);

        @MqttListener(topics = "flow/autostart/test", qos = 1, autoStartup = false)
        public void onAutoStartFalse(String payload) {
            autoStartMsgs.add(payload);
            autoStartLatch.countDown();
        }

        @MqttListener(topics = "flow/shared/test", sharedGroup = "grp", qos = 1)
        public void onSharedA(String payload) {
            sharedAMsgs.add(payload);
            sharedALatch.countDown();
        }

        @MqttListener(topics = "flow/shared/test", sharedGroup = "grp", qos = 1)
        public void onSharedB(String payload) {
            sharedBMsgs.add(payload);
            sharedBLatch.countDown();
        }
    }

    @Test
    @DisplayName("引用计数 Broker 真实流控与 autoStartup=false：暂停归零退订、重连不误订阅、恢复重订阅")
    void shouldPerformReferenceCountedBrokerFlowControlAndRespectAutoStartup() throws Exception {
        ThreadPoolTaskExecutor flowExecutor = new ThreadPoolTaskExecutor();
        flowExecutor.setCorePoolSize(2);
        flowExecutor.setMaxPoolSize(4);
        flowExecutor.setThreadNamePrefix("ateng-flow-");
        flowExecutor.initialize();

        MqttListenerEndpointRegistry flowRegistry =
                new MqttListenerEndpointRegistry(clientRegistry, new PayloadGuard(), flowExecutor);
        MqttContainerManager flowManager = new MqttContainerManager(flowRegistry);
        FlowControlListenerBean flowBean = new FlowControlListenerBean();
        new MqttListenerAnnotationBeanPostProcessor(flowRegistry).postProcessAfterInitialization(flowBean, "flowBean");
        flowRegistry.start();

        String autoId = "flowBean#onAutoStartFalse";
        String sharedAId = "flowBean#onSharedA";
        String sharedBId = "flowBean#onSharedB";
        String sharedFilter = "$share/grp/flow/shared/test";

        try {
            // 1. autoStartup=false 初始处于暂停态且不向 Broker 订阅
            assertThat(flowManager.isPaused(autoId)).isTrue();
            assertThat(flowManager.getSubscribedTopics("primary")).doesNotContain("flow/autostart/test");
            assertThat(flowManager.getSubscribedTopics("primary")).contains(sharedFilter);

            mqttTemplate.publish("flow/autostart/test", "ignored-before-resume", 1);
            Thread.sleep(150);
            assertThat(flowBean.autoStartMsgs).isEmpty();

            // 2. 暂停共享过滤器其中一个端点 (refCount: 2 -> 1)，Broker 订阅应继续保持
            flowManager.pause(sharedAId);
            assertThat(flowManager.getSubscribedTopics("primary")).contains(sharedFilter);

            mqttTemplate.publish("flow/shared/test", "to-b-only", 1);
            assertThat(flowBean.sharedBLatch.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(flowBean.sharedAMsgs).isEmpty();
            assertThat(flowBean.sharedBMsgs).contains("to-b-only");

            // 3. 暂停最后一个共享端点 (refCount: 1 -> 0)，向 Broker 真实退订
            flowManager.pause(sharedBId);
            assertThat(flowManager.getSubscribedTopics("primary")).doesNotContain(sharedFilter);

            // 4. 模拟重连自愈：已暂停端点绝不误订阅
            MqttClientHandle handle = clientRegistry.getClientHandle("primary");
            flowRegistry.handleConnectComplete(handle, true, broker.getServerUri());
            assertThat(flowManager.getSubscribedTopics("primary")).doesNotContain(sharedFilter);
            assertThat(flowManager.getSubscribedTopics("primary")).doesNotContain("flow/autostart/test");

            // 5. 恢复端点后真实重新订阅并接收消息
            flowManager.resume(sharedAId);
            flowManager.resume(autoId);
            assertThat(flowManager.isPaused(autoId)).isFalse();
            assertThat(flowManager.getSubscribedTopics("primary")).contains(sharedFilter, "flow/autostart/test");

            mqttTemplate.publish("flow/autostart/test", "delivered-after-resume", 1);
            mqttTemplate.publish("flow/shared/test", "back-to-a", 1);
            assertThat(flowBean.autoStartLatch.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(flowBean.sharedALatch.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(flowBean.autoStartMsgs).containsExactly("delivered-after-resume");
            assertThat(flowBean.sharedAMsgs).contains("back-to-a");
        } finally {
            flowRegistry.stop();
            flowExecutor.shutdown();
        }
    }

    static class InvalidAutoAckWithParamBean {
        @MqttListener(topics = "invalid/auto/ack", ackMode = AckMode.AUTO_AFTER_CONSUME)
        public void onMessage(String payload, MqttAcknowledgment ack) {
        }
    }

    static class ManualTerminalAckBean {
        final AtomicInteger exhaustCount = new AtomicInteger();

        @MqttListener(topics = "manual/valid/fail", qos = 1, ackMode = AckMode.MANUAL, dltTopic = "manual/valid/DLT")
        public void onValidFail(@Valid ValidDeviceCommand command, MqttAcknowledgment ack) {
            // @Valid 校验失败前不会进入方法体，应由框架强制转 DLT 并提交 PUBACK
        }

        @MqttListener(topics = "manual/exhaust/fail", qos = 1, ackMode = AckMode.MANUAL, maxRetries = 1, dltTopic = "manual/exhaust/DLT")
        public void onExhaustFail(String payload, MqttAcknowledgment ack) {
            exhaustCount.incrementAndGet();
            throw new RuntimeException("transient-exhaust");
        }
    }

    static class RestartableRetryBean {
        final AtomicInteger attempts = new AtomicInteger();
        final List<String> delivered = new CopyOnWriteArrayList<>();
        final CountDownLatch recoveredLatch = new CountDownLatch(1);

        @MqttListener(topics = "lifecycle/restart/retry", qos = 1, concurrency = 1, maxRetries = 2)
        public void onMessage(String payload) {
            if ("fail-once".equals(payload) && attempts.incrementAndGet() == 1) {
                throw new RuntimeException("wait-in-retry");
            }
            delivered.add(payload);
            if ("healthy".equals(payload)) {
                recoveredLatch.countDown();
            }
        }
    }

    @Test
    @DisplayName("MANUAL 终结路径兜底确认、Fail-Fast 注解校验与停机取消退避任务释放许可并支持重启")
    void shouldEnforceManualTerminalAckFailFastValidationAndRestartLifecycle() throws Exception {
        ThreadPoolTaskExecutor testExec = new ThreadPoolTaskExecutor();
        testExec.setCorePoolSize(2);
        testExec.setMaxPoolSize(4);
        testExec.setThreadNamePrefix("ateng-hardening-");
        testExec.initialize();

        // 1. AUTO_AFTER_CONSUME 模式声明 MqttAcknowledgment 参数应 Fail-Fast 拒绝注册
        MqttListenerEndpointRegistry invalidRegistry = new MqttListenerEndpointRegistry(clientRegistry, new PayloadGuard(), testExec);
        assertThatThrownBy(() -> new MqttListenerAnnotationBeanPostProcessor(invalidRegistry)
                .postProcessAfterInitialization(new InvalidAutoAckWithParamBean(), "invalidAutoAck"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AUTO_AFTER_CONSUME");

        // 2. MANUAL 模式下 @Valid 失败与重试耗尽均强制转 DLT 且向 Broker 提交 PUBACK，避免重投死循环
        MqttConsumerProperties fastConsumerProps = new MqttConsumerProperties();
        fastConsumerProps.setInitialBackoff(Duration.ofMillis(30));
        fastConsumerProps.setMaxBackoff(Duration.ofMillis(60));
        MqttListenerEndpointRegistry manualRegistry = new MqttListenerEndpointRegistry(
                clientRegistry, new PayloadGuard(), testExec, null, null, List.of(), fastConsumerProps, null);
        ManualTerminalAckBean manualBean = new ManualTerminalAckBean();
        new MqttListenerAnnotationBeanPostProcessor(manualRegistry).postProcessAfterInitialization(manualBean, "manualTerminalBean");
        manualRegistry.start();

        try {
            mqttTemplate.publish("manual/valid/fail", "{\"orderId\":\"\",\"amount\":-5}", 1);
            mqttTemplate.publish("manual/exhaust/fail", "exhaust-payload", 1);

            long deadline = System.currentTimeMillis() + 4000;
            while (System.currentTimeMillis() < deadline
                    && (broker.getReceivedMessages().stream().noneMatch(m -> "manual/valid/DLT".equals(m.getTopic()))
                    || broker.getReceivedMessages().stream().noneMatch(m -> "manual/exhaust/DLT".equals(m.getTopic())))) {
                Thread.sleep(40);
            }
            assertThat(broker.getReceivedMessages()).anyMatch(m -> "manual/valid/DLT".equals(m.getTopic()));
            assertThat(broker.getReceivedMessages()).anyMatch(m -> "manual/exhaust/DLT".equals(m.getTopic()));
            assertThat(manualBean.exhaustCount.get()).isEqualTo(2);
        } finally {
            manualRegistry.stop();
        }

        // 3. 退避等待期间触发 stop()：应立即取消未决退避任务并归还 concurrency=1 信号量，且再次 start() 可正常调度重试恢复
        MqttConsumerProperties slowBackoffProps = new MqttConsumerProperties();
        slowBackoffProps.setInitialBackoff(Duration.ofMillis(1500));
        slowBackoffProps.setMaxBackoff(Duration.ofMillis(2000));
        MqttListenerEndpointRegistry restartRegistry = new MqttListenerEndpointRegistry(
                clientRegistry, new PayloadGuard(), testExec, null, null, List.of(), slowBackoffProps, null);
        RestartableRetryBean restartBean = new RestartableRetryBean();
        new MqttListenerAnnotationBeanPostProcessor(restartRegistry).postProcessAfterInitialization(restartBean, "restartBean");
        restartRegistry.start();

        try {
            mqttTemplate.publish("lifecycle/restart/retry", "fail-once", 1);
            long attemptDeadline = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < attemptDeadline && restartBean.attempts.get() < 1) {
                Thread.sleep(20);
            }
            assertThat(restartBean.attempts.get()).isEqualTo(1);

            // 在 1500ms 退避等待窗口内触发 stop()：不应卡顿也不应泄漏信号量许可
            restartRegistry.stop();
            assertThat(restartRegistry.getEndpointStatuses()).singleElement();

            // 二次启动应重建调度器并正常接收新消息
            restartRegistry.start();
            mqttTemplate.publish("lifecycle/restart/retry", "healthy", 1);
            assertThat(restartBean.recoveredLatch.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(restartBean.delivered).contains("healthy");
        } finally {
            restartRegistry.stop();
            testExec.shutdown();
        }
    }
}
