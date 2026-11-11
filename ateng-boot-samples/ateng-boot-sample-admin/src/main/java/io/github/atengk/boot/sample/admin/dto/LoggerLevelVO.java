package io.github.atengk.boot.sample.admin.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 运行期 Logback 日志级别响应 VO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
@Builder
public class LoggerLevelVO {

    /**
     * Logger 名称
     */
    private String loggerName;

    /**
     * 显式配置的日志级别（未单独配置时为 null）
     */
    private String configuredLevel;

    /**
     * 当前实际生效的日志级别
     */
    private String effectiveLevel;
}
