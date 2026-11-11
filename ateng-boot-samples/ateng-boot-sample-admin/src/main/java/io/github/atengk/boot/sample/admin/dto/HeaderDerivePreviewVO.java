package io.github.atengk.boot.sample.admin.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 出站 Actuator 凭据级联推导掩码预览响应 VO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
public class HeaderDerivePreviewVO {

    /**
     * 目标微服务名称
     */
    private String serviceName;

    /**
     * 命中的认证推导层级（BASIC/BEARER/ACTUATOR_TOKEN/GLOBAL_FALLBACK/NONE）
     */
    private String authMode;

    /**
     * 推导挂载的 HTTP 请求头名称
     */
    private String headerName;

    /**
     * 掩码脱敏后的请求头值（严禁明文回传）
     */
    private String maskedHeaderValue;

    /**
     * 是否存在有效出站认证请求头
     */
    private boolean headerPresent;
}
