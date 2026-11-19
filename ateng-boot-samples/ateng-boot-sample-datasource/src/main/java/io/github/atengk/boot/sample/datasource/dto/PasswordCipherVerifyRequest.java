package io.github.atengk.boot.sample.datasource.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 零明文凭据密文探测与瞬态解密校验请求传输对象
 *
 * @author Ateng
 * @since 2026-11-19
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PasswordCipherVerifyRequest {

    /**
     * 待校验的加密凭据字符串（支持 enc:sm4: 与 enc:aes: 前缀格式）
     */
    @NotBlank(message = "待校验密码凭据不能为空")
    private String encryptedPassword;
}
