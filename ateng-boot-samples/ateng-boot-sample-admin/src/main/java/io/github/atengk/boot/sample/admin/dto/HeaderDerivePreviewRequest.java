package io.github.atengk.boot.sample.admin.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

/**
 * 出站 Actuator 凭据级联推导预览请求 DTO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
public class HeaderDerivePreviewRequest {

    /**
     * 微服务名称
     */
    @NotBlank(message = "服务名称不能为空")
    private String serviceName;

    /**
     * 实例注册元数据键值对（如 user.name/user.password、bearer-token、actuator-token）
     */
    private Map<String, String> metadata;
}
