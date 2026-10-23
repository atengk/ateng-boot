package io.github.atengk.boot.mqtt.listener;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.mqtt.annotation.MqttHeader;
import io.github.atengk.boot.mqtt.annotation.MqttListener;
import io.github.atengk.boot.mqtt.annotation.MqttTopic;
import io.github.atengk.boot.mqtt.annotation.MqttVariable;
import io.github.atengk.boot.mqtt.defense.PayloadGuard;
import io.github.atengk.boot.mqtt.properties.AtengMqttProperties;
import io.github.atengk.boot.mqtt.properties.ClientIdStrategy;
import io.github.atengk.boot.mqtt.properties.MqttClientProperties;
import io.github.atengk.boot.mqtt.properties.PersistenceType;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

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

        // 5. 初始化 EndpointRegistry 与 BeanPostProcessor
        endpointRegistry = new MqttListenerEndpointRegistry(clientRegistry, payloadGuard, taskExecutor);
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

        // 3. 动态退订
        mqttTemplate.unsubscribe("dynamic/sensor/+");
        Thread.sleep(150);

        // 4. 再次发送消息，验证不再触发回调
        mqttTemplate.publish("dynamic/sensor/temp", "dynamic-temp-2");
        Thread.sleep(250);
        assertThat(dynamicMessages).hasSize(1); // 仍旧只有 1 条
    }
}
