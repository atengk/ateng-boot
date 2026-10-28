package io.github.atengk.boot.encrypt.detector;

import io.github.atengk.boot.encrypt.exception.ConfigSecurityException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 密钥探测与安全守卫单元测试
 *
 * @author Ateng
 * @since 2026-10-25
 */
class KeyDetectorTest {

    @AfterEach
    void tearDown() {
        System.clearProperty(KeyDetector.PROPERTY_PASSWORD);
    }

    @Test
    @DisplayName("测试从 JVM 系统属性 (-D) 获取主密钥")
    void testResolveFromSystemProperty() {
        System.setProperty(KeyDetector.PROPERTY_PASSWORD, "jvm_secret_pass");
        StandardEnvironment env = new StandardEnvironment();

        KeyDetector detector = KeyDetector.detect(env);
        assertEquals("jvm_secret_pass", detector.getPassword("default"));
        assertEquals("jvm_secret_pass", detector.getPassword(null));
    }

    @Test
    @DisplayName("测试多密钥命名注册表解析 (ateng.boot.encrypt.keys.*)")
    void testResolveNamedKeys() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addLast(new MapPropertySource("customConfig", Map.of(
                "ateng.boot.encrypt.keys.finance", "finance_secret_key",
                "ateng.boot.encrypt.keys.user", "user_secret_key"
        )));

        KeyDetector detector = KeyDetector.detect(env);
        assertEquals("finance_secret_key", detector.getPassword("finance"));
        assertEquals("user_secret_key", detector.getPassword("user"));
    }

    @Test
    @DisplayName("测试安全守卫：严禁从正在解密的本地 YAML 配置文件本身读取密钥")
    void testSelfEncryptionRiskThrowsSecurityException() {
        StandardEnvironment env = new StandardEnvironment();
        // 模拟本地 application.yml 配置源
        env.getPropertySources().addLast(new MapPropertySource(
                "Config resource 'class path resource [application.yml]'",
                Map.of(KeyDetector.PROPERTY_PASSWORD, "hardcoded_password_in_yaml")
        ));

        // 外部没有任何注入，此时必须触发安全阻断
        assertThrows(ConfigSecurityException.class, () -> KeyDetector.detect(env));
    }
}
