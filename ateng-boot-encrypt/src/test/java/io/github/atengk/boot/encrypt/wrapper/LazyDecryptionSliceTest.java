package io.github.atengk.boot.encrypt.wrapper;

import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;
import io.github.atengk.boot.encrypt.detector.KeyDetector;
import io.github.atengk.boot.encrypt.exception.ConfigDecryptionException;
import io.github.atengk.boot.encrypt.parser.CiphertextParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 按需懒解密与切片测试友好性单元测试
 * 验证在未注入任何解密密钥的情况下，未被引用的敏感配置绝不阻塞切片单测
 *
 * @author Ateng
 * @since 2026-10-25
 */
@DisplayName("按需懒解密与 Spring 切片测试友好性单元测试")
class LazyDecryptionSliceTest {

    @Test
    @DisplayName("按需懒解密: 切片测试中未引用的加密属性，即使缺失解密密钥也绝不报错")
    void testUnaccessedEncryptedPropertyDoesNotBlockSlicedContext() {
        // 模拟 application.yml 中存在未注入密钥的加密密文
        Map<String, Object> map = new HashMap<>();
        map.put("spring.datasource.password", "ENC(AES-GCM@dGhpcyBpcyBhbiBlbmNyeXB0ZWQgcGFzc3dvcmQ=)");
        map.put("spring.redis.password", "ENC(SM4@YWJjZGVmZ2hpamtsbW5vcA==)");
        map.put("app.name", "slice-test-service");
        map.put("server.port", 8080);

        MapPropertySource rawSource = new MapPropertySource("application.yml", map);

        // 未配置任何密钥
        KeyDetector emptyDetector = new KeyDetector(null, Map.of());

        EncryptPropertySourceWrapper wrapper = new EncryptPropertySourceWrapper(
                rawSource,
                new CiphertextParser(),
                emptyDetector,
                null,
                EncryptAlgorithm.AES_GCM,
                true
        );

        StandardEnvironment env = new StandardEnvironment();
        env.getPropertySources().addFirst(wrapper);

        // 1. 切片测试（如 @WebMvcTest / @JsonTest）仅请求 controller 相关的常规属性
        assertThatCode(() -> {
            assertThat(env.getProperty("app.name")).isEqualTo("slice-test-service");
            assertThat(env.getProperty("server.port")).isEqualTo("8080");
        }).doesNotThrowAnyException();

        // 2. 仅当真正访问未提供密钥的加密字段时，才触发按需解密并抛出清晰异常
        assertThatThrownBy(() -> env.getProperty("spring.datasource.password"))
                .isInstanceOf(ConfigDecryptionException.class)
                .hasMessageContaining("spring.datasource.password")
                .hasMessageContaining("缺失解密主密钥");
    }
}
