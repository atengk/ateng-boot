package io.github.atengk.boot.sample.web.crypto;

import io.github.atengk.boot.web.crypto.enums.CryptoAlgorithm;
import io.github.atengk.boot.web.crypto.provider.DefaultApiCryptoKeyProvider;
import io.github.atengk.boot.web.crypto.spi.ApiCryptoKeyProvider;
import io.github.atengk.boot.web.properties.WebProperties;
import io.github.atengk.boot.web.sign.signer.Sm2Signer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.util.StringUtils;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

/**
 * Sample 默认离线模式（{@code !local}）零配置体验用传输层临时密钥装配器
 * 严格遵循“仓库零明文硬编码密钥”安全基线：运行期动态生成内存临时 RSA-2048 与国密 SM2 密钥对，
 * 当配置了 YAML 显式密钥（含单测 {@code @DynamicPropertySource} 或 {@code local} 环境）时自动优先委托默认提供者
 *
 * @author Ateng
 * @since 2026-11-27
 */
@Configuration
@Profile("!local")
public class SampleCryptoKeyConfiguration {

    private final KeyPair rsaServerKeyPair = generateRsaKeyPair();
    private final KeyPair rsaClientKeyPair = generateRsaKeyPair();
    private final Sm2Signer.Sm2KeyPair sm2ServerKeyPair = Sm2Signer.generateKeyPair();
    private final Sm2Signer.Sm2KeyPair sm2ClientKeyPair = Sm2Signer.generateKeyPair();

    /**
     * 装配 Sample 专用传输层密钥提供者：若外部属性已显式提供私钥则优先委托 {@link DefaultApiCryptoKeyProvider}，
     * 否则自动回退至内存临时生成的演示密钥对以保障开箱即用与 Zero-Secret 规范
     *
     * @param webProperties Web 属性配置
     * @return ApiCryptoKeyProvider 实例
     */
    @Bean
    @ConditionalOnProperty(prefix = "ateng.boot.web.crypto", name = "enabled", havingValue = "true")
    public ApiCryptoKeyProvider sampleApiCryptoKeyProvider(WebProperties webProperties) {
        WebProperties.CryptoProperties cryptoProps = webProperties.getCrypto();
        DefaultApiCryptoKeyProvider delegate = new DefaultApiCryptoKeyProvider(cryptoProps);
        return new ApiCryptoKeyProvider() {
            @Override
            public String getServerPrivateKey(String appId, String keyId, CryptoAlgorithm algorithm) {
                String configured = safeDelegateGet(() -> delegate.getServerPrivateKey(appId, keyId, algorithm));
                if (isConfiguredKey(configured)) {
                    return configured;
                }
                return algorithm == CryptoAlgorithm.SM2_SM4_GCM
                        ? sm2ServerKeyPair.privateKeyHex()
                        : encodePrivate(rsaServerKeyPair);
            }

            @Override
            public String getClientPublicKey(String appId, String keyId, CryptoAlgorithm algorithm) {
                String configured = safeDelegateGet(() -> delegate.getClientPublicKey(appId, keyId, algorithm));
                if (isConfiguredKey(configured)) {
                    return configured;
                }
                return algorithm == CryptoAlgorithm.SM2_SM4_GCM
                        ? sm2ClientKeyPair.publicKeyHex()
                        : encodePublic(rsaClientKeyPair);
            }

            @Override
            public String getActiveKeyId(String appId, CryptoAlgorithm algorithm) {
                return delegate.getActiveKeyId(appId, algorithm);
            }
        };
    }

    /**
     * 获取演示用服务端加密公钥（优先读取显式配置，其次返回内存临时公钥）
     */
    public String resolveServerPublicKey(WebProperties.CryptoProperties props, CryptoAlgorithm algorithm) {
        if (algorithm == CryptoAlgorithm.SM2_SM4_GCM) {
            if (isConfiguredKey(props.getSm2ServerPublicKey())) {
                return props.getSm2ServerPublicKey();
            }
            return sm2ServerKeyPair.publicKeyHex();
        }
        if (isConfiguredKey(props.getRsaServerPublicKey())) {
            return props.getRsaServerPublicKey();
        }
        return encodePublic(rsaServerKeyPair);
    }

    /**
     * 获取内存临时客户端解密私钥（仅用于测试或演示辅助闭环验证）
     */
    public String getEphemeralClientPrivateKey(CryptoAlgorithm algorithm) {
        return algorithm == CryptoAlgorithm.SM2_SM4_GCM
                ? sm2ClientKeyPair.privateKeyHex()
                : encodePrivate(rsaClientKeyPair);
    }

    private static String safeDelegateGet(java.util.function.Supplier<String> supplier) {
        try {
            return supplier.get();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static boolean isConfiguredKey(String value) {
        return StringUtils.hasText(value) && !value.startsWith("YOUR_");
    }

    private static KeyPair generateRsaKeyPair() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("初始化 Sample 临时 RSA 密钥对失败", e);
        }
    }

    private static String encodePublic(KeyPair kp) {
        return Base64.getEncoder().encodeToString(kp.getPublic().getEncoded());
    }

    private static String encodePrivate(KeyPair kp) {
        return Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded());
    }
}
