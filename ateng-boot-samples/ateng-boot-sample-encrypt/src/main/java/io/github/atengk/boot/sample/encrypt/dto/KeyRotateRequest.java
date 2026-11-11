package io.github.atengk.boot.sample.encrypt.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 命名密钥动态注册与平滑轮换请求
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KeyRotateRequest {

    /**
     * 密钥别名标识（如 payment-shard-key, redis-cluster）
     */
    @NotBlank(message = "密钥别名 keyId 不能为空")
    private String keyId;

    /**
     * 新密钥明文字符串
     */
    @NotBlank(message = "新密钥明文 newPassword 不能为空")
    private String newPassword;
}
