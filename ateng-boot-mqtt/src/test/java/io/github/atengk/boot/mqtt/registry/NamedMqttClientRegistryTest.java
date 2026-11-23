package io.github.atengk.boot.mqtt.registry;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.mqtt.context.MqttContextHolder;
import io.github.atengk.boot.mqtt.credentials.MqttCredentials;
import io.github.atengk.boot.mqtt.credentials.MqttCredentialsProvider;
import io.github.atengk.boot.mqtt.event.MqttConnectedEvent;
import io.github.atengk.boot.mqtt.event.MqttDisconnectedEvent;
import io.github.atengk.boot.mqtt.metrics.MqttMetrics;
import io.github.atengk.boot.mqtt.properties.AtengMqttProperties;
import io.github.atengk.boot.mqtt.properties.ClientIdStrategy;
import io.github.atengk.boot.mqtt.properties.MqttClientProperties;
import io.github.atengk.boot.mqtt.properties.MqttLwtProperties;
import io.github.atengk.boot.mqtt.properties.MqttSslProperties;
import io.github.atengk.boot.mqtt.test.EmbeddedMqttBroker;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * NamedMqttClientRegistry 单元测试
 *
 * @author Ateng
 * @since 2026-10-23
 */
class NamedMqttClientRegistryTest {

    private EmbeddedMqttBroker broker;
    private NamedMqttClientRegistry registry;

    @BeforeEach
    void setUp() {
        broker = new EmbeddedMqttBroker(0);
        broker.start();
    }

    @AfterEach
    void tearDown() {
        if (registry != null) {
            registry.destroy();
        }
        if (broker != null) {
            broker.stop();
        }
        MqttContextHolder.clear();
    }

    @Test
    @DisplayName("测试多命名客户端注册、自动连接、防互踢后缀生成与上下文切源")
    void testNamedClientsManagement() {
        AtengMqttProperties properties = new AtengMqttProperties();
        properties.setDefaultClient("primary");

        // 1. 配置主客户端（RANDOM_SUFFIX）
        MqttClientProperties primaryProps = new MqttClientProperties();
        primaryProps.setServerUri(broker.getServerUri());
        primaryProps.setClientId("gateway-primary");
        primaryProps.setClientIdStrategy(ClientIdStrategy.RANDOM_SUFFIX);
        properties.getClients().put("primary", primaryProps);

        // 2. 配置次客户端（STATIC）
        MqttClientProperties secondaryProps = new MqttClientProperties();
        secondaryProps.setServerUri(broker.getServerUri());
        secondaryProps.setClientId("gateway-secondary");
        secondaryProps.setClientIdStrategy(ClientIdStrategy.STATIC);
        properties.getClients().put("secondary", secondaryProps);

        registry = new NamedMqttClientRegistry(properties);
        registry.afterPropertiesSet();

        // 3. 验证默认主客户端
        MqttClientHandle defaultHandle = registry.getDefaultClientHandle();
        assertNotNull(defaultHandle);
        assertEquals("primary", defaultHandle.getClientName());
        assertTrue(defaultHandle.getActualClientId().startsWith("gateway-primary_"), "主客户端应包含随机防互踢后缀");
        assertTrue(defaultHandle.isConnected(), "主客户端应保持已连接状态");

        // 4. 验证次客户端
        MqttClientHandle secondaryHandle = registry.getClientHandle("secondary");
        assertNotNull(secondaryHandle);
        assertEquals("secondary", secondaryHandle.getClientName());
        assertEquals("gateway-secondary", secondaryHandle.getActualClientId(), "静态模式应保持原始 clientId 不变");
        assertTrue(secondaryHandle.isConnected(), "次客户端应保持已连接状态");

        // 5. 验证客户端存在性检查
        assertTrue(registry.containsClient("primary"));
        assertTrue(registry.containsClient("secondary"));
        assertFalse(registry.containsClient("unknown"));
        assertThrows(BusinessException.class, () -> registry.getClientHandle("unknown"));

        // 6. 验证上下文动态切源 MqttContextHolder
        MqttContextHolder.runWith("secondary", () -> {
            assertEquals("secondary", MqttContextHolder.getClientName());
            MqttClientHandle currentHandle = registry.getClientHandle(MqttContextHolder.getClientName());
            assertEquals("gateway-secondary", currentHandle.getActualClientId());
        });
        assertNull(MqttContextHolder.getClientName(), "runWith 执行完毕后上下文必须严格还原清理");

        // 7. 验证客户端只读映射
        Map<String, MqttClientHandle> handles = registry.getClientHandles();
        assertEquals(2, handles.size());
    }

