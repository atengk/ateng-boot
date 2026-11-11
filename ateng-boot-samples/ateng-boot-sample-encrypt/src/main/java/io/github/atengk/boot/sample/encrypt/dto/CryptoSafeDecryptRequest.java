package io.github.atengk.boot.sample.encrypt.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 容错逃生舱安全解密请求
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CryptoSafeDecryptRequest {

    /**
     * 待解密文本（若损坏或无效将返回保底值）
     */
    @NotBlank(message = "待解密文本不能为空")
    private String ciphertext;

    /**
     * 解密失败或异常时的保底默认返回值
     */
    @NotBlank(message = "保底默认值 defaultValue 不能为空")
    private String defaultValue;
}
