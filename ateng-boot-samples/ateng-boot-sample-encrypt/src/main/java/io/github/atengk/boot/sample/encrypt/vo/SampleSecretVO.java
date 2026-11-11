package io.github.atengk.boot.sample.encrypt.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 业务敏感机密解密明文展示对象
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SampleSecretVO {

    /**
     * 数据库连接明文密码（AES-GCM 解密）
     */
    private String databasePassword;

    /**
     * Redis 访问明文密码（多 Key 命名路由解密）
     */
    private String redisPassword;

    /**
     * 第三方支付 API 令牌（国密 SM4-CBC 解密）
     */
    private String thirdPartyToken;

    /**
     * 当前环境默认加密算法
     */
    private String activeAlgorithm;

    /**
     * 解密验证状态
     */
    private String decryptionStatus;
}