    @Test
    @DisplayName("测试禁用开关：enabled=false 时不初始化任何连接")
    void testDisabledMqtt() {
        AtengMqttProperties properties = new AtengMqttProperties();
        properties.setEnabled(false);

        MqttClientProperties clientProps = new MqttClientProperties();
        clientProps.setServerUri(broker.getServerUri());
        properties.getClients().put("primary", clientProps);

        registry = new NamedMqttClientRegistry(properties);
        registry.afterPropertiesSet();

        assertTrue(registry.getClientHandles().isEmpty(), "禁用状态下不应建立任何客户端连接");
    }

    @Test
    @DisplayName("测试 MqttClientHandle 单主题串行订阅、批量订阅防卫与退订")
    void testClientHandleSubscriptionMethods() throws Exception {
        AtengMqttProperties properties = new AtengMqttProperties();
        properties.setDefaultClient("primary");

        MqttClientProperties primaryProps = new MqttClientProperties();
        primaryProps.setServerUri(broker.getServerUri());
        primaryProps.setClientId("gateway-sub-test");
        primaryProps.setClientIdStrategy(ClientIdStrategy.STATIC);
        properties.getClients().put("primary", primaryProps);

        registry = new NamedMqttClientRegistry(properties);
        registry.afterPropertiesSet();

        MqttClientHandle handle = registry.getDefaultClientHandle();
        assertNotNull(handle);
        assertTrue(handle.isMqttV5());

        // 单主题订阅
        assertDoesNotThrow(() -> handle.subscribe("test/topic/single", 1));

        // 批量订阅（串行迭代解构）
        assertDoesNotThrow(() -> handle.subscribeBatch(
                new String[]{"test/topic/batch1", "test/topic/batch2"},
                new int[]{1, 1}
        ));

        // 退订
        assertDoesNotThrow(() -> handle.unsubscribe("test/topic/single", "test/topic/batch1", "test/topic/batch2"));
    }

    @Test
    @DisplayName("测试协议版本校验防卫：非法版本快速失败与 3.1.1 兼容模式")
    void testProtocolVersionValidation() {
        AtengMqttProperties properties = new AtengMqttProperties();
        properties.setDefaultClient("legacy");

        MqttClientProperties legacyProps = new MqttClientProperties();
        legacyProps.setServerUri(broker.getServerUri());
        legacyProps.setProtocolVersion("3.1.1");
        properties.getClients().put("legacy", legacyProps);

        registry = new NamedMqttClientRegistry(properties);
        registry.afterPropertiesSet();

        MqttClientHandle legacyHandle = registry.getDefaultClientHandle();
        assertNotNull(legacyHandle);
        assertFalse(legacyHandle.isMqttV5(), "3.1.1 协议模式下 isMqttV5 应为 false");

        // 测试不支持的非法版本
        AtengMqttProperties invalidProperties = new AtengMqttProperties();
        MqttClientProperties invalidProps = new MqttClientProperties();
        invalidProps.setServerUri(broker.getServerUri());
        invalidProps.setProtocolVersion("2.0");
        invalidProperties.getClients().put("invalid", invalidProps);

        NamedMqttClientRegistry invalidRegistry = new NamedMqttClientRegistry(invalidProperties);
        BusinessException ex = assertThrows(BusinessException.class, invalidRegistry::afterPropertiesSet);
        assertTrue(ex.getMessage().contains("Unsupported MQTT protocol version"));
    }

