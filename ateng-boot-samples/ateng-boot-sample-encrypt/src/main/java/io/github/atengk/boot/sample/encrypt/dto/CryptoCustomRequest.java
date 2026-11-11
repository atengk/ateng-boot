package io.github.atengk.boot.sample.encrypt.dto;

import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 指定命名密钥与算法的编程式加密请求
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CryptoCustomRequest {

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

    /**
     * 加密算法
     */
    @NotNull(message = "加密算法 algorithm 不能为空")
    private EncryptAlgorithm algorithm;
}
