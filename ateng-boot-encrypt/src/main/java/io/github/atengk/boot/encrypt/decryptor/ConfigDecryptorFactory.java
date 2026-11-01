package io.github.atengk.boot.encrypt.decryptor;

import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;
import io.github.atengk.boot.encrypt.config.EncryptRetryProperties;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.ServiceLoader;

/**
 * 解密器发现与默认装配工厂
 * 统一收敛解密器 SPI 发现、默认原生算法装配以及重试装饰器挂载逻辑
 *
 * @author Ateng
 * @since 2026-11-01
 */
public final class ConfigDecryptorFactory {

    private ConfigDecryptorFactory() {
    }

    /**
     * 构建解密器完整列表（包含外部自定义、SPI 发现及 JDK 原生默认实现）
     *
     * @param customDecryptors 自定义解密器集合（可为 null）
     * @param retryProperties  外部重试配置（可为 null）
     * @return 不可变解密器列表
     */
    public static List<ConfigDecryptor> createDefaultDecryptors(List<ConfigDecryptor> customDecryptors,
                                                               EncryptRetryProperties retryProperties) {
        List<ConfigDecryptor> list = new ArrayList<>();
        if (customDecryptors != null) {
            list.addAll(customDecryptors);
        }

        // 1. 通过 Java ServiceLoader 发现外部 SPI 扩展解密器（如外部云 KMS、硬件加密机扩展）
        ServiceLoader<ConfigDecryptor> serviceLoader = ServiceLoader.load(ConfigDecryptor.class);
        for (ConfigDecryptor spiDecryptor : serviceLoader) {
            if (list.stream().noneMatch(d -> d.getClass().equals(spiDecryptor.getClass()))) {
                if (retryProperties != null && spiDecryptor.supports(EncryptAlgorithm.KMS)
                        && !(spiDecryptor instanceof RetryingConfigDecryptor)) {
                    list.add(new RetryingConfigDecryptor(spiDecryptor, retryProperties));
                } else {
                    list.add(spiDecryptor);
                }
            }
        }

        // 2. 注册默认纯 JDK 原生解密器
        if (list.stream().noneMatch(d -> d.supports(EncryptAlgorithm.AES_GCM))) {
            list.add(new AesGcmConfigDecryptor());
        }
        if (list.stream().noneMatch(d -> d.supports(EncryptAlgorithm.AES_CBC))) {
            list.add(new AesCbcConfigDecryptor());
        }
        if (list.stream().noneMatch(d -> d.supports(EncryptAlgorithm.SM4_CBC))) {
            list.add(new Sm4ConfigDecryptor());
        }
        if (list.stream().noneMatch(d -> d.supports(EncryptAlgorithm.RSA))) {
            list.add(new RsaConfigDecryptor());
        }
        if (list.stream().noneMatch(d -> d.supports(EncryptAlgorithm.SM2))) {
            list.add(new Sm2ConfigDecryptor());
        }

        return Collections.unmodifiableList(list);
    }

    public static List<ConfigDecryptor> createDefaultDecryptors(List<ConfigDecryptor> customDecryptors) {
        return createDefaultDecryptors(customDecryptors, null);
    }
}
