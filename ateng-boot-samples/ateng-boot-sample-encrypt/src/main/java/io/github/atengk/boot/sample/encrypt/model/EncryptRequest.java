package io.github.atengk.boot.sample.encrypt.model;

import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 编程式加密请求 DTO
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Data
public class EncryptRequest {

    /**
     * 待加密明文
     */
    @NotBlank(message = "待加密明文不能为空")
    private String plaintext;

    /**
     * 加密密钥口令（或公钥）
     */
    @NotBlank(message = "加密密钥不能为空")
    private String password;

    /**
     * 加密算法类型，默认为 AES-GCM
     */
    private EncryptAlgorithm algorithm = EncryptAlgorithm.AES_GCM;

    /**
     * 可选多 Key 别名
     */
    private String keyId;
}
