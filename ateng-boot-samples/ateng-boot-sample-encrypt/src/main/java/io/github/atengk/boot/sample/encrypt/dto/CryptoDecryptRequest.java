package io.github.atengk.boot.sample.encrypt.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 编程式解密请求
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CryptoDecryptRequest {

    /**
     * 待解密密文字符串（支持 ENC(...) 标记或裸密文）
     */
    @NotBlank(message = "待解密密文字符串不能为空")
    private String ciphertext;
}
