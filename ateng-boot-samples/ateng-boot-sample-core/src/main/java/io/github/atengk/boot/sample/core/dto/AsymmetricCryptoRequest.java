package io.github.atengk.boot.sample.core.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 纯 JDK 原生 RSA-OAEP / RSASSA-PSS 与国密 SM2 非对称加解密及签验演示请求对象
 *
 * @author Ateng
 * @since 2026-11-27
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AsymmetricCryptoRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 待非对称加密传输的业务敏感明文（如会话密钥或商户凭证）
     */
    @NotBlank(message = "待加密业务明文不能为空")
    private String plaintext;

    /**
     * 待非对称数字签名的规范化报文载荷
     */
    @NotBlank(message = "待签名报文载荷不能为空")
    private String signPayload;

    /**
     * 可选 SM2 签验用户标识（为空时使用国标默认 1234567812345678）
     */
    private String sm2UserId;
}
