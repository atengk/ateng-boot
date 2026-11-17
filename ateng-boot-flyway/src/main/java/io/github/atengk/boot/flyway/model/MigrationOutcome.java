package io.github.atengk.boot.flyway.model;

import io.github.atengk.boot.flyway.enums.FlywayExecutionMode;
import lombok.Builder;
import lombok.Getter;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * 单个目标数据源迁移执行结果视图对象
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Getter
@Builder
public class MigrationOutcome {

    /**
     * 目标数据源标识
     */
    private final String targetKey;

    /**
     * 执行模式
     */
    private final FlywayExecutionMode mode;

    /**
     * 是否执行成功
     */
    private final boolean success;

    /**
     * 是否被跳过（如 DISABLED 模式）
     */
    private final boolean skipped;

    /**
     * 本次执行的迁移脚本数
     */
    private final int migrationsExecuted;

    /**
     * 迁移前版本
     */
    private final String initialSchemaVersion;

    /**
     * 迁移后目标版本
     */
    private final String targetSchemaVersion;

    /**
     * 目标数据库类型描述
     */
    private final String database;

    /**
     * 执行耗时
     */
    private final Duration duration;

    /**
     * 执行完成时间
     */
    private final Instant executedAt;

    /**
     * 警告消息列表
     */
    private final List<String> warnings;

    /**
     * 失败原因摘要（已脱敏）
     */
    private final String errorMessage;

    public List<String> getWarnings() {
        return warnings != null ? warnings : Collections.emptyList();
    }
}
