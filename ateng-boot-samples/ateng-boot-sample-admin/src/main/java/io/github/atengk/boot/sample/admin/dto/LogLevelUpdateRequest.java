package io.github.atengk.boot.sample.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 在线动态调整 Logback 日志级别请求 DTO
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Data
public class LogLevelUpdateRequest {

    /**
     * 目标 Logger 名称或包路径
     */
    @NotBlank(message = "Logger 名称不能为空")
    private String loggerName;

    /**
     * 目标日志级别（TRACE/DEBUG/INFO/WARN/ERROR/OFF）
     */
    @NotBlank(message = "日志级别不能为空")
    @Pattern(
            regexp = "^(?i)(TRACE|DEBUG|INFO|WARN|ERROR|OFF)$",
            message = "日志级别仅支持 TRACE、DEBUG、INFO、WARN、ERROR 或 OFF"
    )
    private String level;
}
