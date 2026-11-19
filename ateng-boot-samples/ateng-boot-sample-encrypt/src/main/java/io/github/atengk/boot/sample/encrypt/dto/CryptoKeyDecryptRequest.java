package io.github.atengk.boot.sample.encrypt.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 指定命名密钥按环境默认算法解密请求
 *
 * @author Ateng
 * @since 2026-11-19
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CryptoKeyDecryptRequest {

    /**
     * 待解密密文字符串（支持密文标记或纯 Base64 裸密文）
     */
    @NotBlank(message = "待解密密文字符串不能为空")
    private String ciphertext;

    /**
     * 命名密钥标识（KeyId）
     */
    @NotBlank(message = "命名密钥标识 keyId 不能为空")
    private String keyId;
}
