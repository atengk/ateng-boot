package io.github.atengk.boot.sample.encrypt.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 业务敏感机密配置属性类
 * 用于承载 application.yml 中配置的各类型密文字段，验证启动时透明解密绑定
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Data
@ConfigurationProperties(prefix = "sample.secret")
public class SampleSecretProperties {

    /**
     * 生产数据库连接密码（AES-GCM 默认算法加密）
     */
    private String databasePassword;

    /**
     * 生产 Redis 缓存密码（多 Key 别名 ENC[redis-cluster](...) 加密）
     */
    private String redisPassword;

    /**
     * 第三方支付 API 令牌（国密 SM4-CBC 模式加密）
     */
    private String thirdPartyToken;
}
