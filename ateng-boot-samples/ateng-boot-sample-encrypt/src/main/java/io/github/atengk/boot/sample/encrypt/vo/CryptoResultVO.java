package io.github.atengk.boot.sample.encrypt.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 编程式加解密结果展示对象
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CryptoResultVO {

    /**
     * 真实明文
     */
    private String plaintext;

    /**
     * 密文标记（形如 ENC(...)）
     */
    private String token;

    /**
     * 纯 Base64 裸密文字符串
     */
    private String rawCiphertext;

    /**
     * 命名密钥标识（若有）
     */
    private String keyId;

    /**
     * 算法名称
     */
    private String algorithm;
}
