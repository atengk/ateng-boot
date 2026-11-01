package io.github.atengk.boot.encrypt.key;

import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;
import io.github.atengk.boot.encrypt.config.EncryptProperties;
import io.github.atengk.boot.encrypt.decryptor.ConfigEncryptor;
import io.github.atengk.boot.encrypt.event.KeyRotatedEvent;
import io.github.atengk.boot.encrypt.parser.CiphertextParser;
import io.github.atengk.boot.encrypt.wrapper.EncryptPropertySourceWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 多密钥命名注册表与动态轮换单元测试
 *
 * @author Ateng
 * @since 2026-10-25
 */
class MultiKeyRegistryTest {

    @Test
    @DisplayName("测试基础增删改查与别名解析")
    void testBasicRegistryOperations() {
        MultiKeyRegistry registry = new MultiKeyRegistry("default-secret-123");
        assertEquals("default-secret-123", registry.getDefaultKey());
        assertEquals("default-secret-123", registry.getKey("default"));
        assertEquals("default-secret-123", registry.getKey(null));

        registry.registerKey("db-key", "db-secret-456");
        registry.registerKey("redis-key", "redis-secret-789");

        assertTrue(registry.hasKey("db-key"));
        assertTrue(registry.hasKey("redis-key"));
        assertFalse(registry.hasKey("unknown-key"));

        assertEquals("db-secret-456", registry.getKey("db-key"));
        assertEquals("default-secret-123", registry.getKeyOrDefault("unknown-key"));

        // 注销
        String removed = registry.unregisterKey("redis-key");
        assertEquals("redis-secret-789", removed);
        assertFalse(registry.hasKey("redis-key"));

        // 轮换默认密钥
        registry.rotateDefaultKey("new-default-999");
        assertEquals("new-default-999", registry.getDefaultKey());
        assertEquals("new-default-999", registry.getKeyOrDefault("unknown-key"));
    }

    @Test
    @DisplayName("测试从 EncryptProperties 加载多密钥")
    void testFromProperties() {
        EncryptProperties properties = new EncryptProperties();
        properties.setPassword("global-pwd");
        properties.getKeys().put("key-2025", "secret-2025");
        properties.getKeys().put("key-2026", "secret-2026");

        MultiKeyRegistry registry = MultiKeyRegistry.fromProperties(properties);
        assertEquals("global-pwd", registry.getDefaultKey());
        assertEquals("secret-2025", registry.getKey("key-2025"));
        assertEquals("secret-2026", registry.getKey("key-2026"));
        assertEquals(2, registry.size());
    }

    @Test
    @DisplayName("测试从 Spring Environment 加载多密钥")
    void testFromEnvironment() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("ateng.boot.encrypt.password", "env-global-pwd");
        env.setProperty("ateng.boot.encrypt.keys.cluster-a", "cluster-a-secret");
        env.setProperty("ateng.boot.encrypt.keys.cluster-b", "cluster-b-secret");

