package io.github.atengk.boot.sample.web.dto;

import io.github.atengk.boot.web.crypto.enums.CryptoAlgorithm;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 传输层加密信封辅助生成请求参数
 *
 * @author Ateng
 * @since 2026-11-27
 */
@Data
public class CryptoPrepareEnvelopeDTO {

    /**
     * 待加密原始明文 JSON 字符串
     */
    @NotBlank(message = "待加密明文载荷不能为空")
    private String plainJson;

    /**
     * 目标混合加密套件（默认 RSA_AES_GCM）
     */
    private CryptoAlgorithm algorithm = CryptoAlgorithm.RSA_AES_GCM;

    /**
     * 密钥版本标识（默认 v1）
     */
    private String keyId = "v1";

    /**
     * 目标接口请求路径（当 withSign=true 时必填，例如 /sample/web/crypto/mixed/transfer）
     */
    private String requestUri;

    /**
     * 是否同步基于最终密文信封生成 Encrypt-then-Sign 签名请求头
     */
    private boolean withSign;
}
