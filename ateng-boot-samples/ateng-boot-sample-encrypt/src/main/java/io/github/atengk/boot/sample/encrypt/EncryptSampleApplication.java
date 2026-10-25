package io.github.atengk.boot.sample.encrypt;

import io.github.atengk.boot.sample.encrypt.config.SampleSecretProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 配置加解密与运行时安全防御全场景样例主应用
 *
 * @author Ateng
 * @since 2026-10-25
 */
@SpringBootApplication
@EnableConfigurationProperties(SampleSecretProperties.class)
public class EncryptSampleApplication {

    public static void main(String[] args) {
        SpringApplication.run(EncryptSampleApplication.class, args);
    }
}
