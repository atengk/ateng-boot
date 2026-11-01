package io.github.atengk.boot.sample.encrypt.service;

import io.github.atengk.boot.sample.encrypt.config.SampleSecretProperties;
import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 配置透明懒解密场景服务
 * 演示通过 @ConfigurationProperties 强类型属性对象以及 @Value 属性注入方式透明获取解密后的真实明文配置
 *
 * @author Ateng
 * @since 2026-11-01
 */
@Service
@Getter
public class ConfigDecryptionScenarioService {

    private final SampleSecretProperties secretProperties;

    @Value("${sample.secret.database-password}")
    private String databasePasswordFromValue;

    @Value("${sample.secret.redis-password}")
    private String redisPasswordFromValue;

    @Value("${sample.secret.third-party-token}")
    private String thirdPartyTokenFromValue;

    public ConfigDecryptionScenarioService(SampleSecretProperties secretProperties) {
        this.secretProperties = secretProperties;
    }

    /**
     * 获取通过 @ConfigurationProperties 解密绑定的数据库连接密码
     *
     * @return 真实数据库明文密码
     */
    public String getDatabasePasswordFromProperties() {
        return secretProperties.getDatabasePassword();
    }

    /**
     * 获取通过 @ConfigurationProperties 经多 Key [redis-cluster] 路由解密绑定的缓存密码
     *
     * @return 真实 Redis 明文密码
     */
    public String getRedisPasswordFromProperties() {
        return secretProperties.getRedisPassword();
    }

    /**
     * 获取通过 @ConfigurationProperties 经国密 SM4-CBC 解密绑定的第三方安全令牌
     *
     * @return 真实第三方明文 Token
     */
    public String getThirdPartyTokenFromProperties() {
        return secretProperties.getThirdPartyToken();
    }

    /**
     * 校验 @ConfigurationProperties 与 @Value 注入的明文是否一致自洽
     *
     * @return 若完全一致且非空则返回 true
     */
    public boolean verifyDecryptionConsistency() {
        return databasePasswordFromValue != null
                && databasePasswordFromValue.equals(secretProperties.getDatabasePassword())
                && redisPasswordFromValue != null
                && redisPasswordFromValue.equals(secretProperties.getRedisPassword())
                && thirdPartyTokenFromValue != null
                && thirdPartyTokenFromValue.equals(secretProperties.getThirdPartyToken());
    }
}
