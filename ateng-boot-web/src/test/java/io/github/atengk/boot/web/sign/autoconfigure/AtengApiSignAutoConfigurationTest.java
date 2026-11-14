package io.github.atengk.boot.web.sign.autoconfigure;

import io.github.atengk.boot.web.idempotent.autoconfigure.AtengIdempotentAutoConfiguration;
import io.github.atengk.boot.web.properties.WebProperties;
import io.github.atengk.boot.web.sign.aspect.ApiSignAspect;
import io.github.atengk.boot.web.sign.enums.SignAlgorithm;
import io.github.atengk.boot.web.sign.filter.RequestBodyCachingFilter;
import io.github.atengk.boot.web.sign.signer.HmacSha256Signer;
import io.github.atengk.boot.web.sign.signer.RsaSigner;
import io.github.atengk.boot.web.sign.signer.Sm2Signer;
import io.github.atengk.boot.web.sign.spi.ApiSigner;
import io.github.atengk.boot.web.sign.spi.SignKeyProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * API 加签验签自动配置单元测试
 *
 * @author Ateng
 * @since 2026-10-16
 */
@DisplayName("API 加签验签自动配置单元测试")
class AtengApiSignAutoConfigurationTest {

    private final WebApplicationContextRunner contextRunner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    AtengIdempotentAutoConfiguration.class,
                    AtengApiSignAutoConfiguration.class
            ));

    @Test
    @DisplayName("默认未配置 enabled 时不装配任何加签验签 Bean")
    void shouldNotLoadWhenDisabledByDefault() {
        contextRunner.run(context -> {
            assertThat(context).doesNotHaveBean(ApiSignAspect.class);
            assertThat(context).doesNotHaveBean(ApiSigner.class);
            assertThat(context).doesNotHaveBean(SignKeyProvider.class);
        });
    }

    @Test
    @DisplayName("配置 ateng.boot.web.sign.enabled=true 时默认装配 HMAC-SHA256 签名器与切面")
    void shouldLoadDefaultHmacSignerWhenEnabled() {
        contextRunner.withPropertyValues(
                "ateng.boot.web.sign.enabled=true",
                "ateng.boot.web.sign.secret-key=TestSecretKey123"
        ).run(context -> {
            assertThat(context).hasSingleBean(ApiSignAspect.class);
            assertThat(context).hasSingleBean(SignKeyProvider.class);
            assertThat(context).hasSingleBean(ApiSigner.class);
            assertThat(context.getBean(ApiSigner.class)).isInstanceOf(HmacSha256Signer.class);
            assertThat(context).hasBean("requestBodyCachingFilterRegistration");
        });
    }

    @Test
    @DisplayName("配置 algorithm=RSA 时自动装配 RsaSigner 实例")
    void shouldLoadRsaSignerWhenConfigured() {
        contextRunner.withPropertyValues(
                "ateng.boot.web.sign.enabled=true",
                "ateng.boot.web.sign.algorithm=RSA"
        ).run(context -> {
            assertThat(context).hasSingleBean(ApiSigner.class);
            assertThat(context.getBean(ApiSigner.class)).isInstanceOf(RsaSigner.class);
        });
    }

    @Test
    @DisplayName("配置 algorithm=SM2 时自动装配 Sm2Signer 实例")
    void shouldLoadSm2SignerWhenConfigured() {
        contextRunner.withPropertyValues(
                "ateng.boot.web.sign.enabled=true",
                "ateng.boot.web.sign.algorithm=SM2"
        ).run(context -> {
            assertThat(context).hasSingleBean(ApiSigner.class);
            assertThat(context.getBean(ApiSigner.class)).isInstanceOf(Sm2Signer.class);
        });
    }

    @Test
    @DisplayName("支持业务自定义 ApiSigner 与 SignKeyProvider 时平滑退让")
    void shouldBackOffWhenCustomBeansProvided() {
        ApiSigner customSigner = new ApiSigner() {
            @Override
            public String sign(String data, String key) {
                return "custom-sig";
            }

            @Override
            public boolean verify(String data, String sig, String key) {
                return true;
            }
        };

        contextRunner.withPropertyValues("ateng.boot.web.sign.enabled=true")
                .withBean(ApiSigner.class, () -> customSigner)
                .run(context -> {
                    assertThat(context).hasSingleBean(ApiSigner.class);
                    assertThat(context.getBean(ApiSigner.class)).isSameAs(customSigner);
                });
    }

    @Test
    @DisplayName("完整绑定 nonce-prefix、fail-open 与分算法非对称密钥并向 DefaultSignKeyProvider 透传")
    void shouldBindNoncePolicyAndPerAlgorithmKeys() {
        contextRunner.withPropertyValues(
                "ateng.boot.web.sign.enabled=true",
                "ateng.boot.web.sign.secret-key=hmac-secret",
                "ateng.boot.web.sign.rsa-public-key=rsa-pub",
                "ateng.boot.web.sign.rsa-private-key=rsa-priv",
                "ateng.boot.web.sign.sm2-public-key=sm2-pub",
                "ateng.boot.web.sign.sm2-private-key=sm2-priv",
                "ateng.boot.web.sign.nonce-prefix=custom:nonce:",
                "ateng.boot.web.sign.fail-open=true"
        ).run(context -> {
            WebProperties props = context.getBean(WebProperties.class);
            assertThat(props.getSign().getNoncePrefix()).isEqualTo("custom:nonce:");
            assertThat(props.getSign().isFailOpen()).isTrue();

            SignKeyProvider provider = context.getBean(SignKeyProvider.class);
            assertThat(provider.getVerifyKey("app", SignAlgorithm.HMAC_SHA256)).isEqualTo("hmac-secret");
            assertThat(provider.getVerifyKey("app", SignAlgorithm.RSA)).isEqualTo("rsa-pub");
            assertThat(provider.getSignKey("app", SignAlgorithm.RSA)).isEqualTo("rsa-priv");
            assertThat(provider.getVerifyKey("app", SignAlgorithm.SM2)).isEqualTo("sm2-pub");
            assertThat(provider.getSignKey("app", SignAlgorithm.SM2)).isEqualTo("sm2-priv");
        });
    }
}
