package io.github.atengk.boot.encrypt.autoconfigure;

import io.github.atengk.boot.encrypt.config.EncryptProperties;
import io.github.atengk.boot.encrypt.decryptor.AesCbcConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.AesGcmConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.RsaConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.Sm2ConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.Sm4ConfigDecryptor;
import io.github.atengk.boot.encrypt.guard.PlaintextIngressGuard;
import io.github.atengk.boot.encrypt.guard.SanitizingGuard;
import io.github.atengk.boot.encrypt.parser.CiphertextParser;
import org.springframework.boot.actuate.endpoint.SanitizingFunction;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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

    @Bean
    @ConditionalOnMissingBean
    public CiphertextParser ciphertextParser(EncryptProperties properties) {
        return new CiphertextParser(properties.getPrefix(), properties.getSuffix());
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
