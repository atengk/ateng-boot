package io.github.atengk.boot.sample.encrypt.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 指定命名密钥按环境默认算法加密请求
 *
 * @author Ateng
 * @since 2026-11-19
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CryptoKeyEncryptRequest {

    /**
     * 待加密明文字符串
     */
    @NotBlank(message = "待加密明文不能为空")
    private String plaintext;

    /**
     * 命名密钥标识（KeyId）
     */
    @NotBlank(message = "命名密钥标识 keyId 不能为空")
    private String keyId;
}
