package io.github.atengk.boot.sample.web.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.Map;

/**
 * 客户端演示用密文信封与加密/签名请求头组装结果
 *
 * @author Ateng
 * @since 2026-11-27
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CryptoPreparedEnvelopeVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 标准 HTTP 密文信封 JSON 字符串
     */
    private String encryptedBodyJson;

    /**
     * 需随请求一同发送的加密与可选签名请求头映射
     */
    private Map<String, String> headers;
}
