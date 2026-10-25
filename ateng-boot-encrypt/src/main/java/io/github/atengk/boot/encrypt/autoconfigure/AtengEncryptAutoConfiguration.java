package io.github.atengk.boot.encrypt.autoconfigure;

import io.github.atengk.boot.encrypt.config.EncryptProperties;
import io.github.atengk.boot.encrypt.decryptor.AesCbcConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.AesGcmConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.Sm4ConfigDecryptor;
import io.github.atengk.boot.encrypt.parser.CiphertextParser;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

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
}
