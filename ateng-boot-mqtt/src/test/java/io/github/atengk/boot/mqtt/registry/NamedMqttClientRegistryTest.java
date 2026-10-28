package io.github.atengk.boot.mqtt.registry;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.mqtt.context.MqttContextHolder;
import io.github.atengk.boot.mqtt.properties.AtengMqttProperties;
import io.github.atengk.boot.mqtt.properties.ClientIdStrategy;
import io.github.atengk.boot.mqtt.properties.MqttClientProperties;
import io.github.atengk.boot.mqtt.test.EmbeddedMqttBroker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

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
}
