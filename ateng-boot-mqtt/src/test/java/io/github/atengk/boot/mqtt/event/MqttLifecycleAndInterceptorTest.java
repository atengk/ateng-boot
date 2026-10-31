package io.github.atengk.boot.mqtt.event;

import io.github.atengk.boot.mqtt.annotation.MqttHeader;
import io.github.atengk.boot.mqtt.annotation.MqttListener;
import io.github.atengk.boot.mqtt.annotation.MqttTopic;
import io.github.atengk.boot.mqtt.codec.CompositeMqttMessageConverter;
import io.github.atengk.boot.mqtt.defense.PayloadGuard;
import io.github.atengk.boot.mqtt.interceptor.MqttMessageInterceptor;
import io.github.atengk.boot.mqtt.listener.MqttConnectionEventDispatcher;
import io.github.atengk.boot.mqtt.listener.MqttConnectionEventListener;
import io.github.atengk.boot.mqtt.listener.MqttListenerAnnotationBeanPostProcessor;
import io.github.atengk.boot.mqtt.listener.MqttListenerEndpointRegistry;
import io.github.atengk.boot.mqtt.properties.AtengMqttProperties;
import io.github.atengk.boot.mqtt.properties.ClientIdStrategy;
import io.github.atengk.boot.mqtt.properties.MqttClientProperties;
import io.github.atengk.boot.mqtt.properties.PersistenceType;
import io.github.atengk.boot.mqtt.registry.NamedMqttClientRegistry;
import io.github.atengk.boot.mqtt.template.MqttTemplate;
import io.github.atengk.boot.mqtt.test.EmbeddedMqttBroker;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.unit.DataSize;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MQTT 连接生命周期事件总线、生命周期 SPI、切面拦截器与全量标头 Map 注入测试套件
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class MqttLifecycleAndInterceptorTest {

    private EmbeddedMqttBroker broker;
    private ExecutorService executor;

    @BeforeEach
    void setUp() {
        broker = new EmbeddedMqttBroker(0);
        broker.start();
        executor = Executors.newCachedThreadPool();
    }

    @AfterEach
    void tearDown() {
        if (executor != null) {
            executor.shutdownNow();
        }
        if (broker != null) {
            broker.stop();
        }
    }

    @Test
    @DisplayName("连接生命周期领域事件总线：验证 MqttConnectedEvent、MqttDisconnectedEvent 与 MqttConnectionEventListener SPI 回调")
    void shouldPublishConnectionEventsAndInvokeSpi() throws Exception {
        List<MqttConnectionEvent> receivedEvents = new CopyOnWriteArrayList<>();
        List<String> spiInvocations = new CopyOnWriteArrayList<>();

        // 自定义 SPI 实现
        MqttConnectionEventListener testListener = new MqttConnectionEventListener() {
            @Override
            public void onConnected(MqttConnectedEvent event) {
                spiInvocations.add("connected:" + event.getClientName() + ":" + event.isReconnect());
            }

            @Override
            public void onDisconnected(MqttDisconnectedEvent event) {
                spiInvocations.add("disconnected:" + event.getClientName());
            }

            @Override
            public void onConnectionLost(MqttConnectionLostEvent event) {
                spiInvocations.add("lost:" + event.getClientName());
            }
        };

        MqttConnectionEventDispatcher dispatcher = new MqttConnectionEventDispatcher(List.of(testListener));

        // 模拟 Spring ApplicationEventPublisher
        ApplicationEventPublisher publisher = new ApplicationEventPublisher() {
            @Override
            public void publishEvent(ApplicationEvent event) {
                if (event instanceof MqttConnectionEvent connEvent) {
                    receivedEvents.add(connEvent);
                    if (event instanceof MqttConnectedEvent ce) {
                        dispatcher.onConnected(ce);
                    } else if (event instanceof MqttDisconnectedEvent de) {
                        dispatcher.onDisconnected(de);
                    } else if (event instanceof MqttConnectionLostEvent cle) {
                        dispatcher.onConnectionLost(cle);
                    }
                }
            }

            @Override
            public void publishEvent(Object event) {
                if (event instanceof ApplicationEvent appEvent) {
                    publishEvent(appEvent);
                }
            }
        };

        AtengMqttProperties properties = new AtengMqttProperties();
        properties.setDefaultClient("primary");
        Map<String, MqttClientProperties> clients = new HashMap<>();

        MqttClientProperties primaryProps = new MqttClientProperties();
        primaryProps.setServerUri(broker.getServerUri());
        primaryProps.setClientId("test-event-client");
        primaryProps.setClientIdStrategy(ClientIdStrategy.STATIC);
        primaryProps.setCleanStart(true);
        primaryProps.setConnectionTimeout(Duration.ofSeconds(3));
        primaryProps.setShutdownTimeout(Duration.ofSeconds(1));
        primaryProps.setProtocolVersion("5.0");
        clients.put("primary", primaryProps);
        properties.setClients(clients);

        NamedMqttClientRegistry registry = new NamedMqttClientRegistry(properties);
        registry.setApplicationEventPublisher(publisher);

        // 1. 初始化客户端连接并断言初次连接事件 (reconnect=false)
        registry.initClients();
        Thread.sleep(150);

        assertThat(receivedEvents).hasSize(1);
        assertThat(receivedEvents.get(0)).isInstanceOf(MqttConnectedEvent.class);
        MqttConnectedEvent connectedEvent = (MqttConnectedEvent) receivedEvents.get(0);
        assertThat(connectedEvent.getClientName()).isEqualTo("primary");
        assertThat(connectedEvent.isReconnect()).isFalse();
        assertThat(connectedEvent.getClientId()).isEqualTo("test-event-client");
        assertThat(spiInvocations).contains("connected:primary:false");

        // 2. 模拟断网自愈重连事件 (reconnect=true)
        registry.publishConnectedEvent("primary", broker.getServerUri(), "test-event-client", true);
        assertThat(receivedEvents).hasSize(2);
        MqttConnectedEvent reconnectEvent = (MqttConnectedEvent) receivedEvents.get(1);
        assertThat(reconnectEvent.isReconnect()).isTrue();
        assertThat(spiInvocations).contains("connected:primary:true");

        // 3. 模拟异常掉线事件
        Throwable cause = new RuntimeException("Connection reset by peer");
        registry.publishConnectionLostEvent("primary", broker.getServerUri(), "test-event-client", cause);
        assertThat(receivedEvents).hasSize(3);
        assertThat(receivedEvents.get(2)).isInstanceOf(MqttConnectionLostEvent.class);
        MqttConnectionLostEvent lostEvent = (MqttConnectionLostEvent) receivedEvents.get(2);
        assertThat(lostEvent.getCause()).isEqualTo(cause);
        assertThat(spiInvocations).contains("lost:primary");

        // 4. 正常下线注销客户端，断言断开连接事件
        registry.destroy();
        Thread.sleep(150);

        assertThat(receivedEvents).hasSize(4);
        assertThat(receivedEvents.get(3)).isInstanceOf(MqttDisconnectedEvent.class);
        MqttDisconnectedEvent disconnectedEvent = (MqttDisconnectedEvent) receivedEvents.get(3);
        assertThat(disconnectedEvent.getClientName()).isEqualTo("primary");
        assertThat(spiInvocations).contains("disconnected:primary");
    }

    @Test
    @DisplayName("消息切面拦截器 (MqttMessageInterceptor)：全流程 prePublish、postPublish、preConsume、postConsume 链式拦截与逆序执行")
    void shouldExecuteMessageInterceptorPipelineInOrder() throws Exception {
        List<String> executionLog = new CopyOnWriteArrayList<>();

        // 定义两个拦截器以验证链式与逆序执行
        MqttMessageInterceptor interceptorA = new MqttMessageInterceptor() {
            @Override
            public boolean prePublish(String clientName, String topic, byte[] payload, int qos, boolean retained, Map<String, String> userProperties) {
                executionLog.add("A:prePublish:" + topic);
                userProperties.put("X-Audit-Sign", "SEC-A-TOKEN");
                return true;
            }

            @Override
            public void postPublish(String clientName, String topic, byte[] payload, int qos, boolean retained, Map<String, String> userProperties, Throwable ex) {
                executionLog.add("A:postPublish:" + topic);
            }

            @Override
            public boolean preConsume(String clientName, String endpointId, String topic, MqttMessage message, Map<String, String> headers) {
                executionLog.add("A:preConsume:" + topic);
                return true;
            }

            @Override
            public void postConsume(String clientName, String endpointId, String topic, MqttMessage message, Map<String, String> headers, Throwable ex) {
                executionLog.add("A:postConsume:" + topic);
            }
        };

        MqttMessageInterceptor interceptorB = new MqttMessageInterceptor() {
            @Override
            public boolean prePublish(String clientName, String topic, byte[] payload, int qos, boolean retained, Map<String, String> userProperties) {
                executionLog.add("B:prePublish:" + topic);
                userProperties.put("X-Trace-B", "TRACE-B-999");
                return true;
            }

            @Override
            public void postPublish(String clientName, String topic, byte[] payload, int qos, boolean retained, Map<String, String> userProperties, Throwable ex) {
                executionLog.add("B:postPublish:" + topic);
            }

            @Override
            public boolean preConsume(String clientName, String endpointId, String topic, MqttMessage message, Map<String, String> headers) {
                executionLog.add("B:preConsume:" + topic);
                return true;
            }

            @Override
            public void postConsume(String clientName, String endpointId, String topic, MqttMessage message, Map<String, String> headers, Throwable ex) {
                executionLog.add("B:postConsume:" + topic);
            }
        };

        List<MqttMessageInterceptor> interceptors = List.of(interceptorA, interceptorB);

        // 初始化客户端与 Template、Registry
        AtengMqttProperties properties = new AtengMqttProperties();
        properties.setDefaultClient("primary");
        Map<String, MqttClientProperties> clients = new HashMap<>();

        MqttClientProperties primaryProps = new MqttClientProperties();
        primaryProps.setServerUri(broker.getServerUri());
        primaryProps.setClientId("interceptor-client");
        primaryProps.setClientIdStrategy(ClientIdStrategy.STATIC);
        primaryProps.setCleanStart(true);
        primaryProps.setConnectionTimeout(Duration.ofSeconds(3));
        primaryProps.setMaxPayloadSize(DataSize.ofMegabytes(1));
        primaryProps.setProtocolVersion("5.0");
        clients.put("primary", primaryProps);
        properties.setClients(clients);

        NamedMqttClientRegistry clientRegistry = new NamedMqttClientRegistry(properties);
        clientRegistry.initClients();

        PayloadGuard payloadGuard = new PayloadGuard();
        MqttTemplate mqttTemplate = new MqttTemplate(clientRegistry, payloadGuard, null, executor, null, null, null, interceptors);
        MqttListenerEndpointRegistry listenerRegistry = new MqttListenerEndpointRegistry(clientRegistry, payloadGuard, executor, null, null, interceptors);

        // 订阅测试主题并接收
        String topic = "test/interceptor/pipeline";
        PipelineListenerBean listenerBean = new PipelineListenerBean();
        MqttListenerAnnotationBeanPostProcessor bpp = new MqttListenerAnnotationBeanPostProcessor(listenerRegistry);
        bpp.postProcessAfterInitialization(listenerBean, "pipelineListenerBean");
        listenerRegistry.start();
        Thread.sleep(200);

        // 发布消息
        mqttTemplate.publish(topic, "PAYLOAD_INTERCEPTED");

        boolean received = listenerBean.latch.await(3, TimeUnit.SECONDS);
        assertThat(received).isTrue();
        Thread.sleep(100);

        // 验证标头动态注入
        assertThat(listenerBean.receivedHeaders).containsEntry("X-Audit-Sign", "SEC-A-TOKEN");
        assertThat(listenerBean.receivedHeaders).containsEntry("X-Trace-B", "TRACE-B-999");

        // 验证执行时序：
        // 发布顺序：A:prePublish -> B:prePublish
        // 发布逆序：B:postPublish -> A:postPublish
        // 消费顺序：A:preConsume -> B:preConsume
        // 消费逆序：B:postConsume -> A:postConsume
        assertThat(executionLog).containsSubsequence("A:prePublish:" + topic, "B:prePublish:" + topic);
        assertThat(executionLog).containsSubsequence("B:postPublish:" + topic, "A:postPublish:" + topic);
        assertThat(executionLog).containsSubsequence("A:preConsume:" + topic, "B:preConsume:" + topic);
        assertThat(executionLog).containsSubsequence("B:postConsume:" + topic, "A:postConsume:" + topic);

        listenerRegistry.stop();
        clientRegistry.destroy();
    }

    @Test
    @DisplayName("消息切面拦截器前置拦截熔断：prePublish 返回 false 时终止发信，preConsume 返回 false 时拦截消费")
    void shouldAbortWhenInterceptorReturnsFalse() throws Exception {
        AtomicBoolean allowPublish = new AtomicBoolean(false);
        AtomicBoolean allowConsume = new AtomicBoolean(false);

        MqttMessageInterceptor guardInterceptor = new MqttMessageInterceptor() {
            @Override
            public boolean prePublish(String clientName, String topic, byte[] payload, int qos, boolean retained, Map<String, String> userProperties) {
                return allowPublish.get();
            }

            @Override
            public boolean preConsume(String clientName, String endpointId, String topic, MqttMessage message, Map<String, String> headers) {
                return allowConsume.get();
            }
        };

        AtengMqttProperties properties = new AtengMqttProperties();
        properties.setDefaultClient("primary");
        Map<String, MqttClientProperties> clients = new HashMap<>();

        MqttClientProperties primaryProps = new MqttClientProperties();
        primaryProps.setServerUri(broker.getServerUri());
        primaryProps.setClientId("abort-test-client");
        primaryProps.setClientIdStrategy(ClientIdStrategy.STATIC);
        primaryProps.setCleanStart(true);
        primaryProps.setConnectionTimeout(Duration.ofSeconds(3));
        primaryProps.setMaxPayloadSize(DataSize.ofMegabytes(1));
        primaryProps.setProtocolVersion("5.0");
        clients.put("primary", primaryProps);
        properties.setClients(clients);

        NamedMqttClientRegistry clientRegistry = new NamedMqttClientRegistry(properties);
        clientRegistry.initClients();

        PayloadGuard payloadGuard = new PayloadGuard();
        MqttTemplate mqttTemplate = new MqttTemplate(clientRegistry, payloadGuard, null, executor, null, null, null, List.of(guardInterceptor));
        MqttListenerEndpointRegistry listenerRegistry = new MqttListenerEndpointRegistry(clientRegistry, payloadGuard, executor, null, null, List.of(guardInterceptor));

        String topic = "test/guard/abort";
        AbortListenerBean abortBean = new AbortListenerBean();
        MqttListenerAnnotationBeanPostProcessor bpp = new MqttListenerAnnotationBeanPostProcessor(listenerRegistry);
        bpp.postProcessAfterInitialization(abortBean, "abortBean");
        listenerRegistry.start();
        Thread.sleep(150);

        // 1. allowPublish=false：发布被拦截，Broker 接收不到消息
        mqttTemplate.publish(topic, "BLOCKED_PUBLISH");
        Thread.sleep(200);
        assertThat(abortBean.consumeCount.get()).isEqualTo(0);

        // 2. 开启 allowPublish=true，保持 allowConsume=false：发布成功，但消费被拦截跳过
        allowPublish.set(true);
        mqttTemplate.publish(topic, "BLOCKED_CONSUME");
        Thread.sleep(200);
        assertThat(abortBean.consumeCount.get()).isEqualTo(0);

        // 3. 开启 allowConsume=true：发布与消费全部正常走通
        allowConsume.set(true);
        mqttTemplate.publish(topic, "PASSED_MESSAGE");
        boolean reached = abortBean.latch.await(3, TimeUnit.SECONDS);
        assertThat(reached).isTrue();
        assertThat(abortBean.consumeCount.get()).isEqualTo(1);

        listenerRegistry.stop();
        clientRegistry.destroy();
    }

    @Test
    @DisplayName("全量标头 Map 注入：@MqttHeader 未指定键名时自动注入所有 MQTT 5.0 User Properties 字典")
    void shouldInjectCompleteHeaderMapWhenKeyOmitted() throws Exception {
        AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext();
        context.register(TestFullHeaderConfiguration.class);
        context.refresh();

        HeaderCaptureService service = context.getBean(HeaderCaptureService.class);
        MqttTemplate template = context.getBean(MqttTemplate.class);

        // 发布附带多个自定义标头的报文
        Map<String, String> userProps = Map.of(
                "X-Device-Id", "DEV-8899",
                "X-Tenant-Id", "tenant-alpha",
                "X-Timestamp", "1700000000000",
                "x-tag", "production"
        );

        template.publish("device/telemetry/full-headers", "SENSOR_PAYLOAD", 1, false, userProps);

        boolean received = service.latch.await(3, TimeUnit.SECONDS);
        assertThat(received).isTrue();

        // 验证 Map<String, Object> 全量注入
        assertThat(service.receivedObjectHeaders).isNotNull();
        assertThat(service.receivedObjectHeaders).containsEntry("X-Device-Id", "DEV-8899");
        assertThat(service.receivedObjectHeaders).containsEntry("X-Tenant-Id", "tenant-alpha");
        assertThat(service.receivedObjectHeaders).containsEntry("X-Timestamp", "1700000000000");

        // 验证 Map<String, String> 全量注入
        assertThat(service.receivedStringHeaders).isNotNull();
        assertThat(service.receivedStringHeaders).containsEntry("X-Device-Id", "DEV-8899");
        assertThat(service.receivedStringHeaders).containsEntry("x-tag", "production");

        // 验证单独指定的特定单个标头
        assertThat(service.receivedDeviceId).isEqualTo("DEV-8899");

        context.close();
    }

    // ==================== 辅助测试结构与配置 ====================

    public static class PipelineListenerBean {
        final CountDownLatch latch = new CountDownLatch(1);
        final Map<String, String> receivedHeaders = new ConcurrentHashMap<>();

        @MqttListener(topics = "test/interceptor/pipeline", qos = 1)
        public void onMessage(String payload, @MqttHeader Map<String, Object> headers) {
            if (headers != null) {
                headers.forEach((k, v) -> receivedHeaders.put(k, String.valueOf(v)));
            }
            latch.countDown();
        }
    }

    public static class AbortListenerBean {
        final AtomicInteger consumeCount = new AtomicInteger(0);
        final CountDownLatch latch = new CountDownLatch(1);

        @MqttListener(topics = "test/guard/abort", qos = 1)
        public void onMessage(String payload) {
            consumeCount.incrementAndGet();
            latch.countDown();
        }
    }

    @Configuration
    static class TestFullHeaderConfiguration {

        @Bean
        public EmbeddedMqttBroker embeddedMqttBroker() {
            EmbeddedMqttBroker broker = new EmbeddedMqttBroker(0);
            broker.start();
            return broker;
        }

        @Bean
        public NamedMqttClientRegistry namedMqttClientRegistry(EmbeddedMqttBroker broker) {
            AtengMqttProperties properties = new AtengMqttProperties();
            properties.setDefaultClient("primary");
            Map<String, MqttClientProperties> clients = new HashMap<>();

            MqttClientProperties primaryProps = new MqttClientProperties();
            primaryProps.setServerUri(broker.getServerUri());
            primaryProps.setClientId("header-inject-client");
            primaryProps.setClientIdStrategy(ClientIdStrategy.STATIC);
            primaryProps.setCleanStart(true);
            primaryProps.setConnectionTimeout(Duration.ofSeconds(3));
            primaryProps.setMaxPayloadSize(DataSize.ofMegabytes(1));
            primaryProps.setProtocolVersion("5.0");
            clients.put("primary", primaryProps);
            properties.setClients(clients);

            NamedMqttClientRegistry registry = new NamedMqttClientRegistry(properties);
            registry.initClients();
            return registry;
        }

        @Bean
        public PayloadGuard payloadGuard() {
            return new PayloadGuard();
        }

        @Bean
        public CompositeMqttMessageConverter compositeMqttMessageConverter() {
            return new CompositeMqttMessageConverter();
        }

        @Bean
        public ExecutorService testExecutor() {
            return Executors.newCachedThreadPool();
        }

        @Bean
        public MqttTemplate mqttTemplate(NamedMqttClientRegistry registry, PayloadGuard guard, ExecutorService executor) {
            return new MqttTemplate(registry, guard, null, executor, null, null, null);
        }

        @Bean
        public MqttListenerEndpointRegistry mqttListenerEndpointRegistry(NamedMqttClientRegistry registry, PayloadGuard guard, ExecutorService executor) {
            return new MqttListenerEndpointRegistry(registry, guard, executor, null, null);
        }

        @Bean
        public MqttListenerAnnotationBeanPostProcessor bpp() {
            return new MqttListenerAnnotationBeanPostProcessor();
        }

        @Bean
        public HeaderCaptureService headerCaptureService() {
            return new HeaderCaptureService();
        }
    }

    @Component
    static class HeaderCaptureService {
        final CountDownLatch latch = new CountDownLatch(1);
        volatile Map<String, Object> receivedObjectHeaders;
        volatile Map<String, String> receivedStringHeaders;
        volatile String receivedDeviceId;

        @MqttListener(topics = "device/telemetry/full-headers", qos = 1)
        public void handleMessage(@MqttTopic String topic,
                                  String payload,
                                  @MqttHeader Map<String, Object> allObjectHeaders,
                                  @MqttHeader Map<String, String> allStringHeaders,
                                  @MqttHeader("X-Device-Id") String deviceId) {
            this.receivedObjectHeaders = allObjectHeaders;
            this.receivedStringHeaders = allStringHeaders;
            this.receivedDeviceId = deviceId;
            this.latch.countDown();
        }
    }
}
