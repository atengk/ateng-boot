package io.github.atengk.boot.web.crypto.autoconfigure;

import io.github.atengk.boot.web.autoconfigure.AtengWebAutoConfiguration;
import io.github.atengk.boot.web.crypto.cipher.RsaAesGcmApiCipher;
import io.github.atengk.boot.web.crypto.cipher.Sm2Sm4GcmApiCipher;
import io.github.atengk.boot.web.crypto.enums.CryptoAlgorithm;
import io.github.atengk.boot.web.crypto.filter.ApiCryptoFilter;
import io.github.atengk.boot.web.crypto.model.ApiCipherInput;
import io.github.atengk.boot.web.crypto.model.ApiCipherResult;
import io.github.atengk.boot.web.crypto.model.ApiCryptoContext;
import io.github.atengk.boot.web.crypto.spi.ApiCipher;
import io.github.atengk.boot.web.crypto.spi.ApiCryptoKeyProvider;
import io.github.atengk.boot.web.properties.WebProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API 传输层混合加解密自动配置单元测试
 *
 * @author Ateng
 * @since 2026-11-27
 */
@DisplayName("API 传输层混合加解密自动配置单元测试")
class AtengApiCryptoAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AtengWebAutoConfiguration.class,
                    AtengApiCryptoAutoConfiguration.class
            ));

    @Test
    @DisplayName("默认未配置 crypto.enabled=true 时零装配，不激活 ApiCryptoFilter 与 ContentCachingFilter")
    void shouldNotLoadWhenDisabledByDefault() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean("apiCryptoFilterRegistration");
            assertThat(context).doesNotHaveBean(ApiCryptoKeyProvider.class);
            assertThat(context).doesNotHaveBean(ApiCipher.class);
            assertThat(context).doesNotHaveBean("contentCachingFilterRegistration");
        });
    }

    @Test
    @DisplayName("配置 ateng.boot.web.crypto.enabled=true 时自动装配双套件引擎、密钥提供者与按需过滤器")
    void shouldLoadAllCryptoBeansWhenEnabled() {
        contextRunner.withPropertyValues(
                "ateng.boot.web.crypto.enabled=true",
                "ateng.boot.web.crypto.algorithm=SM2_SM4_GCM",
                "ateng.boot.web.crypto.active-key-id=v2",
                "ateng.boot.web.crypto.bind-aad=true",
                "ateng.boot.web.crypto.use-http-status=true",
                "ateng.boot.web.crypto.max-body-size=512KB",
                "ateng.boot.web.crypto.keys.v2.enabled=true",
                "ateng.boot.web.crypto.keys.v2.sm2-server-private-key=priv-v2"
        ).run(context -> {
            assertThat(context).hasBean("apiCryptoFilterRegistration");
            assertThat(context).hasBean("contentCachingFilterRegistration");
            assertThat(context).hasSingleBean(ApiCryptoKeyProvider.class);
            assertThat(context).hasSingleBean(RsaAesGcmApiCipher.class);
            assertThat(context).hasSingleBean(Sm2Sm4GcmApiCipher.class);

            FilterRegistrationBean<?> registration = context.getBean("apiCryptoFilterRegistration", FilterRegistrationBean.class);
            assertThat(registration.getFilter()).isInstanceOf(ApiCryptoFilter.class);
            assertThat(registration.getOrder()).isEqualTo(ApiCryptoFilter.ORDER);

            WebProperties props = context.getBean(WebProperties.class);
            assertThat(props.getCrypto().getAlgorithm()).isEqualTo(CryptoAlgorithm.SM2_SM4_GCM);
            assertThat(props.getCrypto().getActiveKeyId()).isEqualTo("v2");
            assertThat(props.getCrypto().isBindAad()).isTrue();
            assertThat(props.getCrypto().isUseHttpStatus()).isTrue();
            assertThat(props.getCrypto().getMaxBodySize().toKilobytes()).isEqualTo(512L);
            assertThat(context.getBean(ApiCryptoKeyProvider.class)
                    .getServerPrivateKey("default", "v2", CryptoAlgorithm.SM2_SM4_GCM))
                    .isEqualTo("priv-v2");
        });
    }

    @Test
    @DisplayName("支持自定义 ApiCryptoKeyProvider 与 RsaAesGcmApiCipher Bean 平滑接管")
    void shouldBackOffWhenCustomBeansProvided() {
        ApiCryptoKeyProvider customProvider = new ApiCryptoKeyProvider() {
            @Override
            public String getServerPrivateKey(String appId, String keyId, CryptoAlgorithm algorithm) {
                return "custom-priv";
            }

            @Override
            public String getClientPublicKey(String appId, String keyId, CryptoAlgorithm algorithm) {
                return "custom-pub";
            }
        };
        RsaAesGcmApiCipher customRsaCipher = new RsaAesGcmApiCipher() {
            @Override
            public ApiCipherResult encrypt(byte[] plaintext, ApiCryptoContext context) {
                return super.encrypt(plaintext, context);
            }

            @Override
            public byte[] decrypt(ApiCipherInput input, ApiCryptoContext context) {
                return super.decrypt(input, context);
            }
        };

        contextRunner.withPropertyValues("ateng.boot.web.crypto.enabled=true")
                .withBean(ApiCryptoKeyProvider.class, () -> customProvider)
                .withBean(RsaAesGcmApiCipher.class, () -> customRsaCipher)
                .run(context -> {
                    assertThat(context.getBean(ApiCryptoKeyProvider.class)).isSameAs(customProvider);
                    assertThat(context.getBean(RsaAesGcmApiCipher.class)).isSameAs(customRsaCipher);
                });
    }
}
