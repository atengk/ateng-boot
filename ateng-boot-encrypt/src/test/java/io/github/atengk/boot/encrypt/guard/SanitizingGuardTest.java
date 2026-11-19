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

    @Test
    @DisplayName("apply: 主密钥与任意命名多密钥键前缀（忽略大小写且不依赖敏感词正则）必须无条件物理掩码为 ******")
    void testSanitizesMasterPasswordAndAllNamedKeysUnconditionally() {
        EncryptProperties customProps = new EncryptProperties();
        // 即便业务侧覆盖敏感词正则为仅匹配自定义关键词，主密钥与多密钥前缀也必须无条件脱敏
        customProps.setSensitiveKeyPattern(".*only-this-custom-keyword.*");
        SanitizingGuard strictGuard = new SanitizingGuard(customProps, parser);

        MapPropertySource source = new MapPropertySource("applicationConfig", Map.of());

        SanitizableData masterData = new SanitizableData(source, "ateng.boot.encrypt.password", "MasterPlain@2026");
        assertThat(strictGuard.apply(masterData).getValue()).isEqualTo(SanitizableData.SANITIZED_VALUE);

        SanitizableData upperMasterData = new SanitizableData(source, "ATENG.BOOT.ENCRYPT.PASSWORD", "MasterUpper@2026");
        assertThat(strictGuard.apply(upperMasterData).getValue()).isEqualTo(SanitizableData.SANITIZED_VALUE);

        SanitizableData envMasterData = new SanitizableData(source, "ATENG_BOOT_ENCRYPT_PASSWORD", "MasterEnv@2026");
        assertThat(strictGuard.apply(envMasterData).getValue()).isEqualTo(SanitizableData.SANITIZED_VALUE);

        SanitizableData bracketNamedKeyData = new SanitizableData(source, "ateng.boot.encrypt.keys[alpha]", "BracketShardKey@2026");
        assertThat(strictGuard.apply(bracketNamedKeyData).getValue()).isEqualTo(SanitizableData.SANITIZED_VALUE);

        SanitizableData namedKeyData = new SanitizableData(source, "ateng.boot.encrypt.keys.redis-cluster", "ClusterShardKey@2026");
        assertThat(strictGuard.apply(namedKeyData).getValue()).isEqualTo(SanitizableData.SANITIZED_VALUE);

        SanitizableData upperNamedKeyData = new SanitizableData(source, "ATENG.BOOT.ENCRYPT.KEYS.PAYMENT_SHARD", "PaymentShardKey@2026");
        assertThat(strictGuard.apply(upperNamedKeyData).getValue()).isEqualTo(SanitizableData.SANITIZED_VALUE);

        SanitizableData nonSensitiveNamedKeyData = new SanitizableData(source, "ateng.boot.encrypt.keys.alpha", "AlphaKeyWithoutSensitiveWord");
        assertThat(strictGuard.apply(nonSensitiveNamedKeyData).getValue()).isEqualTo(SanitizableData.SANITIZED_VALUE);

        // 非密钥元配置（如算法枚举）与普通业务配置应继续原样放行
        SanitizableData algoData = new SanitizableData(source, "ateng.boot.encrypt.algorithm", "AES-GCM");
        assertThat(strictGuard.apply(algoData).getValue()).isEqualTo("AES-GCM");
    }
}
