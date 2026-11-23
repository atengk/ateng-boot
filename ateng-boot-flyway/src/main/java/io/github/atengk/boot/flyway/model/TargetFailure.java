package io.github.atengk.boot.flyway.model;

import lombok.Builder;
import lombok.Getter;

import java.time.Duration;

/**
 * 批量迁移单目标失败明细条目（错误信息已统一脱敏）
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Getter
@Builder
public class TargetFailure {

    /**
     * 失败目标数据源标识
     */
    private final String targetKey;

    /**
     * Flyway 业务状态码（例如 62001）
     */
    private final Integer errorCode;

    /**
     * 已脱敏的失败原因描述
     */
    private final String errorMessage;

    /**
     * 执行耗时
     */
    private final Duration duration;
}
