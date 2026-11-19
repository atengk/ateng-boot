package io.github.atengk.boot.encrypt.guard;

import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;
import io.github.atengk.boot.encrypt.config.EncryptProperties;
import io.github.atengk.boot.encrypt.decryptor.AesGcmConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.ConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.ConfigEncryptor;
import io.github.atengk.boot.encrypt.detector.KeyDetector;
import io.github.atengk.boot.encrypt.exception.ConfigSecurityException;
import io.github.atengk.boot.encrypt.key.MultiKeyRegistry;
import io.github.atengk.boot.encrypt.parser.CiphertextParser;
import io.github.atengk.boot.encrypt.wrapper.EncryptPropertySourceWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 配置中心动态热更新防明文倒灌与缓存置换单元测试
 *
 * @author Ateng
 * @since 2026-10-25
 */
@DisplayName("配置中心动态热更新防明文倒灌与缓存置换单元测试")
class PlaintextIngressGuardTest {

    private final String masterPassword = "IngressMasterPassword@2026";
    private final CiphertextParser parser = new CiphertextParser();

    @Test
    @DisplayName("onEnvironmentChange: 热更新时原子置换清空解密缓存，确保最新密文被正确解密")
    void testCacheAtomicInvalidationOnRefresh() {
        EncryptProperties properties = new EncryptProperties();
        properties.setPassword(masterPassword);
        PlaintextIngressGuard guard = new PlaintextIngressGuard(properties, parser);

        String cipher1 = ConfigEncryptor.encrypt("initial_password_111", masterPassword, EncryptAlgorithm.AES_GCM);
        String cipher2 = ConfigEncryptor.encrypt("refreshed_password_222", masterPassword, EncryptAlgorithm.AES_GCM);

        Map<String, Object> map = new HashMap<>();
        map.put("custom.db.password", "ENC(" + cipher1 + ")");
        MapPropertySource rawSource = new MapPropertySource("nacosSource", map);

        StandardEnvironment env = new StandardEnvironment();
        EncryptPropertySourceWrapper wrapper = new EncryptPropertySourceWrapper(
                rawSource, parser, new KeyDetector(masterPassword, Map.of()), null, EncryptAlgorithm.AES_GCM, true
        );
        env.getPropertySources().addFirst(wrapper);

        // 1. 首次读取并缓存
        assertThat(env.getProperty("custom.db.password")).isEqualTo("initial_password_111");

        // 2. 模拟配置中心热更新底层属性源密文
        map.put("custom.db.password", "ENC(" + cipher2 + ")");

        // 若未触发清空，缓存仍返回旧值
        assertThat(env.getProperty("custom.db.password")).isEqualTo("initial_password_111");

        // 3. 触发动态热更新防倒灌守卫
        guard.onEnvironmentChange(Set.of("custom.db.password"), env);

        // 4. 断言缓存已清空，获取到最新解密明文
        assertThat(env.getProperty("custom.db.password")).isEqualTo("refreshed_password_222");
    }

    @Test
    @DisplayName("onEnvironmentChange: enforce-encrypted=true 时若配置中心推送裸明文则刚性阻断")
    void testPlaintextIngressBlockedWhenEnforceEncrypted() {
        EncryptProperties properties = new EncryptProperties();
        properties.setPassword(masterPassword);
        properties.setEnforceEncrypted(true);
        PlaintextIngressGuard guard = new PlaintextIngressGuard(properties, parser);

        Map<String, Object> map = new HashMap<>();
        // 运维误在配置中心推入裸明文密码
        map.put("spring.datasource.password", "plaintext_unsafe_password");
        MapPropertySource rawSource = new MapPropertySource("nacosSource", map);

        StandardEnvironment env = new StandardEnvironment();
        EncryptPropertySourceWrapper wrapper = new EncryptPropertySourceWrapper(
                rawSource, parser, new KeyDetector(masterPassword, Map.of()), null, EncryptAlgorithm.AES_GCM, true
        );
        env.getPropertySources().addFirst(wrapper);

        assertThatThrownBy(() -> guard.onEnvironmentChange(Set.of("spring.datasource.password"), env))
                .isInstanceOf(ConfigSecurityException.class)
                .hasMessageContaining("spring.datasource.password");
    }

