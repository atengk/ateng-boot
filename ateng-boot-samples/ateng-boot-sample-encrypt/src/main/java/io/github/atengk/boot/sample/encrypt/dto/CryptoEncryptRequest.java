package io.github.atengk.boot.sample.encrypt.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 编程式默认加密请求
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CryptoEncryptRequest {

    /**
     * 待加密明文字符串
     */
    @NotBlank(message = "待加密明文不能为空")
    private String plaintext;
}
