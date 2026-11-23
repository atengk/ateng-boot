package io.github.atengk.boot.sample.core.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 国密 SM4 密钥生成与带 AAD 认证 AES-GCM 对称加解密请求对象
 *
 * @author Ateng
 * @since 2026-11-13
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SecurityCryptoRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 待加密敏感明文
     */
    @NotBlank(message = "待加密明文不能为空")
    private String plaintext;

    /**
     * 附加认证数据（AAD，用于防上下文替换篡改）
     */
    @NotBlank(message = "附加认证数据 AAD 不能为空")
    private String aad;
}
