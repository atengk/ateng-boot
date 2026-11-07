package io.github.atengk.boot.sample.web.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/**
 * 实时计算与生成 API 签名请求头载荷 DTO
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PrepareSignHeadersDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * HTTP 请求方法（GET / POST 等，默认 POST）
     */
    @Builder.Default
    private String method = "POST";

    /**
     * 请求 URI（如 /sample/web/security/sign/orders）
     */
    private String uri;

    /**
     * 待加签的请求体字符串（POST 请求 JSON 等）
     */
    private String body;

    /**
     * URL Query 查询参数（GET 请求可选）
     */
    private Map<String, String> queryParams;

    /**
     * 签名算法（HMAC / RSA / SM2，不传则自动匹配系统当前配置）
     */
    private String algorithm;

    /**
     * 密钥（对称密钥或私钥，不传则使用系统当前配置的密钥）
     */
    private String secretOrPrivateKey;

    /**
     * 应用标识
     */
    private String appId;
}
