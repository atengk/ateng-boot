package io.github.atengk.boot.sample.admin.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 实例注册准入预检响应 VO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
public class AdmissionEvaluateVO {

    /**
     * 是否允许准入放行
     */
    private boolean allowed;

    /**
     * 评估返回的 HTTP 状态码
     */
    private int httpStatus;

    /**
     * 准入拒绝原因（放行时为 null）
     */
    private String reason;

    /**
     * 被评估微服务名称
     */
    private String serviceName;

    /**
     * 实际执行的准入策略实现类名称
     */
    private String policyClass;
}
