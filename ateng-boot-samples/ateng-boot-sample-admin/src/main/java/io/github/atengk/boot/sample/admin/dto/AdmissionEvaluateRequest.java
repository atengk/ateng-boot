package io.github.atengk.boot.sample.admin.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 实例注册准入预检请求 DTO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
public class AdmissionEvaluateRequest {

    /**
     * 待注册微服务名称
     */
    @NotBlank(message = "服务名称不能为空")
    private String serviceName;

    /**
     * 服务主访问地址
     */
    @NotBlank(message = "serviceUrl 不能为空")
    private String serviceUrl;

    /**
     * Actuator 管理端点根路径
     */
    private String managementUrl;

    /**
     * 健康检查端点地址
     */
    private String healthUrl;

    /**
     * 模拟上报的 X-Admin-Client-Secret 集群准入密钥（可选，不传则沿用当前请求头）
     */
    private String clientSecret;
}
