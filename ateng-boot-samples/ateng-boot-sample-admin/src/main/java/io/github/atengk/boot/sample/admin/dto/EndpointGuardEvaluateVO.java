package io.github.atengk.boot.sample.admin.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * 控制台高危端点守卫预检结果 VO
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Data
@Builder
public class EndpointGuardEvaluateVO {

    private String method;
    private String endpointUri;
    private String role;
    private boolean blocked;
    private boolean writeOperationsEnabled;
    private List<String> blockedEndpoints;
    private String reason;
}
