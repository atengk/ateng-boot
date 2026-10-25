package io.github.atengk.boot.encrypt.config;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * 支持的加密算法类型枚举
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Getter
@RequiredArgsConstructor
public enum EncryptAlgorithm {

    /**
     * 现代带认证加密 AES-GCM (生产默认推荐)
     * 采用 12 字节随机 IV 前置，自带 128 位 GCM 认证 Tag
     */
    AES_GCM("AES-GCM", "AES/GCM/NoPadding"),

    /**
     * 传统对称加密 AES-CBC
     * 采用 16 字节随机 IV 前置
     */
    AES_CBC("AES-CBC", "AES/CBC/PKCS5Padding"),

    /**
     * 纯 JDK 原生国密 SM4-CBC 模式 (GB/T 32907-2016)
     * 采用 16 字节随机 IV 前置，免 Bouncy Castle 外部依赖
     */
    SM4_CBC("SM4-CBC", "SM4/CBC/PKCS7Padding"),

    /**
     * 纯 JDK 原生国密 SM4-ECB 模式 (GB/T 32907-2016)
     * 免 IV，免 Bouncy Castle 外部依赖
     */
    SM4_ECB("SM4-ECB", "SM4/ECB/PKCS7Padding"),

    /**
     * 非对称加密 RSA (2048/4096 位)
     * 公钥加密，私钥解密
     */
    RSA("RSA", "RSA"),

    /**
     * 纯原生国密非对称 SM2
     * 国密公钥加密，私钥解密
     */
    SM2("SM2", "SM2");

    private final String code;
    private final String transformation;

    /**
     * 根据编码解析算法枚举（忽略大小写与中划线/下划线差异）
     *
     * @param code 算法编码（如 AES-GCM, aes_gcm, SM4）
     * @return 对应的枚举对象；若无匹配则返回 null
     */
    public static EncryptAlgorithm fromCode(String code) {
        if (code == null || code.trim().isEmpty()) {
            return null;
        }
        String normalized = code.trim().replace("-", "_").toUpperCase();
        for (EncryptAlgorithm algo : values()) {
            if (algo.name().equalsIgnoreCase(normalized) || algo.code.equalsIgnoreCase(code.trim())) {
                return algo;
            }
        }
        if ("SM4".equalsIgnoreCase(normalized)) {
            return SM4_CBC;
        }
        if ("AES".equalsIgnoreCase(normalized)) {
            return AES_GCM;
        }
        return null;
    }
}
