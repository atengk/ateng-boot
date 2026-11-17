package io.github.atengk.boot.flyway.model;

import io.github.atengk.boot.flyway.enums.FlywayExecutionMode;
import lombok.Builder;
import lombok.Getter;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.List;

/**
 * 最近一次迁移执行全局汇总摘要快照
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Getter
@Builder
public class MigrationSummary {

    /**
     * 本次执行模式
     */
    private final FlywayExecutionMode mode;

    /**
     * 执行完成时间
     */
    private final Instant executedAt;

    /**
     * 总耗时
     */
    private final Duration totalDuration;

    /**
     * 总目标数据源数
     */
    private final int totalTargets;

    /**
     * 成功目标数
     */
    private final int successCount;

    /**
     * 失败目标数
     */
    private final int failedCount;

    /**
     * 跳过目标数
     */
    private final int skippedCount;

    /**
     * 各目标执行明细列表
     */
    private final List<MigrationOutcome> outcomes;

    public List<MigrationOutcome> getOutcomes() {
        return outcomes != null ? outcomes : Collections.emptyList();
    }
}
