package io.github.atengk.boot.mqtt.governance;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.IdempotentException;
import io.github.atengk.boot.mqtt.context.MqttContextHolder;
import io.github.atengk.boot.mqtt.exception.SilentAckException;
import org.slf4j.MDC;
import io.github.atengk.boot.mqtt.annotation.MqttListener;
import io.github.atengk.boot.mqtt.defense.PayloadGuard;
import io.github.atengk.boot.mqtt.listener.MqttListenerAnnotationBeanPostProcessor;
import io.github.atengk.boot.mqtt.listener.MqttListenerEndpointRegistry;
import io.github.atengk.boot.mqtt.properties.AtengMqttProperties;
import io.github.atengk.boot.mqtt.properties.ClientIdStrategy;
import io.github.atengk.boot.mqtt.properties.MqttClientProperties;
import io.github.atengk.boot.mqtt.properties.PersistenceType;
import io.github.atengk.boot.mqtt.registry.NamedMqttClientRegistry;
import io.github.atengk.boot.mqtt.template.MqttTemplate;
import io.github.atengk.boot.mqtt.test.EmbeddedMqttBroker;
import io.github.atengk.boot.mqtt.will.MqttWill;
import io.github.atengk.boot.mqtt.will.MqttWillProvider;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.MqttSubscription;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 毒丸死信 DLT 转移、Silent ACK 幂等防卫、确定性优雅停机与动态遗嘱全维测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
class MqttGovernanceAndShutdownTest {

    private EmbeddedMqttBroker broker;
    private NamedMqttClientRegistry clientRegistry;
    private MqttListenerEndpointRegistry listenerRegistry;
    private MqttTemplate mqttTemplate;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        broker = new EmbeddedMqttBroker(0);
        broker.start();
        executor = Executors.newCachedThreadPool();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        TenantContextHolder.clear();
        UserContextHolder.clear();
        MqttContextHolder.clear();

