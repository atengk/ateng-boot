package io.github.atengk.boot.sample.core.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * RFC 5869 HKDF 密钥派生、HMAC-SHA512、流式摘要与自包含 Packed AEAD 演示请求对象
 *
 * @author Ateng
 * @since 2026-11-27
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KdfAndStreamRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 输入主密钥材料 IKM（如租户根密钥或协商共享密钥）
     */
    @NotBlank(message = "主密钥材料不能为空")
    private String masterSecret;

    /**
     * 可选加密盐值（为空时内部按 RFC 5869 规范自动填充全零盐）
     */
    private String salt;

    /**
     * 应用上下文绑定信息 info（用于子密钥域隔离，如 tenant=t001:enc）
     */
    @NotBlank(message = "HKDF 上下文信息 info 不能为空")
    private String info;

    /**
     * 待计算摘要与认证加密的业务载荷
     */
    @NotBlank(message = "业务载荷不能为空")
    private String payload;

    /**
     * Packed AEAD 附加认证数据 AAD
     */
    @NotBlank(message = "附加认证数据 AAD 不能为空")
    private String aad;
}
