package io.github.atengk.boot.sample.flyway.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 隔离式加密 DDL 凭据瞬态执行演练请求 DTO
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IsolatedCredentialDemoRequest {

    /**
     * 目标数据源标识（如 log_db 或 master）
     */
    @NotBlank(message = "目标数据源标识 (targetKey) 不能为空")
    private String targetKey;

    /**
     * 高权限 DDL 账号用户名（离线模式默认 sa）
     */
    @NotBlank(message = "DDL 账号用户名 (username) 不能为空")
    private String username;

    /**
     * 待 SM4 加密的 DDL 口令明文（离线 H2 演示可为空字符串）
     */
    private String plainPassword;
}
