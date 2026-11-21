package io.github.atengk.boot.sample.admin.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 控制台高危端点守卫预检请求 DTO
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Data
public class EndpointGuardEvaluateRequest {

    /**
     * HTTP 请求方法（GET / POST / PUT / DELETE / PATCH）
     */
    @NotBlank(message = "HTTP 方法不能为空")
    private String method;

    /**
     * 目标 Actuator 代理 URI 路径（如 /admin/instances/demo/actuator/heapdump）
     */
    @NotBlank(message = "目标端点 URI 不能为空")
    private String endpointUri;

    /**
     * 模拟发起请求的控制台角色（VIEWER / OPERATOR / ADMIN，默认 ADMIN）
     */
    private String role;
}
