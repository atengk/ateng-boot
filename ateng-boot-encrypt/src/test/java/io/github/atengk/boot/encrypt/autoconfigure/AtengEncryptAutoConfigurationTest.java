package io.github.atengk.boot.encrypt.autoconfigure;

import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;
import io.github.atengk.boot.encrypt.decryptor.AbstractKmsConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.AesCbcConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.AesGcmConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.ConfigEncryptor;
import io.github.atengk.boot.encrypt.decryptor.RsaConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.Sm2ConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.Sm4ConfigDecryptor;
import io.github.atengk.boot.encrypt.detector.KeyDetector;
import io.github.atengk.boot.encrypt.exception.ConfigDecryptionException;
import io.github.atengk.boot.encrypt.guard.PlaintextIngressGuard;
import io.github.atengk.boot.encrypt.guard.SanitizingGuard;
import io.github.atengk.boot.encrypt.key.MultiKeyRegistry;
import io.github.atengk.boot.encrypt.parser.CiphertextParser;
import io.github.atengk.boot.encrypt.template.EncryptTemplate;
import io.github.atengk.boot.encrypt.wrapper.EncryptPropertySourceWrapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.PropertySource;

import javax.crypto.BadPaddingException;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
            assertThat(context).hasSingleBean(EncryptTemplate.class);
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
                    assertThat(context).doesNotHaveBean(EncryptTemplate.class);
                });
    }

    @Test
    @DisplayName("开发者定制接管: 用户自定义 Bean 时平滑覆盖框架默认 Bean")
    void testCustomBeanOverride() {
        CiphertextParser customParser = new CiphertextParser("MY_ENC[", "]");
        BeanFactoryPostProcessor customBridgePostProcessor = beanFactory -> {
        };
        SmartInitializingSingleton customBridgeInitializer = () -> {
        };
        contextRunner
                .withBean("customParser", CiphertextParser.class, () -> customParser)
                .withBean("encryptContainerBridgePostProcessor", BeanFactoryPostProcessor.class,
                        () -> customBridgePostProcessor)
                .withBean("encryptContainerBridgeInitializer", SmartInitializingSingleton.class,
                        () -> customBridgeInitializer)
                .run(context -> {
                    assertThat(context).hasSingleBean(CiphertextParser.class);
                    assertThat(context.getBean(CiphertextParser.class)).isSameAs(customParser);
                    assertThat(context.getBean("encryptContainerBridgePostProcessor"))
                            .isSameAs(customBridgePostProcessor);
                    assertThat(context.getBean("encryptContainerBridgeInitializer"))
                            .isSameAs(customBridgeInitializer);
                });
    }

    @Test
    @DisplayName("容器期 KMS 解密器桥接: 反向回填 Environment 包装器并对齐 EncryptRetryProperties 避免双重重试")
    void testContainerKmsDecryptorBridgedToEnvironmentWithRetryPolicy() {
        AtomicInteger transientAttempts = new AtomicInteger();
        AtomicInteger badPaddingAttempts = new AtomicInteger();
        AbstractKmsConfigDecryptor containerKmsBean = new AbstractKmsConfigDecryptor() {
            @Override
            protected String doDecrypt(String ciphertext, String password) throws Exception {
                if ("bad-padding-cipher".equals(ciphertext)) {
                    badPaddingAttempts.incrementAndGet();
                    throw new BadPaddingException("deterministic padding failure");
                }
                int attempt = transientAttempts.incrementAndGet();
                if (attempt < 4) {
                    throw new IOException("transient kms error #" + attempt);
                }
                return "kms-plain:" + ciphertext;
            }
        };

        contextRunner
                .withPropertyValues(
                        "ateng.boot.encrypt.password=ContainerMaster2026!",
                        "ateng.boot.encrypt.retry.max-attempts=4",
                        "ateng.boot.encrypt.retry.delay=5ms"
                )
                .withInitializer(ctx -> {
                    ConfigurableEnvironment env = ctx.getEnvironment();
                    Map<String, Object> sourceMap = new HashMap<>();
                    sourceMap.put("kms.transient.secret", "ENC(KMS@ok-payload)");
                    sourceMap.put("kms.badpadding.secret", "ENC(KMS@bad-padding-cipher)");
                    MapPropertySource rawSource = new MapPropertySource("kmsTestSource", sourceMap);
                    EncryptPropertySourceWrapper wrapper = new EncryptPropertySourceWrapper(
                            rawSource, new CiphertextParser(), new KeyDetector(null, Map.of()), null, EncryptAlgorithm.AES_GCM, true);
                    env.getPropertySources().addFirst(wrapper);
                })
                .withBean("customKmsDecryptor", AbstractKmsConfigDecryptor.class, () -> containerKmsBean)
                .run(context -> {
                    ConfigurableEnvironment env = context.getEnvironment();
                    assertThat(env.getProperty("kms.transient.secret")).isEqualTo("kms-plain:ok-payload");
                    assertThat(transientAttempts.get()).isEqualTo(4);

                    assertThatThrownBy(() -> env.getProperty("kms.badpadding.secret"))
                            .isInstanceOf(ConfigDecryptionException.class);
                    assertThat(badPaddingAttempts.get()).isEqualTo(1);

                    // 再次通过 EncryptTemplate 验证统一桥接，不应发生嵌套重试
                    transientAttempts.set(0);
                    EncryptTemplate encryptTemplate = context.getBean(EncryptTemplate.class);
                    assertThat(encryptTemplate.decrypt("ENC(KMS@template-payload)")).isEqualTo("kms-plain:template-payload");
                    assertThat(transientAttempts.get()).isEqualTo(4);
                });
    }

    @Test
    @DisplayName("热更新守卫联动: PlaintextIngressGuard 共享受管 MultiKeyRegistry 单例并保留运行期轮换密钥")
    void testPlaintextIngressGuardSharesManagedRegistrySingleton() {
        String rotatedSecret = "RuntimeRotatedKey@2026";
        String rotatedCipher = "ENC[runtime-rotated]("
                + ConfigEncryptor.encrypt("rotated-plain", rotatedSecret, EncryptAlgorithm.AES_GCM) + ")";

        contextRunner
                .withPropertyValues("ateng.boot.encrypt.password=ContainerMaster2026!")
                .run(context -> {
                    MultiKeyRegistry registry = context.getBean(MultiKeyRegistry.class);
                    PlaintextIngressGuard guard = context.getBean(PlaintextIngressGuard.class);
                    ConfigurableEnvironment env = context.getEnvironment();

                    registry.registerKey("runtime-rotated", rotatedSecret);

                    Map<String, Object> hotMap = new HashMap<>();
                    hotMap.put("app.rotated.secret", rotatedCipher);
                    env.getPropertySources().addFirst(new MapPropertySource("hotNacosProps", hotMap));

                    guard.onEnvironmentChange(Set.of("app.rotated.secret"), env);

                    assertThat(env.getProperty("app.rotated.secret")).isEqualTo("rotated-plain");
                    PropertySource<?> wrapped = env.getPropertySources().get("hotNacosProps");
                    assertThat(wrapped).isInstanceOf(EncryptPropertySourceWrapper.class);
                    assertThat(((EncryptPropertySourceWrapper) wrapped).getKeyRegistry()).isSameAs(registry);
                });
    }
}