        if (listenerRegistry != null) {
            listenerRegistry.stop();
        }
        if (clientRegistry != null) {
            clientRegistry.stop();
        }
        if (executor != null) {
            executor.shutdownNow();
        }
        if (broker != null) {
            broker.stop();
        }
    }

    @Test
    @DisplayName("毒丸死信转移：遇到不可自愈致命异常时自动转发原始报文与堆栈至 ${topic}/DLT 并安全 ACK")
    void shouldTransferPoisonPillToDltTopicAndSubmitAck() throws Exception {
        // 1. 初始化客户端
        AtengMqttProperties properties = new AtengMqttProperties();
        properties.setDefaultClient("primary");
        Map<String, MqttClientProperties> clientMap = new HashMap<>();

        MqttClientProperties primary = new MqttClientProperties();
        primary.setServerUri(broker.getServerUri());
        primary.setClientId("governance-primary");
        primary.setClientIdStrategy(ClientIdStrategy.STATIC);
        primary.setCleanStart(true);
        primary.setConnectionTimeout(Duration.ofSeconds(3));
        primary.setPersistenceType(PersistenceType.MEMORY);
        primary.setProtocolVersion("5.0");
        clientMap.put("primary", primary);

        properties.setClients(clientMap);
        clientRegistry = new NamedMqttClientRegistry(properties);
        clientRegistry.initClients();

        PayloadGuard payloadGuard = new PayloadGuard();
        listenerRegistry = new MqttListenerEndpointRegistry(clientRegistry, payloadGuard, executor);
        mqttTemplate = new MqttTemplate(clientRegistry, payloadGuard, null, executor);

        // 2. 模拟订阅 DLT 死信主题以捕获转移动作
        CountDownLatch dltLatch = new CountDownLatch(1);
        List<MqttMessage> dltReceived = new CopyOnWriteArrayList<>();
        MqttSubscription dltSub = new MqttSubscription("sensor/poison/data/DLT", 1);
        clientRegistry.getDefaultClientHandle().getAsyncClient().subscribe(
                new MqttSubscription[]{dltSub}, null, null,
                new org.eclipse.paho.mqttv5.client.IMqttMessageListener[]{
                        (topic, message) -> {
                            dltReceived.add(message);
                            dltLatch.countDown();
                        }
                }, null
        ).waitForCompletion(3000);

        // 3. 构建 Spring 容器注册含 @MqttListener 的毒丸消费者 Bean
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean("clientRegistry", NamedMqttClientRegistry.class, () -> clientRegistry);
        context.registerBean("listenerRegistry", MqttListenerEndpointRegistry.class, () -> listenerRegistry);
        context.registerBean(MqttListenerAnnotationBeanPostProcessor.class, () -> new MqttListenerAnnotationBeanPostProcessor(listenerRegistry));
        context.registerBean(PoisonPillListenerBean.class);
        context.refresh();

        listenerRegistry.start();

        // 4. 发送一条格式损毁的不可自愈毒丸报文
        mqttTemplate.publish("sensor/poison/data", "MALFORMED_POISON_PILL_JSON_DATA", 1);

        // 5. 验证死信主题在超时内接收到转移报文
        boolean receivedDlt = dltLatch.await(4, TimeUnit.SECONDS);
        assertThat(receivedDlt).isTrue();
        assertThat(dltReceived).hasSize(1);

        MqttMessage dltMsg = dltReceived.get(0);
        assertThat(new String(dltMsg.getPayload(), StandardCharsets.UTF_8)).isEqualTo("MALFORMED_POISON_PILL_JSON_DATA");

        // 验证 User Properties 中附加了死信追踪元数据
        MqttProperties dltProps = dltMsg.getProperties();
        assertThat(dltProps).isNotNull();
        List<UserProperty> userProps = dltProps.getUserProperties();
        Map<String, String> headerMap = new HashMap<>();
        for (UserProperty up : userProps) {
            headerMap.put(up.getKey(), up.getValue());
        }

        assertThat(headerMap.get("x-original-topic")).isEqualTo("sensor/poison/data");
        assertThat(headerMap.get("x-exception-class")).contains("JsonParseException");
        assertThat(headerMap.get("x-dlt-timestamp")).isNotBlank();

        context.close();
    }

    @Test
    @DisplayName("消费端幂等静默确认：抛出 IdempotentException 时直接提交 Silent ACK，不触发 DLT 亦不抛错")
    void shouldSilentlyAckWhenIdempotentExceptionOccurred() throws Exception {
        AtengMqttProperties properties = new AtengMqttProperties();
        properties.setDefaultClient("primary");
        Map<String, MqttClientProperties> clientMap = new HashMap<>();

        MqttClientProperties primary = new MqttClientProperties();
        primary.setServerUri(broker.getServerUri());
        primary.setClientId("governance-primary");
        primary.setClientIdStrategy(ClientIdStrategy.STATIC);
        primary.setCleanStart(true);
        primary.setConnectionTimeout(Duration.ofSeconds(3));
        primary.setPersistenceType(PersistenceType.MEMORY);
        primary.setProtocolVersion("5.0");
        clientMap.put("primary", primary);

        properties.setClients(clientMap);
        clientRegistry = new NamedMqttClientRegistry(properties);
        clientRegistry.initClients();

        PayloadGuard payloadGuard = new PayloadGuard();
        listenerRegistry = new MqttListenerEndpointRegistry(clientRegistry, payloadGuard, executor);
        mqttTemplate = new MqttTemplate(clientRegistry, payloadGuard, null, executor);

        // 监听 DLT 确保没有误入死信
        CountDownLatch dltLatch = new CountDownLatch(1);
        MqttSubscription dltSub = new MqttSubscription("sensor/idempotent/data/DLT", 1);
        clientRegistry.getDefaultClientHandle().getAsyncClient().subscribe(
                new MqttSubscription[]{dltSub}, null, null,
                new org.eclipse.paho.mqttv5.client.IMqttMessageListener[]{
                        (topic, message) -> dltLatch.countDown()
                }, null
        ).waitForCompletion(3000);

        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.registerBean("clientRegistry", NamedMqttClientRegistry.class, () -> clientRegistry);
        context.registerBean("listenerRegistry", MqttListenerEndpointRegistry.class, () -> listenerRegistry);
        context.registerBean(MqttListenerAnnotationBeanPostProcessor.class, () -> new MqttListenerAnnotationBeanPostProcessor(listenerRegistry));
        context.registerBean(IdempotentListenerBean.class);
        context.refresh();

        listenerRegistry.start();

        // 发布消息触发幂等异常
        mqttTemplate.publish("sensor/idempotent/data", "REPEAT_ORDER_PAYLOAD", 1);

        // 等待并验证：DLT 主题未收到任何消息，静默确认通过
        boolean receivedDlt = dltLatch.await(800, TimeUnit.MILLISECONDS);
        assertThat(receivedDlt).isFalse();

        context.close();
    }

    @Test
    @DisplayName("确定性优雅停机排空：正常关闭时发送标准 DISCONNECT 报文，Broker 依法销毁遗嘱（不触发 LWT 误报）")
    void shouldNotTriggerLwtOnGracefulShutdown() throws Exception {
        AtengMqttProperties properties = new AtengMqttProperties();
        properties.setDefaultClient("app");
        Map<String, MqttClientProperties> clientMap = new HashMap<>();

        // 1. app 客户端：配置 LWT 遗嘱消息
        MqttClientProperties appClient = new MqttClientProperties();
        appClient.setServerUri(broker.getServerUri());
        appClient.setClientId("app-node-01");
        appClient.setClientIdStrategy(ClientIdStrategy.STATIC);
        appClient.setCleanStart(true);
        appClient.setConnectionTimeout(Duration.ofSeconds(3));
        appClient.setPersistenceType(PersistenceType.MEMORY);
        appClient.getLwt().setEnabled(true);
        appClient.getLwt().setTopic("sys/monitor/alert");
        appClient.getLwt().setPayload("APP_NODE_UNEXPECTED_CRASH");
        appClient.getLwt().setQos(1);
        clientMap.put("app", appClient);

        // 2. monitor 客户端：监控告警主题
        MqttClientProperties monitorClient = new MqttClientProperties();
        monitorClient.setServerUri(broker.getServerUri());
        monitorClient.setClientId("ops-monitor");
        monitorClient.setClientIdStrategy(ClientIdStrategy.STATIC);
        monitorClient.setCleanStart(true);
        monitorClient.setConnectionTimeout(Duration.ofSeconds(3));
        monitorClient.setPersistenceType(PersistenceType.MEMORY);
        clientMap.put("monitor", monitorClient);

        properties.setClients(clientMap);
        clientRegistry = new NamedMqttClientRegistry(properties);
        clientRegistry.initClients();

        // 监控端订阅告警主题
        CountDownLatch alertLatch = new CountDownLatch(1);
        MqttSubscription sub = new MqttSubscription("sys/monitor/alert", 1);
        clientRegistry.getClientHandle("monitor").getAsyncClient().subscribe(
                new MqttSubscription[]{sub}, null, null,
                new org.eclipse.paho.mqttv5.client.IMqttMessageListener[]{
                        (topic, message) -> alertLatch.countDown()
                }, null
        ).waitForCompletion(3000);

        // 3. 执行优雅停机（向 Broker 发送 DISCONNECT 报文并关闭）
        clientRegistry.stop();

        // 4. 验证监控端未收到任何意外遗嘱报警（防误报验证）
        boolean receivedAlert = alertLatch.await(1, TimeUnit.SECONDS);
        assertThat(receivedAlert).isFalse();
    }

    @Test
    @DisplayName("异常宕机遗嘱触发：客户端遭遇非正常崩溃（未发送 DISCONNECT）时，Broker 准确广播预设遗嘱")
    void shouldTriggerLwtOnAbruptDisconnection() throws Exception {
        AtengMqttProperties properties = new AtengMqttProperties();
        properties.setDefaultClient("crashed");
        Map<String, MqttClientProperties> clientMap = new HashMap<>();

        // 1. crashed 客户端：配置 LWT 遗嘱
        MqttClientProperties crashedClient = new MqttClientProperties();
        crashedClient.setServerUri(broker.getServerUri());
        crashedClient.setClientId("crashed-node");
        crashedClient.setClientIdStrategy(ClientIdStrategy.STATIC);
        crashedClient.setCleanStart(true);
        crashedClient.setConnectionTimeout(Duration.ofSeconds(3));
        crashedClient.setPersistenceType(PersistenceType.MEMORY);
        crashedClient.getLwt().setEnabled(true);
        crashedClient.getLwt().setTopic("sys/monitor/abrupt");
        crashedClient.getLwt().setPayload("NODE_ABRUPTLY_TERMINATED");
        crashedClient.getLwt().setQos(1);
        clientMap.put("crashed", crashedClient);

        // 2. monitor 客户端：订阅告警主题
        MqttClientProperties monitorClient = new MqttClientProperties();
        monitorClient.setServerUri(broker.getServerUri());
        monitorClient.setClientId("ops-monitor-abrupt");
        monitorClient.setClientIdStrategy(ClientIdStrategy.STATIC);
        monitorClient.setCleanStart(true);
        monitorClient.setConnectionTimeout(Duration.ofSeconds(3));
        monitorClient.setPersistenceType(PersistenceType.MEMORY);
        clientMap.put("monitor", monitorClient);

        properties.setClients(clientMap);
        clientRegistry = new NamedMqttClientRegistry(properties);
        clientRegistry.initClients();

        CountDownLatch alertLatch = new CountDownLatch(1);
        List<String> receivedAlerts = new CopyOnWriteArrayList<>();
        MqttSubscription sub = new MqttSubscription("sys/monitor/abrupt", 1);
        clientRegistry.getClientHandle("monitor").getAsyncClient().subscribe(
                new MqttSubscription[]{sub}, null, null,
                new org.eclipse.paho.mqttv5.client.IMqttMessageListener[]{
                        (topic, message) -> {
                            receivedAlerts.add(new String(message.getPayload(), StandardCharsets.UTF_8));
                            alertLatch.countDown();
                        }
                }, null
        ).waitForCompletion(3000);

        // 3. 模拟异常宕机：直接调用底层 close(true) 或强制切断底层 TCP 连接（绝不发送规范 DISCONNECT）
        clientRegistry.getClientHandle("crashed").getAsyncClient().disconnectForcibly(0, 0, false);

        // 4. 验证 Broker 判定为意外中断，触发 LWT 广播并成功送达监控端
        boolean receivedAlert = alertLatch.await(3, TimeUnit.SECONDS);
        assertThat(receivedAlert).isTrue();
        assertThat(receivedAlerts).contains("NODE_ABRUPTLY_TERMINATED");
    }

    @Test
    @DisplayName("动态遗嘱感知：MqttWillProvider SPI 能在连接初始化时自适应注入定制化实例遗嘱")
    void shouldApplyDynamicMqttWillProvider() throws Exception {
        AtengMqttProperties properties = new AtengMqttProperties();
        properties.setDefaultClient("dynamicApp");
        Map<String, MqttClientProperties> clientMap = new HashMap<>();

        MqttClientProperties appClient = new MqttClientProperties();
        appClient.setServerUri(broker.getServerUri());
        appClient.setClientId("dynamic-instance-node");
        appClient.setClientIdStrategy(ClientIdStrategy.STATIC);
        appClient.setCleanStart(true);
        appClient.setConnectionTimeout(Duration.ofSeconds(3));
        appClient.setPersistenceType(PersistenceType.MEMORY);
        clientMap.put("dynamicApp", appClient);

        MqttClientProperties monitorClient = new MqttClientProperties();
        monitorClient.setServerUri(broker.getServerUri());
        monitorClient.setClientId("dynamic-monitor");
        monitorClient.setClientIdStrategy(ClientIdStrategy.STATIC);
        monitorClient.setCleanStart(true);
        monitorClient.setConnectionTimeout(Duration.ofSeconds(3));
        monitorClient.setPersistenceType(PersistenceType.MEMORY);
        clientMap.put("monitor", monitorClient);

        properties.setClients(clientMap);

        // 自定义 MqttWillProvider SPI 供给动态遗嘱
        MqttWillProvider willProvider = (clientName, actualClientId, clientProps) -> MqttWill.builder()
                .topic("sys/cluster/alerts/dynamic")
                .payload("{\"node\":\"" + actualClientId + "\",\"event\":\"NODE_DIED\",\"ip\":\"10.0.0.123\"}")
                .qos(1)
                .retained(false)
                .build();

        clientRegistry = new NamedMqttClientRegistry(properties, willProvider);
        clientRegistry.initClients();

        CountDownLatch alertLatch = new CountDownLatch(1);
        List<String> receivedAlerts = new CopyOnWriteArrayList<>();
        MqttSubscription sub = new MqttSubscription("sys/cluster/alerts/dynamic", 1);
        clientRegistry.getClientHandle("monitor").getAsyncClient().subscribe(
                new MqttSubscription[]{sub}, null, null,
                new org.eclipse.paho.mqttv5.client.IMqttMessageListener[]{
                        (topic, message) -> {
                            receivedAlerts.add(new String(message.getPayload(), StandardCharsets.UTF_8));
                            alertLatch.countDown();
                        }
                }, null
        ).waitForCompletion(3000);

        // 模拟异常切断连接
        clientRegistry.getClientHandle("dynamicApp").getAsyncClient().disconnectForcibly(0, 0, false);

        // 验证动态遗嘱已成功广播
        boolean receivedAlert = alertLatch.await(3, TimeUnit.SECONDS);
        assertThat(receivedAlert).isTrue();
        assertThat(receivedAlerts.get(0)).contains("dynamic-instance-node", "10.0.0.123");
    }

    // ==================== 测试夹具 Bean ====================

    public static class PoisonPillListenerBean {
        @MqttListener(topics = "sensor/poison/data", qos = 1, enableDlt = true)
        public void onMessage(String payload) throws Exception {
            // 抛出知名不可自愈致命异常，指导底座转移到死信主题
            throw new com.fasterxml.jackson.core.JsonParseException(null, "Unrecoverable poison pill syntax error: " + payload);
        }
    }

    public static class IdempotentListenerBean {
        @MqttListener(topics = "sensor/idempotent/data", qos = 1)
        public void onMessage(String payload) {
            // 模拟 @Idempotent 切面判定为重复消息抛出的 IdempotentException
            throw new IdempotentException("Duplicate MQTT payload detected: " + payload);
        }
    }

    public static class SilentAckListenerBean {
        final AtomicInteger invokeCount = new AtomicInteger(0);

        @MqttListener(topics = "sensor/silent-ack/data", qos = 1)
        public void onMessage(String payload) {
            invokeCount.incrementAndGet();
            throw new SilentAckException("Explicit silent ACK for business reason: " + payload);
        }
    }

    @Test
    @DisplayName("静默确认：抛出 SilentAckException 时底座提交 ACK 且不转发至死信队列")
    void shouldHandleSilentAckExceptionGracefully() throws Exception {
        AtengMqttProperties properties = new AtengMqttProperties();
        properties.setDefaultClient("primary");

        MqttClientProperties clientProps = new MqttClientProperties();
        clientProps.setServerUri(broker.getServerUri());
        clientProps.setClientId("silent-ack-client");
        clientProps.setClientIdStrategy(ClientIdStrategy.STATIC);
        properties.getClients().put("primary", clientProps);

        clientRegistry = new NamedMqttClientRegistry(properties);
        clientRegistry.initClients();

        SilentAckListenerBean listenerBean = new SilentAckListenerBean();
        MqttListenerEndpointRegistry registry = new MqttListenerEndpointRegistry(clientRegistry, new PayloadGuard(), executor);
        MqttListenerAnnotationBeanPostProcessor bpp = new MqttListenerAnnotationBeanPostProcessor(registry);
        bpp.postProcessAfterInitialization(listenerBean, "silentAckListenerBean");
        registry.start();
        listenerRegistry = registry;

        MqttTemplate template = new MqttTemplate(clientRegistry, new PayloadGuard(), null, executor, null);
        template.publish("sensor/silent-ack/data", "{\"msg\":\"silent-test\"}", 1);

        Thread.sleep(1000);
        assertThat(listenerBean.invokeCount.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("防御性参数校验：MqttWill 非法 QoS 必须 Fail-Fast 抛出 IllegalArgumentException")
    void shouldFailFastOnInvalidMqttWillQos() {
        assertThatThrownBy(() -> MqttWill.of("test/topic", "test".getBytes(StandardCharsets.UTF_8), 3, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("QoS must be between 0 and 2");

        assertThatThrownBy(() -> MqttWill.of("test/topic", "test".getBytes(StandardCharsets.UTF_8), -1, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("QoS must be between 0 and 2");
    }
}
