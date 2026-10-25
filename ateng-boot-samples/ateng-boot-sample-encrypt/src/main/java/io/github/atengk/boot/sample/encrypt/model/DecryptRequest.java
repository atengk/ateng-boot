package io.github.atengk.boot.sample.encrypt.model;

import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 编程式解密请求 DTO
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Data
public class DecryptRequest {

    /**
     * 待解密密文（支持 ENC(...) 标记或纯 Base64）
     */
    @NotBlank(message = "待解密密文不能为空")
    private String ciphertext;

    /**
     * 解密密钥口令（或私钥）
     */
    @NotBlank(message = "解密密钥不能为空")
    private String password;

    /**
     * 加密算法类型，默认为 AES-GCM
     */
    private EncryptAlgorithm algorithm = EncryptAlgorithm.AES_GCM;
}