    @Test
    @DisplayName("动态客户端热插拔：registerClient / registerOrReplaceClient / unregisterClient 守卫与度量解绑闭环")
    void testDynamicClientRegisterReplaceAndUnregister() {
        AtengMqttProperties properties = new AtengMqttProperties();
        properties.setDefaultClient("primary");
        MqttClientProperties primaryProps = new MqttClientProperties();
        primaryProps.setServerUri(broker.getServerUri());
        primaryProps.setClientId("dynamic-primary");
        primaryProps.setClientIdStrategy(ClientIdStrategy.STATIC);
        properties.getClients().put("primary", primaryProps);

        List<ApplicationEvent> publishedEvents = new CopyOnWriteArrayList<>();
        ApplicationEventPublisher publisher = new ApplicationEventPublisher() {
            @Override
            public void publishEvent(ApplicationEvent event) {
                publishedEvents.add(event);
            }

            @Override
            public void publishEvent(Object event) {
                if (event instanceof ApplicationEvent appEvent) {
                    publishedEvents.add(appEvent);
                }
            }
        };

        registry = new NamedMqttClientRegistry(properties);
        registry.setApplicationEventPublisher(publisher);
        registry.afterPropertiesSet();

        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        MqttMetrics metrics = new MqttMetrics(meterRegistry);
        metrics.bindClientRegistry(registry);

        assertNotNull(meterRegistry.find("mqtt.client.status").tag("client", "primary").gauge());

        // 1. 动态注册新客户端
        MqttClientProperties edgeProps = new MqttClientProperties();
        edgeProps.setServerUri(broker.getServerUri());
        edgeProps.setClientId("dynamic-edge-v1");
        edgeProps.setClientIdStrategy(ClientIdStrategy.STATIC);
        MqttClientHandle edgeHandle = registry.registerClient("edge", edgeProps);
        assertTrue(edgeHandle.isConnected());
        assertEquals("dynamic-edge-v1", edgeHandle.getActualClientId());
        assertTrue(registry.containsClient("edge"));
        assertNotNull(meterRegistry.find("mqtt.client.status").tag("client", "edge").gauge());
        assertEquals(1.0, meterRegistry.find("mqtt.client.status").tag("client", "edge").gauge().value());

        // 2. 重名注册快速失败
        assertThrows(BusinessException.class, () -> registry.registerClient("edge", edgeProps));

        // 3. 热替换客户端
        MqttClientProperties edgeV2Props = new MqttClientProperties();
        edgeV2Props.setServerUri(broker.getServerUri());
        edgeV2Props.setClientId("dynamic-edge-v2");
        edgeV2Props.setClientIdStrategy(ClientIdStrategy.STATIC);
        MqttClientHandle edgeV2Handle = registry.registerOrReplaceClient("edge", edgeV2Props);
        assertTrue(edgeV2Handle.isConnected());
        assertFalse(edgeHandle.isConnected(), "被替换的旧客户端连接应已优雅断开");
        assertEquals("dynamic-edge-v2", registry.getClientHandle("edge").getActualClientId());
        assertEquals(1.0, meterRegistry.find("mqtt.client.status").tag("client", "edge").gauge().value());

        // 4. 默认客户端受保护不可直接注销；未知客户端注销快速失败
        assertThrows(BusinessException.class, () -> registry.unregisterClient("primary"));
        assertThrows(BusinessException.class, () -> registry.unregisterClient("non-existent"));

        // 5. 对称注销动态客户端并解绑 Gauge 与发布断连事件
        registry.unregisterClient("edge");
        assertFalse(registry.containsClient("edge"));
        assertFalse(edgeV2Handle.isConnected());
        assertNull(meterRegistry.find("mqtt.client.status").tag("client", "edge").gauge(), "注销后应同步清理 Gauge");
        assertTrue(publishedEvents.stream().anyMatch(e -> e instanceof MqttDisconnectedEvent de && "edge".equals(de.getClientName())));
        assertTrue(publishedEvents.stream().anyMatch(e -> e instanceof MqttConnectedEvent ce && "edge".equals(ce.getClientName())));
    }

