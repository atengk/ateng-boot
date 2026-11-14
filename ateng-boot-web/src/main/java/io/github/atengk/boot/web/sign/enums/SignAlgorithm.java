package io.github.atengk.boot.web.sign.enums;

/**
 * API 数据签名与验签算法枚举
 *
 * @author Ateng
 * @since 2026-10-16
 */
public enum SignAlgorithm {

    /**
     * 跟随全局配置（默认用于 {@link io.github.atengk.boot.web.sign.annotation.ApiSign} 注解，运行时按 ateng.boot.web.sign.algorithm 解析）
     */
    DEFAULT,

    /**
     * HMAC-SHA256 对称哈希算法（极速、高效，推荐内网微服务与移动端）
     */
    HMAC_SHA256,

    /**
     * RSA SHA256withRSA 非对称签名算法（公私钥分离，推荐开放平台对外生态）
     */
    RSA,

    /**
     * 国密 SM2 (GB/T 32918.2-2016) / SM3 非对称数字签名算法（政企与信创金融合规要求）
     */
    SM2
}
