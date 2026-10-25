package io.github.atengk.boot.encrypt.guard;

import io.github.atengk.boot.encrypt.exception.ConfigSecurityException;
import io.github.atengk.boot.encrypt.parser.CiphertextParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 生产强制加密门禁单元测试
 *
 * @author Ateng
 * @since 2026-10-25
 */
@DisplayName("生产环境防明文误提交与强制加密门禁单元测试")
class EnforceEncryptionPolicyTest {

    private final CiphertextParser parser = new CiphertextParser();
    private final String pattern = ".*password.*|.*secret.*|.*token.*|.*private-key.*";

    @Test
    @DisplayName("enforce: 敏感属性为裸明文时必须抛出 ConfigSecurityException 刚性阻断")
    void testEnforceFailsOnPlaintextSensitiveProperty() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("prodConfig", Map.of(
                "spring.datasource.password", "root123456",
                "server.port", 8080
        )));

        assertThatThrownBy(() -> EnforceEncryptionPolicy.enforce(env, pattern, parser))
                .isInstanceOf(ConfigSecurityException.class)
                .hasMessageContaining("spring.datasource.password");
    }

    @Test
    @DisplayName("enforce: 敏感属性均已使用 ENC(...) 加密时平滑放行")
    void testEnforcePassesWhenAllSensitivePropertiesAreEncrypted() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("prodConfig", Map.of(
                "spring.datasource.password", "ENC(AES-GCM@dGhpcyBpcyBhbiBlbmNyeXB0ZWQgcGFzc3dvcmQ=)",
                "app.jwt.secret", "ENC(c2VjcmV0X3Rva2Vu)",
                "server.port", 8080
        )));

        assertThatCode(() -> EnforceEncryptionPolicy.enforce(env, pattern, parser))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("enforce: 排除框架自身主密钥与内部系统属性")
    void testEnforceIgnoresInternalEncryptProperties() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("commandLine", Map.of(
                "ateng.boot.encrypt.password", "MyMasterPassword2026",
                "ateng.boot.encrypt.keys.key1", "SubKey123456"
        )));

        assertThatCode(() -> EnforceEncryptionPolicy.enforce(env, pattern, parser))
                .doesNotThrowAnyException();
    }
}
