package io.github.atengk.boot.encrypt.autoconfigure;

import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;
import io.github.atengk.boot.encrypt.config.EncryptProperties;
import io.github.atengk.boot.encrypt.decryptor.AesCbcConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.AesGcmConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.ConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.RsaConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.Sm2ConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.Sm4ConfigDecryptor;
import io.github.atengk.boot.encrypt.event.KeyRotatedEvent;
import io.github.atengk.boot.encrypt.guard.PlaintextIngressGuard;
import io.github.atengk.boot.encrypt.guard.SanitizingGuard;
import io.github.atengk.boot.encrypt.key.MultiKeyRegistry;
import io.github.atengk.boot.encrypt.parser.CiphertextParser;
import io.github.atengk.boot.encrypt.template.EncryptTemplate;
import io.github.atengk.boot.encrypt.wrapper.EncryptPropertySourceWrapper;
import org.springframework.boot.actuate.endpoint.SanitizingFunction;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;

import java.util.List;

/**
 * 配置加解密核心自动装配类
 *
 * @author Ateng
 * @since 2026-10-25
 */
@AutoConfiguration
@EnableConfigurationProperties(EncryptProperties.class)
@ConditionalOnProperty(prefix = "ateng.boot.encrypt", name = "enabled", havingValue = "true", matchIfMissing = true)
public class AtengEncryptAutoConfiguration {

    private final ConfigurableEnvironment environment;

    public AtengEncryptAutoConfiguration(ConfigurableEnvironment environment) {
        this.environment = environment;
    }

    @Bean
    @ConditionalOnMissingBean
    public MultiKeyRegistry multiKeyRegistry(EncryptProperties properties) {
        if (environment != null) {
            for (PropertySource<?> source : environment.getPropertySources()) {
                if (source instanceof EncryptPropertySourceWrapper wrapper && wrapper.getKeyDetector() != null) {
                    MultiKeyRegistry registry = wrapper.getKeyDetector().getRegistry();
                    if (registry != null) {
                        if (properties.getKeys() != null && !properties.getKeys().isEmpty()) {
                            properties.getKeys().forEach(registry::registerKey);
                        }
                        if (properties.getPassword() != null && !properties.getPassword().trim().isEmpty()) {
                            registry.setDefaultKey(properties.getPassword().trim());
                        }
                        return registry;
                    }
                }
            }
        }
        return MultiKeyRegistry.fromProperties(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public EncryptAlgorithm defaultEncryptAlgorithm(EncryptProperties properties) {
        return properties.getAlgorithm() != null ? properties.getAlgorithm() : EncryptAlgorithm.AES_GCM;
    }

    @Bean
    @ConditionalOnMissingBean
    public CiphertextParser ciphertextParser(EncryptProperties properties) {
        return new CiphertextParser(properties.getPrefix(), properties.getSuffix(), properties.isCompatibleCipherPrefix());
    }

    @Bean
    @ConditionalOnMissingBean
    public AesGcmConfigDecryptor aesGcmConfigDecryptor() {
        return new AesGcmConfigDecryptor();
    }

    @Bean
    @ConditionalOnMissingBean
    public AesCbcConfigDecryptor aesCbcConfigDecryptor() {
        return new AesCbcConfigDecryptor();
    }

    @Bean
    @ConditionalOnMissingBean
    public Sm4ConfigDecryptor sm4ConfigDecryptor() {
        return new Sm4ConfigDecryptor();
    }

    @Bean
    @ConditionalOnMissingBean
    public RsaConfigDecryptor rsaConfigDecryptor() {
        return new RsaConfigDecryptor();
    }

    @Bean
    @ConditionalOnMissingBean
    public Sm2ConfigDecryptor sm2ConfigDecryptor() {
        return new Sm2ConfigDecryptor();
    }

    @Bean
    @ConditionalOnMissingBean
    public PlaintextIngressGuard plaintextIngressGuard(EncryptProperties properties, CiphertextParser parser) {
        return new PlaintextIngressGuard(properties, parser);
    }

    @Bean
    @ConditionalOnMissingBean
    public EncryptTemplate encryptTemplate(EncryptProperties properties,
                                           EncryptAlgorithm defaultEncryptAlgorithm,
                                           MultiKeyRegistry multiKeyRegistry,
                                           CiphertextParser ciphertextParser,
                                           List<ConfigDecryptor> decryptors) {
        return new EncryptTemplate(properties, defaultEncryptAlgorithm, multiKeyRegistry, ciphertextParser, decryptors);
    }

    @EventListener
    public void onKeyRotated(KeyRotatedEvent event) {
        if (environment != null) {
            for (PropertySource<?> source : environment.getPropertySources()) {
                if (source instanceof EncryptPropertySourceWrapper wrapper) {
                    wrapper.clearCache();
                }
            }
        }
    }

    /**
     * Actuator 运行时端点脱敏守卫条件自动配置
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(SanitizingFunction.class)
    public static class EncryptActuatorConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "encryptSanitizingGuard")
        public SanitizingGuard encryptSanitizingGuard(EncryptProperties properties, CiphertextParser parser) {
            return new SanitizingGuard(properties, parser);
        }
    }
}
