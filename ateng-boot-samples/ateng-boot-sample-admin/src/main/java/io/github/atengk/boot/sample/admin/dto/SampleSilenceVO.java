package io.github.atengk.boot.sample.admin.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * 维护静默规则响应 VO
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Data
@Builder
public class SampleSilenceVO {

    private String id;
    private String pattern;
    private String creator;
    private String reason;
    private Instant createdAt;
    private Instant expiresAt;
}
