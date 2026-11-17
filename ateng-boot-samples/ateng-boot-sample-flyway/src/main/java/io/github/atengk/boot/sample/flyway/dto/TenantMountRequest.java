package io.github.atengk.boot.sample.flyway.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 运行期动态挂载 SaaS 物理租户数据源请求 DTO
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TenantMountRequest {

    /**
     * 租户唯一标识（如 1003）
     */
    @NotBlank(message = "租户标识 (tenantId) 不能为空")
    @Pattern(regexp = "^[a-zA-Z0-9_-]{1,32}$", message = "租户标识仅允许字母、数字、下划线与短横线")
    private String tenantId;

    /**
     * 租户名称（用于同步写入平台租户注册表，可选）
     */
    private String tenantName;

    /**
     * 租户数据库 JDBC URL（离线演示模式未传时自动构建独立 H2 内存分库）
     */
    private String jdbcUrl;

    /**
     * 数据库用户名（默认 sa）
     */
    private String username;

    /**
     * 数据库密码（默认空字符串）
     */
    private String password;

    /**
     * JDBC 驱动全限定类名（默认 org.h2.Driver）
     */
    private String driverClassName;
}
