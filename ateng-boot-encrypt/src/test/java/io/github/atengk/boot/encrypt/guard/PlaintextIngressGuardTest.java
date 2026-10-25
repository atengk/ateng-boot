package io.github.atengk.boot.encrypt.guard;

import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;
import io.github.atengk.boot.encrypt.config.EncryptProperties;
import io.github.atengk.boot.encrypt.decryptor.AesGcmConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.ConfigEncryptor;
import io.github.atengk.boot.encrypt.detector.KeyDetector;
import io.github.atengk.boot.encrypt.exception.ConfigSecurityException;
import io.github.atengk.boot.encrypt.parser.CiphertextParser;
import io.github.atengk.boot.encrypt.wrapper.EncryptPropertySourceWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.HashMap;
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
}
