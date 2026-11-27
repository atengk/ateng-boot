package io.github.atengk.boot.web.crypto.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * HTTP 传输层密文信封载体模型
 * 对应标准 JSON 载荷格式 {@code {"cipherText":"<Base64>"}}，与请求/响应头加密元数据协同传输
 *
 * @author Ateng
 * @since 2026-11-27
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ApiEncryptedPayload implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * AEAD（AES-256-GCM / SM4-GCM）加密输出的 Base64 密文及尾部认证 Tag
     */
    private String cipherText;
}
