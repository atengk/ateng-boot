package io.github.atengk.boot.sample.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.Map;

/**
 * 演练实例登记请求 DTO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
public class InstanceMockRegisterRequest {

    /**
     * 演练实例唯一标识
     */
    @NotBlank(message = "实例 ID 不能为空")
    private String instanceId;

    /**
     * 业务微服务名称（如 payment-gateway-service）
     */
    @NotBlank(message = "服务名称不能为空")
    private String serviceName;

    /**
     * 微服务访问根路径
     */
    @NotBlank(message = "serviceUrl 不能为空")
    @Pattern(regexp = "^(?i)https?://\\S+$", message = "serviceUrl 必须为合法的 http 或 https URL")
    private String serviceUrl;

    /**
     * 健康检查地址（可选，默认拼接 /actuator/health）
     */
    @Pattern(regexp = "^(?i)(|https?://\\S+)$", message = "healthUrl 必须为合法的 http 或 https URL")
    private String healthUrl;

    /**
     * 应用版本号（可选）
     */
    private String version;

    /**
     * 部署可用区标签（可选）
     */
    private String zone;

    /**
     * 初始实例状态（默认 UP）
     */
    @Pattern(
            regexp = "^(?i)(UP|DOWN|OFFLINE|UNKNOWN)$",
            message = "状态仅支持 UP、DOWN、OFFLINE 或 UNKNOWN"
    )
    private String status;

    /**
     * 扩展非敏感元数据（可选）
     */
    private Map<String, String> metadata;
}
