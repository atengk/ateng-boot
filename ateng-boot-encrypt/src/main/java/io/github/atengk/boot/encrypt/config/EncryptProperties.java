package io.github.atengk.boot.encrypt.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 配置加解密核心属性类
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Data
@ConfigurationProperties(prefix = "ateng.boot.encrypt")
public class EncryptProperties {

    /**
     * 是否启用配置加密解密功能，默认为 true
     */
    private boolean enabled = true;

    /**
     * 全局默认解密主密钥（严禁直接写在被解密的本地 application.yml 中，推荐经由环境变量或启动参数注入）
     */
    private String password;

    /**
     * 全局默认加解密算法，默认为 AES-GCM
     */
    private EncryptAlgorithm algorithm = EncryptAlgorithm.AES_GCM;

    /**
     * 密文标记前缀，默认为 "ENC("
     */
    private String prefix = "ENC(";

    /**
     * 密文标记后缀，默认为 ")"
     */
    private String suffix = ")";

    /**
     * 解密失败时是否立即中断应用启动（快速失败），默认为 true
     */
    private boolean failOnError = true;

    /**
     * 生产环境是否强制要求敏感字段（password/secret 等）必须加密，默认为 false
     */
    private boolean enforceEncrypted = false;

    /**
     * 生产环境强制加密白名单属性键集合（配置项 ateng.boot.encrypt.ignored-keys）
     * 显式白名单内的属性跳过强制加密校验
     */
    private Set<String> ignoredKeys = new HashSet<>();

    /**
     * 是否兼容 Spring Cloud Config {cipher}... 格式密文，默认为 false
     */
    private boolean compatibleCipherPrefix = false;

    /**
     * 敏感属性匹配正则模式，用于生产环境明文强制检测
     */
    private String sensitiveKeyPattern = ".*password.*|.*secret.*|.*token.*|.*private-key.*";

    /**
     * 多密钥映射表（KeyId -> Password/PrivateKey），用于多 Key 轮换与分域隔离
     */
    private Map<String, String> keys = new LinkedHashMap<>();

    /**
     * 外部 KMS / 硬件加密机弹性退避重试配置
     */
    @NestedConfigurationProperty
    private EncryptRetryProperties retry = new EncryptRetryProperties();
}
