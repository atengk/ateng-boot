package io.github.atengk.boot.encrypt.autoconfigure;

import io.github.atengk.boot.encrypt.decryptor.AesCbcConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.AesGcmConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.RsaConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.Sm2ConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.Sm4ConfigDecryptor;
import io.github.atengk.boot.encrypt.guard.PlaintextIngressGuard;
import io.github.atengk.boot.encrypt.guard.SanitizingGuard;
import io.github.atengk.boot.encrypt.parser.CiphertextParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AtengEncryptAutoConfiguration 自动装配单元测试
 *
 * @author Ateng
 * @since 2026-10-25
 */
@DisplayName("配置加解密核心自动装配单元测试")
class AtengEncryptAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengEncryptAutoConfiguration.class));

    @Test
    @DisplayName("默认自动装配: 所有加解密器、语法解析器、防倒灌守卫与 Actuator 脱敏守卫均成功注入")
    void testDefaultAutoConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(CiphertextParser.class);
            assertThat(context).hasSingleBean(AesGcmConfigDecryptor.class);
            assertThat(context).hasSingleBean(AesCbcConfigDecryptor.class);
            assertThat(context).hasSingleBean(Sm4ConfigDecryptor.class);
            assertThat(context).hasSingleBean(RsaConfigDecryptor.class);
            assertThat(context).hasSingleBean(Sm2ConfigDecryptor.class);
            assertThat(context).hasSingleBean(PlaintextIngressGuard.class);
            assertThat(context).hasSingleBean(SanitizingGuard.class);
        });
    }

    @Test
    @DisplayName("配置禁用: ateng.boot.encrypt.enabled=false 时不装配任何 Bean")
    void testDisabledConfiguration() {
        contextRunner.withPropertyValues("ateng.boot.encrypt.enabled=false")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(CiphertextParser.class);
                    assertThat(context).doesNotHaveBean(AesGcmConfigDecryptor.class);
                    assertThat(context).doesNotHaveBean(PlaintextIngressGuard.class);
                });
    }

    @Test
    @DisplayName("开发者定制接管: 用户自定义 Bean 时平滑覆盖框架默认 Bean")
    void testCustomBeanOverride() {
        CiphertextParser customParser = new CiphertextParser("MY_ENC[", "]");
        contextRunner.withBean("customParser", CiphertextParser.class, () -> customParser)
                .run(context -> {
                    assertThat(context).hasSingleBean(CiphertextParser.class);
                    assertThat(context.getBean(CiphertextParser.class)).isSameAs(customParser);
                });
    }
}
