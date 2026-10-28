package io.github.atengk.boot.encrypt.key;

import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;
import io.github.atengk.boot.encrypt.config.EncryptProperties;
import io.github.atengk.boot.encrypt.decryptor.ConfigEncryptor;
import io.github.atengk.boot.encrypt.parser.CiphertextParser;
import io.github.atengk.boot.encrypt.wrapper.EncryptPropertySourceWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.mock.env.MockEnvironment;

import java.util.HashMap;
import java.util.Map;

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
}
