package io.github.atengk.boot.sample.admin.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * 安全审计轨迹脱敏视图 VO
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Data
@Builder
public class SampleAuditRecordVO {

    private String id;
    private Instant timestamp;
    private String action;
    private String actor;
    private String sourceIp;
    private String target;
    private String method;
    private String path;
    private String outcome;
    private String detail;
}
