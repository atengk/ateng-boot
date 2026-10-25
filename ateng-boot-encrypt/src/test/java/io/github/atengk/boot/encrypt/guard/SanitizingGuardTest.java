package io.github.atengk.boot.encrypt.guard;

import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;
import io.github.atengk.boot.encrypt.config.EncryptProperties;
import io.github.atengk.boot.encrypt.detector.KeyDetector;
import io.github.atengk.boot.encrypt.parser.CiphertextParser;
import io.github.atengk.boot.encrypt.wrapper.EncryptPropertySourceWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.endpoint.SanitizableData;
import org.springframework.core.env.MapPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Actuator 运行时端点物理掩码脱敏守卫单元测试
 *
 * @author Ateng
 * @since 2026-10-25
 */
@DisplayName("Actuator 运行时端点物理掩码脱敏守卫单元测试")
class SanitizingGuardTest {

    private final EncryptProperties properties = new EncryptProperties();
    private final CiphertextParser parser = new CiphertextParser();
    private final SanitizingGuard guard = new SanitizingGuard(properties, parser);

    @Test
    @DisplayName("apply: 曾被加密的属性在 Actuator 端点必须被物理掩码为 ******")
    void testSanitizesEncryptedProperties() {
        Map<String, Object> map = Map.of(
                "custom.server.rpc-target", "ENC(AES-GCM@dGVzdF9jaXBoZXI=)"
        );
        MapPropertySource rawSource = new MapPropertySource("testSource", map);
        EncryptPropertySourceWrapper wrapper = new EncryptPropertySourceWrapper(
                rawSource, parser, new KeyDetector("pass", Map.of()), null, EncryptAlgorithm.AES_GCM, true
        );

        // 模拟已解析出的真实明文
        SanitizableData data = new SanitizableData(wrapper, "custom.server.rpc-target", "192.168.1.100:9090");
        SanitizableData result = guard.apply(data);

        assertThat(result.getValue()).isEqualTo(SanitizableData.SANITIZED_VALUE);
    }

    @Test
    @DisplayName("apply: 键名匹配敏感模式（如 password/secret/token）必须被物理掩码")
    void testSanitizesSensitiveKeyPattern() {
        MapPropertySource source = new MapPropertySource("map", Map.of("spring.datasource.password", "plain_123456"));
        SanitizableData data = new SanitizableData(source, "spring.datasource.password", "plain_123456");

        SanitizableData result = guard.apply(data);
        assertThat(result.getValue()).isEqualTo(SanitizableData.SANITIZED_VALUE);
    }

    @Test
    @DisplayName("apply: 普通非敏感且未加密的属性原样放行")
    void testPreservesNormalProperties() {
        MapPropertySource source = new MapPropertySource("map", Map.of("server.port", 8080));
        SanitizableData data = new SanitizableData(source, "server.port", 8080);

        SanitizableData result = guard.apply(data);
        assertThat(result.getValue()).isEqualTo(8080);
    }
}
