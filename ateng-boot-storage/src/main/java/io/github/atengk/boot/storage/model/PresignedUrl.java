package io.github.atengk.boot.storage.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;

/**
 * 预签名外链模型
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PresignedUrl implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 可直接访问的完整预签名 URL
     */
    private String url;

    /**
     * 外链绝对过期时间戳
     */
    private Instant expiration;

    /**
     * HTTP 请求方法（如 GET、PUT）
     */
    private String httpMethod;

    /**
     * 客户端发起直传请求时必须携带的 HTTP 报头（如 Content-Type 等，无特殊要求时为空 Map）
     */
    @Builder.Default
    private Map<String, String> headers = Collections.emptyMap();
}
