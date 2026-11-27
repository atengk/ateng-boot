package io.github.atengk.boot.web.crypto.enums;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import org.springframework.util.StringUtils;

import java.util.Locale;

/**
 * API 传输层混合加解密套件枚举
 *
 * @author Ateng
 * @since 2026-11-27
 */
public enum CryptoAlgorithm {

    /**
     * 跟随全局配置（用于注解默认值，运行时按 ateng.boot.web.crypto.algorithm 解析）
     */
    DEFAULT,

    /**
     * RSA-OAEP-SHA256 封装随机 256-bit 会话密钥 + AES-256-GCM 认证加密
     */
    RSA_AES_GCM,

    /**
     * 国密 SM2 非对称封装随机 128-bit 会话密钥 + 纯 JDK 原生 SM4-GCM 认证加密
     */
    SM2_SM4_GCM;

    /**
     * 解析请求头传入的加密算法标识
     *
     * @param headerValue 请求头算法名称（为空时返回 null）
     * @return 解析后的具体算法枚举；空值返回 null
     */
    public static CryptoAlgorithm fromHeader(String headerValue) {
        if (!StringUtils.hasText(headerValue)) {
            return null;
        }
        String normalized = headerValue.trim().toUpperCase(Locale.ROOT);
        if ("RSA_AES_GCM".equals(normalized) || "RSA-OAEP-SHA256+AES-256-GCM".equals(normalized)) {
            return RSA_AES_GCM;
        }
        if ("SM2_SM4_GCM".equals(normalized) || "SM2+SM4-GCM".equals(normalized)) {
            return SM2_SM4_GCM;
        }
        throw new BusinessException(CommonResultCode.BAD_REQUEST, "不支持的加密算法标识: " + headerValue);
    }
}