    @Test
    @DisplayName("onEnvironmentChange: enforce-encrypted=true 时若配置中心推送加密密文则顺利放行")
    void testEncryptedIngressPassesWhenEnforceEncrypted() {
        EncryptProperties properties = new EncryptProperties();
        properties.setPassword(masterPassword);
        properties.setEnforceEncrypted(true);
        PlaintextIngressGuard guard = new PlaintextIngressGuard(properties, parser);

        String cipher = ConfigEncryptor.encrypt("safe_password", masterPassword, EncryptAlgorithm.AES_GCM);
        Map<String, Object> map = new HashMap<>();
        map.put("spring.datasource.password", "ENC(" + cipher + ")");
        MapPropertySource rawSource = new MapPropertySource("nacosSource", map);

        StandardEnvironment env = new StandardEnvironment();
        EncryptPropertySourceWrapper wrapper = new EncryptPropertySourceWrapper(
                rawSource, parser, new KeyDetector(masterPassword, Map.of()), null, EncryptAlgorithm.AES_GCM, true
        );
        env.getPropertySources().addFirst(wrapper);

        assertThatCode(() -> guard.onEnvironmentChange(Set.of("spring.datasource.password"), env))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("onEnvironmentChange: 动态刷新推送全新属性源时，自动装配解密代理并成功解密")
    void testNewPropertySourceWrappedOnRefresh() {
        EncryptProperties properties = new EncryptProperties();
        properties.setPassword(masterPassword);
        PlaintextIngressGuard guard = new PlaintextIngressGuard(properties, parser);

        StandardEnvironment env = new StandardEnvironment();
        System.setProperty(KeyDetector.PROPERTY_PASSWORD, masterPassword);
        try {
            // 动态推送一个全新的原始 MapPropertySource（未经过包装）
            String cipher = ConfigEncryptor.encrypt("new_redis_password_999", masterPassword, EncryptAlgorithm.AES_GCM);
            Map<String, Object> map = new HashMap<>();
            map.put("spring.data.redis.password", "ENC(" + cipher + ")");
            MapPropertySource remoteSource = new MapPropertySource("nacosRemoteSource", map);
            env.getPropertySources().addFirst(remoteSource);

            // 触发动态刷新响应
            guard.onEnvironmentChange(Set.of("spring.data.redis.password"), env);

            // 断言属性源已被装饰为 EncryptPropertySourceWrapper 且成功解密
            assertThat(env.getPropertySources().get("nacosRemoteSource")).isInstanceOf(EncryptPropertySourceWrapper.class);
            assertThat(env.getProperty("spring.data.redis.password")).isEqualTo("new_redis_password_999");
        } finally {
            System.clearProperty(KeyDetector.PROPERTY_PASSWORD);
        }
    }

    @Test
    @DisplayName("onEnvironmentChange: failOnError=false 时热更新包裹的新属性源解密失败返回原始值")
    void testNewPropertySourceHonorsFailOnErrorFalse() {
        EncryptProperties properties = new EncryptProperties();
        properties.setPassword(masterPassword);
        properties.setFailOnError(false);
        PlaintextIngressGuard guard = new PlaintextIngressGuard(properties, parser);

        StandardEnvironment env = new StandardEnvironment();
        System.setProperty(KeyDetector.PROPERTY_PASSWORD, masterPassword);
        try {
            Map<String, Object> map = new HashMap<>();
            map.put("spring.data.redis.password", "ENC(InvalidHotPushedCipher)");
            MapPropertySource remoteSource = new MapPropertySource("nacosBrokenSource", map);
            env.getPropertySources().addFirst(remoteSource);

            guard.onEnvironmentChange(Set.of("spring.data.redis.password"), env);

            assertThat(env.getProperty("spring.data.redis.password")).isEqualTo("ENC(InvalidHotPushedCipher)");
        } finally {
            System.clearProperty(KeyDetector.PROPERTY_PASSWORD);
        }
    }

    @Test
    @DisplayName("onEnvironmentChange: 热更新将 fail-on-error=false 同步至存量已包装属性源")
    void testExistingWrapperSyncsFailOnErrorFalseOnRefresh() {
        EncryptProperties properties = new EncryptProperties();
        properties.setPassword(masterPassword);
        properties.setFailOnError(true);
        PlaintextIngressGuard guard = new PlaintextIngressGuard(properties, parser);

        Map<String, Object> map = new HashMap<>();
        map.put("ateng.boot.encrypt.fail-on-error", "false");
        map.put("custom.broken.password", "ENC(CorruptedExistingCipher)");
        MapPropertySource rawSource = new MapPropertySource("nacosExistingSource", map);

        StandardEnvironment env = new StandardEnvironment();
        EncryptPropertySourceWrapper wrapper = new EncryptPropertySourceWrapper(
                rawSource, parser, new KeyDetector(masterPassword, Map.of()), null, EncryptAlgorithm.AES_GCM, true);
        env.getPropertySources().addFirst(wrapper);

        guard.onEnvironmentChange(Set.of("ateng.boot.encrypt.fail-on-error"), env);

        assertThat(wrapper.isFailOnError()).isFalse();
        assertThat(env.getProperty("custom.broken.password")).isEqualTo("ENC(CorruptedExistingCipher)");
    }

    @Test
    @DisplayName("onEnvironmentChange: 复用受管 MultiKeyRegistry 单例保留运行期轮换密钥、增量合并新密钥并支持自定义 KMS 解密器")
    void testManagedMultiKeyRegistryPreservesRotatedKeysAndInjectsKmsDecryptor() {
        String initialDbSecret = "InitialDbSecret@2026";
        String rotatedDbSecret = "RotatedDbSecret@2026";
        String runtimeKeySecret = "RuntimeOnlySecret@2026";
        String nacosNewKeySecret = "NacosNewKeySecret@2026";

        EncryptProperties properties = new EncryptProperties();
        properties.setPassword(masterPassword);
        MultiKeyRegistry managedRegistry = MultiKeyRegistry.fromProperties(properties);
        managedRegistry.registerKey("db-key", initialDbSecret);

        String rotatedDbCipher = "ENC[db-key](" + ConfigEncryptor.encrypt("rotated-db-pass", rotatedDbSecret, EncryptAlgorithm.AES_GCM) + ")";
        String runtimeKeyCipher = "ENC[runtime-key](" + ConfigEncryptor.encrypt("runtime-pass", runtimeKeySecret, EncryptAlgorithm.AES_GCM) + ")";
        String nacosNewKeyCipher = "ENC[nacos-new-key](" + ConfigEncryptor.encrypt("nacos-new-pass", nacosNewKeySecret, EncryptAlgorithm.AES_GCM) + ")";

        ConfigDecryptor kmsDecryptor = new ConfigDecryptor() {
            @Override
            public boolean supports(EncryptAlgorithm algorithm) {
                return algorithm == EncryptAlgorithm.KMS;
            }

            @Override
            public String decrypt(String cipherText, String password) {
                return "kms-plain:" + cipherText;
            }
        };

        PlaintextIngressGuard guard = new PlaintextIngressGuard(
                properties, parser, managedRegistry, List.of(kmsDecryptor));

        // 模拟运行期密钥轮换与动态新增密钥（覆盖已有 db-key 与新增 runtime-key）
        managedRegistry.registerKey("db-key", rotatedDbSecret);
        managedRegistry.registerKey("runtime-key", runtimeKeySecret);

        StandardEnvironment env = new StandardEnvironment();
        Map<String, Object> appProps = new HashMap<>();
        // 环境中的旧配置仍保留 initialDbSecret，热更新时不得覆盖运行期已轮换密钥
        appProps.put("ateng.boot.encrypt.keys.db-key", initialDbSecret);
        appProps.put("ateng.boot.encrypt.keys.nacos-new-key", nacosNewKeySecret);
        appProps.put("app.db.secret", rotatedDbCipher);
        appProps.put("app.runtime.secret", runtimeKeyCipher);
        appProps.put("app.nacos.secret", nacosNewKeyCipher);
        appProps.put("app.kms.secret", "ENC(KMS@cipher-payload)");
        env.getPropertySources().addFirst(new MapPropertySource("nacosHotSource", appProps));

        guard.onEnvironmentChange(Set.of("app.db.secret", "app.kms.secret"), env);

        assertThat(managedRegistry.getKey("db-key")).isEqualTo(rotatedDbSecret);
        assertThat(managedRegistry.getKey("runtime-key")).isEqualTo(runtimeKeySecret);
        assertThat(managedRegistry.getKey("nacos-new-key")).isEqualTo(nacosNewKeySecret);

        PropertySource<?> wrapped = env.getPropertySources().get("nacosHotSource");
        assertThat(wrapped).isInstanceOf(EncryptPropertySourceWrapper.class);
        EncryptPropertySourceWrapper wrapper = (EncryptPropertySourceWrapper) wrapped;
        assertThat(wrapper.getKeyRegistry()).isSameAs(managedRegistry);

        assertThat(env.getProperty("app.db.secret")).isEqualTo("rotated-db-pass");
        assertThat(env.getProperty("app.runtime.secret")).isEqualTo("runtime-pass");
        assertThat(env.getProperty("app.nacos.secret")).isEqualTo("nacos-new-pass");
        assertThat(env.getProperty("app.kms.secret")).isEqualTo("kms-plain:cipher-payload");
    }

    @Test
    @DisplayName("onEnvironmentChange: 新纳管属性源在后续 MultiKeyRegistry 密钥轮换时缓存失效同步生效")
    void testNewlyWrappedSourceSharesRegistryForCacheInvalidation() {
        EncryptProperties properties = new EncryptProperties();
        properties.setPassword(masterPassword);
        MultiKeyRegistry managedRegistry = MultiKeyRegistry.fromProperties(properties);
        PlaintextIngressGuard guard = new PlaintextIngressGuard(properties, parser, managedRegistry, List.of());

        StandardEnvironment env = new StandardEnvironment();
        // 注册一个监听器模拟 AtengEncryptAutoConfiguration.onKeyRotated 清理环境内 wrapper 缓存
        managedRegistry.setEventPublisher(event -> {
            for (PropertySource<?> ps : env.getPropertySources()) {
                if (ps instanceof EncryptPropertySourceWrapper w) {
                    w.clearCache();
                }
            }
        });

        String keyV1 = "RotateKeyV1@2026";
        String keyV2 = "RotateKeyV2@2026";
        managedRegistry.registerKey("rotate-key", keyV1);

        String cipherV1 = "ENC[rotate-key](" + ConfigEncryptor.encrypt("val-v1", keyV1, EncryptAlgorithm.AES_GCM) + ")";
        String cipherV2 = "ENC[rotate-key](" + ConfigEncryptor.encrypt("val-v2", keyV2, EncryptAlgorithm.AES_GCM) + ")";

        Map<String, Object> hotMap = new HashMap<>();
        hotMap.put("app.dynamic.token", cipherV1);
        env.getPropertySources().addFirst(new MapPropertySource("nacosDynamicSource", hotMap));

        guard.onEnvironmentChange(Set.of("app.dynamic.token"), env);
        assertThat(env.getProperty("app.dynamic.token")).isEqualTo("val-v1");

        // 模拟配置中心推送 V2 密文且后台同步轮换受管 MultiKeyRegistry 中的密钥
        hotMap.put("app.dynamic.token", cipherV2);
        managedRegistry.registerKey("rotate-key", keyV2);

        assertThat(env.getProperty("app.dynamic.token")).isEqualTo("val-v2");
    }
}
