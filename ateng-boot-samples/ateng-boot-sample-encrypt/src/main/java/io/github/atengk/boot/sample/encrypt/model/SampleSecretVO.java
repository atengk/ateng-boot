package io.github.atengk.boot.sample.encrypt.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 敏感机密配置自验展示 VO
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SampleSecretVO {

    /**
     * 默认 AES-GCM 解密后的数据库密码
     */
    private String databasePassword;

    /**
     * 多 Key 别名路由解密后的 Redis 密码
     */
    private String redisPassword;

    /**
     * 国密 SM4-CBC 解密后的第三方令牌
     */
    private String thirdPartyToken;
}