        MultiKeyRegistry registry = MultiKeyRegistry.fromEnvironment(env);
        assertEquals("env-global-pwd", registry.getDefaultKey());
        assertEquals("cluster-a-secret", registry.getKey("cluster-a"));
        assertEquals("cluster-b-secret", registry.getKey("cluster-b"));
    }

    @Test
    @DisplayName("测试多 Key 路由与新旧密钥平滑解密共存")
    void testMultiKeyRoutingWithWrapper() {
        String key2025 = "LegacySecretKey2025!";
        String key2026 = "ModernSecretKey2026!";
        String defaultKey = "GlobalDefaultKey2026!";

        // 分别使用不同的密钥对不同业务配置加密
        String cipher2025 = ConfigEncryptor.encrypt("old_db_password", key2025, EncryptAlgorithm.AES_GCM);
        String cipher2026 = ConfigEncryptor.encrypt("new_db_password", key2026, EncryptAlgorithm.AES_GCM);
        String cipherDefault = ConfigEncryptor.encrypt("cache_password", defaultKey, EncryptAlgorithm.AES_GCM);

        MultiKeyRegistry registry = new MultiKeyRegistry(defaultKey);
        registry.registerKey("key-2025", key2025);
        registry.registerKey("key-2026", key2026);

        Map<String, Object> map = new HashMap<>();
        map.put("spring.datasource.legacy.password", "ENC[key-2025](" + cipher2025 + ")");
        map.put("spring.datasource.primary.password", "ENC[key-2026](" + cipher2026 + ")");
        map.put("spring.redis.password", "ENC(" + cipherDefault + ")");

        MapPropertySource source = new MapPropertySource("testSource", map);
        EncryptPropertySourceWrapper wrapper = new EncryptPropertySourceWrapper(source, registry);

        // 验证各自路由到对应 keyId 成功解密
        assertEquals("old_db_password", wrapper.getProperty("spring.datasource.legacy.password"));
        assertEquals("new_db_password", wrapper.getProperty("spring.datasource.primary.password"));
        assertEquals("cache_password", wrapper.getProperty("spring.redis.password"));
    }

    @Test
    @DisplayName("测试监听器总线在 register/unregister/rotateDefaultKey 时准确触发")
    void testChangeListenerBus() {
        MultiKeyRegistry registry = new MultiKeyRegistry("initial-default");
        List<String> notifications = new ArrayList<>();

        java.util.function.Consumer<String> listener = notifications::add;
        registry.addChangeListener(listener);
        assertEquals(1, registry.getChangeListeners().size());

        registry.registerKey("app-key", "app-secret");
        registry.rotateDefaultKey("rotated-default");
        registry.unregisterKey("app-key");
        registry.clear();

        assertEquals(List.of("app-key", "default", "app-key", "*"), notifications);

        // 移除监听器后不再接收通知
        registry.removeChangeListener(listener);
        registry.registerKey("another-key", "pwd");
        assertEquals(4, notifications.size(), "移除监听器后不应再触发新通知");
    }

    @Test
    @DisplayName("测试领域事件 KeyRotatedEvent 成功广播与接收")
    void testKeyRotatedEventBroadcast() {
        MultiKeyRegistry registry = new MultiKeyRegistry("init-key");
        List<KeyRotatedEvent> receivedEvents = new ArrayList<>();

        ApplicationEventPublisher publisher = (event) -> {
            if (event instanceof KeyRotatedEvent kre) {
                receivedEvents.add(kre);
            }
        };

        registry.setApplicationEventPublisher(publisher);

        registry.registerKey("dynamic-key", "secret-value");
        registry.rotateDefaultKey("new-default-key");

        assertEquals(2, receivedEvents.size());
        assertEquals("dynamic-key", receivedEvents.get(0).getKeyId());
        assertSame(registry, receivedEvents.get(0).getSource());
        assertEquals("default", receivedEvents.get(1).getKeyId());
    }

    @Test
    @DisplayName("测试动态轮换密钥后属性源包装器自动清空缓存并立即使用新密钥解密")
    void testDynamicKeyRotationEvictsCacheAutomatically() {
        String oldKey = "MyOldMasterKey2025!";
        String newKey = "MyNewMasterKey2026!";

        String cipherOld = ConfigEncryptor.encrypt("db_secret_v1", oldKey, EncryptAlgorithm.AES_GCM);
        String cipherNew = ConfigEncryptor.encrypt("db_secret_v2", newKey, EncryptAlgorithm.AES_GCM);

        MultiKeyRegistry registry = new MultiKeyRegistry(oldKey);
        Map<String, Object> map = new HashMap<>();
        map.put("db.password", "ENC(" + cipherOld + ")");

        MapPropertySource source = new MapPropertySource("dynamicSource", map);
        EncryptPropertySourceWrapper wrapper = new EncryptPropertySourceWrapper(source, registry);

        // 首次读取，触发解密并存入内存缓存
        assertEquals("db_secret_v1", wrapper.getProperty("db.password"));

        // 更新配置中心属性为新密文，并执行密钥平滑轮换
        map.put("db.password", "ENC(" + cipherNew + ")");
        registry.rotateDefaultKey(newKey);

        // 监听器已自动触发 clearCache()，无需重启即可读取到新密钥解密的明文
        assertEquals("db_secret_v2", wrapper.getProperty("db.password"));
    }

    @Test
    @DisplayName("测试多线程并发轮换密钥时的线程安全性")
    void testConcurrentKeyRotationsThreadSafety() throws InterruptedException {
        MultiKeyRegistry registry = new MultiKeyRegistry("thread-default");
        AtomicInteger listenerTriggers = new AtomicInteger(0);
        registry.addChangeListener(keyId -> listenerTriggers.incrementAndGet());

        int threadCount = 8;
        int operationsPerThread = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int threadIdx = i;
            executor.submit(() -> {
                try {
                    for (int j = 0; j < operationsPerThread; j++) {
                        String keyId = "thread-key-" + threadIdx + "-" + j;
                        registry.registerKey(keyId, "pwd-" + j);
                        registry.getKey(keyId);
                        registry.rotateDefaultKey("default-rot-" + j);
                        registry.unregisterKey(keyId);
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        boolean finished = latch.await(10, TimeUnit.SECONDS);
        executor.shutdown();
        assertTrue(finished, "并发操作应在超时前完成");
        assertTrue(listenerTriggers.get() > 0, "监听器应接收到大量并发通知");
    }
}
