package io.github.atengk.boot.encrypt.guard;

import io.github.atengk.boot.encrypt.exception.ConfigSecurityException;
import io.github.atengk.boot.encrypt.parser.CiphertextParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
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

    @Test
    @DisplayName("enforce: 协议路径特征防误伤豁免 (http://, https://, classpath:, /)")
    void testProtocolImmunityExemption() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("oauthConfig", Map.of(
                "spring.security.oauth2.client.provider.oidc.token-uri", "https://auth.com/token",
                "custom.secret.service-url", "http://auth.internal:8080/secret",
                "security.jwt.token-key-location", "classpath:certs/token-pub.pem",
                "security.ssl.private-key-path", "/etc/ssl/certs/private-key.key"
        )));

        assertThatCode(() -> EnforceEncryptionPolicy.enforce(env, pattern, parser))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("enforce: 日志级别特征防误伤豁免 (DEBUG, INFO, WARN, ERROR, TRACE)")
    void testLogLevelImmunityExemption() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("logConfig", Map.of(
                "logging.level.com.example.secret.service", "DEBUG",
                "logging.level.org.token.manager", "TRACE",
                "logging.level.security.password.service", "INFO",
                "logging.level.audit.secret.filter", "WARN",
                "logging.level.auth.token.validator", "ERROR"
        )));

        assertThatCode(() -> EnforceEncryptionPolicy.enforce(env, pattern, parser))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("enforce: 显式白名单机制 (ignoredKeys 与 ateng.boot.encrypt.ignored-keys)")
    void testExplicitIgnoredKeysWhitelist() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("customConfig", Map.of(
                "app.jwt.secret", "plain_secret_text",
                "ateng.boot.encrypt.ignored-keys[0]", "app.jwt.secret"
        )));

        // 从 environment 自动 Binder 读取 ignored-keys 白名单
        assertThatCode(() -> EnforceEncryptionPolicy.enforce(env, pattern, parser))
                .doesNotThrowAnyException();

        // 显式入参传入 ignoredKeys 集合
        StandardEnvironment env2 = new StandardEnvironment();
        env2.getPropertySources().addFirst(new MapPropertySource("customConfig2", Map.of(
                "app.jwt.secret", "plain_secret_text"
        )));
        assertThatCode(() -> EnforceEncryptionPolicy.enforce(env2, pattern, Set.of("app.jwt.secret"), parser))
                .doesNotThrowAnyException();

        // 不在白名单且未加密时严格阻断
        StandardEnvironment env3 = new StandardEnvironment();
        env3.getPropertySources().addFirst(new MapPropertySource("customConfig3", Map.of(
                "app.jwt.secret", "plain_secret_text"
        )));
        assertThatThrownBy(() -> EnforceEncryptionPolicy.enforce(env3, pattern, Set.of("other.key"), parser))
                .isInstanceOf(ConfigSecurityException.class)
                .hasMessageContaining("app.jwt.secret");
    }

    @Test
    @DisplayName("enforce: Spring Cloud {cipher} 模式受控兼容检测")
    void testSpringCloudCipherCompatibilityInEnforce() {
        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(new MapPropertySource("cipherConfig", Map.of(
                "spring.datasource.password", "{cipher}AQB123456789=="
        )));

        // 关闭 compatible-cipher-prefix 时，{cipher} 视作普通明文，被刚性拦截
        CiphertextParser disabledParser = new CiphertextParser(false);
        assertThatThrownBy(() -> EnforceEncryptionPolicy.enforce(env, pattern, disabledParser))
                .isInstanceOf(ConfigSecurityException.class)
                .hasMessageContaining("spring.datasource.password");

        // 开启 compatible-cipher-prefix 时，{cipher} 识别为密文，顺利放行
        CiphertextParser enabledParser = new CiphertextParser(true);
        assertThatCode(() -> EnforceEncryptionPolicy.enforce(env, pattern, enabledParser))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("isExemptedValue: 基础豁免判定单元覆盖")
    void testIsExemptedValue() {
        assertThat(EnforceEncryptionPolicy.isExemptedValue(null)).isFalse();
        assertThat(EnforceEncryptionPolicy.isExemptedValue("")).isFalse();
        assertThat(EnforceEncryptionPolicy.isExemptedValue("   ")).isFalse();
        assertThat(EnforceEncryptionPolicy.isExemptedValue("https://example.com/token")).isTrue();
        assertThat(EnforceEncryptionPolicy.isExemptedValue("HTTP://localhost:8080")).isTrue();
        assertThat(EnforceEncryptionPolicy.isExemptedValue("classpath:certs/key.pem")).isTrue();
        assertThat(EnforceEncryptionPolicy.isExemptedValue("/var/data/secret")).isTrue();
        assertThat(EnforceEncryptionPolicy.isExemptedValue("DEBUG")).isTrue();
        assertThat(EnforceEncryptionPolicy.isExemptedValue("info")).isTrue();
        assertThat(EnforceEncryptionPolicy.isExemptedValue("warn")).isTrue();
        assertThat(EnforceEncryptionPolicy.isExemptedValue("ERROR")).isTrue();
        assertThat(EnforceEncryptionPolicy.isExemptedValue("trace")).isTrue();
        assertThat(EnforceEncryptionPolicy.isExemptedValue("mySecretPassword123")).isFalse();
    }
}