    @Test
    @DisplayName("测试 MqttCredentialsProvider SPI：初次建连与断线重连动态刷新凭据及增强认证属性")
    void testCredentialsProviderInitialAndReconnectRefresh() throws Exception {
        broker.start();

        AtengMqttProperties properties = new AtengMqttProperties();
        MqttClientProperties clientProps = new MqttClientProperties();
        clientProps.setServerUri(broker.getServerUri());
        clientProps.setClientId("cred-spi-client");
        clientProps.setClientIdStrategy(ClientIdStrategy.STATIC);
        clientProps.setAutomaticReconnect(true);
        clientProps.setMaxReconnectDelay(Duration.ofSeconds(1));
        properties.setClients(Map.of("primary", clientProps));

        AtomicInteger invokeCount = new AtomicInteger(0);
        MqttCredentialsProvider provider = (clientName, props, reconnect) -> {
            int seq = invokeCount.incrementAndGet();
            if (!reconnect) {
                return MqttCredentials.of("user-v1", "token-v1");
            }
            return MqttCredentials.builder()
                    .username("user-v2")
                    .password("token-v2")
                    .authMethod("SCRAM-SHA-256")
                    .authData(("nonce-" + seq).getBytes(StandardCharsets.UTF_8))
                    .build();
        };

        registry = new NamedMqttClientRegistry(properties, null, provider);
        registry.afterPropertiesSet();

        MqttClientHandle handle = registry.getClientHandle("primary");
        assertTrue(handle.isConnected());
        assertEquals(1, broker.getConnectRecords().size());
        assertEquals("user-v1", broker.getConnectRecords().get(0).username());
        assertEquals("token-v1", broker.getConnectRecords().get(0).password());

        // 模拟 Broker 侧网络突发中断，验证重连时自动刷新凭据并携带新 CONNECT 凭据重连
        broker.disconnectAllClients();
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(5);
        while (System.currentTimeMillis() < deadline
                && (broker.getConnectRecords().size() < 2 || !handle.isConnected())) {
            Thread.sleep(50);
        }

        assertTrue(handle.isConnected(), "断线后应完成自动重连");
        assertTrue(broker.getConnectRecords().size() >= 2, "应观察到至少两次 CONNECT 握手报文");
        EmbeddedMqttBroker.ConnectRecord reconnectRecord = broker.getConnectRecords().get(broker.getConnectRecords().size() - 1);
        assertEquals("user-v2", reconnectRecord.username());
        assertEquals("token-v2", reconnectRecord.password());
        assertEquals("SCRAM-SHA-256", handle.getConnectionOptions().getAuthMethod());
        assertNotNull(handle.getConnectionOptions().getAuthData());
    }

    @Test
    @DisplayName("测试 serverUris 高可用故障转移、webSocketHeaders、willDelayInterval 与 TLS 主机名校验开关")
    void testServerUrisFailoverWebSocketHeadersWillDelayAndHostnameVerification() throws Exception {
        broker.start();

        int deadPort;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            deadPort = socket.getLocalPort();
        }

        AtengMqttProperties properties = new AtengMqttProperties();
        MqttClientProperties clientProps = new MqttClientProperties();
        clientProps.setServerUri("tcp://127.0.0.1:" + deadPort);
        clientProps.setServerUris(List.of("tcp://127.0.0.1:" + deadPort, broker.getServerUri()));
        clientProps.setWebSocketHeaders(Map.of("Authorization", "Bearer edge-jwt", "X-Tenant", "t-ha"));
        clientProps.setConnectionTimeout(Duration.ofSeconds(2));

        MqttLwtProperties lwt = new MqttLwtProperties();
        lwt.setEnabled(true);
        lwt.setTopic("devices/status/ha");
        lwt.setPayload("offline");
        lwt.setWillDelayInterval(Duration.ofSeconds(15));
        clientProps.setLwt(lwt);

        MqttSslProperties ssl = new MqttSslProperties();
        assertTrue(ssl.isHostnameVerificationEnabled(), "生产默认应开启 TLS 主机名校验");
        ssl.setHostnameVerificationEnabled(false);
        clientProps.setSsl(ssl);

        properties.setClients(Map.of("primary", clientProps));

        registry = new NamedMqttClientRegistry(properties);
        registry.afterPropertiesSet();

        MqttClientHandle handle = registry.getClientHandle("primary");
        assertTrue(handle.isConnected(), "首位节点不可达时应自动故障转移至 serverUris 次位节点建连成功");
        assertArrayEquals(
                new String[]{"tcp://127.0.0.1:" + deadPort, broker.getServerUri()},
                handle.getConnectionOptions().getServerURIs()
        );
        assertEquals("Bearer edge-jwt", handle.getConnectionOptions().getCustomWebSocketHeaders().get("Authorization"));
        assertEquals("t-ha", handle.getConnectionOptions().getCustomWebSocketHeaders().get("X-Tenant"));
        assertNotNull(handle.getConnectionOptions().getWillMessageProperties());
        assertEquals(15L, handle.getConnectionOptions().getWillMessageProperties().getWillDelayInterval());
        assertFalse(handle.getConnectionOptions().isHttpsHostnameVerificationEnabled());
    }
}
