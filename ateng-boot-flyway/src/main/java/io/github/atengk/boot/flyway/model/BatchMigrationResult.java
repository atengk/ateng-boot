package io.github.atengk.boot.flyway.model;

import io.github.atengk.boot.flyway.enums.BatchExecutionPolicy;
import io.github.atengk.boot.flyway.enums.FlywayExecutionMode;
import lombok.Builder;
import lombok.Getter;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多数据源 / 物理多租户批量迁移与校验结构化聚合回执
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Getter
@Builder
public class BatchMigrationResult {

    /**
     * 本次执行模式（MIGRATE / VALIDATE_ONLY / DISABLED）
     */
    private final FlywayExecutionMode mode;

    /**
     * 本次批量执行生效的容错策略（FAIL_FAST / FAIL_SAFE）
     */
    private final BatchExecutionPolicy policy;

    /**
     * 执行完成时间戳
     */
    private final Instant executedAt;

    /**
     * 总执行耗时
     */
    private final Duration totalDuration;

    /**
     * 总计处理目标数据源数
     */
    private final int totalTargets;

    /**
     * 成功目标数
     */
    private final int successCount;

    /**
     * 跳过目标数
     */
    private final int skippedCount;

    /**
     * 失败目标数
     */
    private final int failedCount;

    /**
     * 各目标完整执行结果明细列表（永不为 null）
     */
    private final List<MigrationOutcome> outcomes;

    /**
     * 失败目标脱敏明细列表（永不为 null）
     */
    private final List<TargetFailure> failures;

    public List<MigrationOutcome> getOutcomes() {
        return outcomes != null ? outcomes : Collections.emptyList();
    }

    public List<TargetFailure> getFailures() {
        return failures != null ? failures : Collections.emptyList();
    }

    /**
     * 是否全部目标执行成功且无失败项
     *
     * @return true 表示零失败且至少处理了 1 个目标
     */
    public boolean isAllSuccess() {
        return failedCount == 0 && totalTargets > 0 && getFailures().isEmpty();
    }

    /**
     * 是否存在失败项
     *
     * @return true 表示存在至少一个失败目标
     */
    public boolean hasFailures() {
        return failedCount > 0 || !getFailures().isEmpty();
    }

    /**
     * 提取所有失败目标的标识列表
     *
     * @return 失败目标 Key 列表（永不为 null）
     */
    public List<String> getFailedTargets() {
        List<TargetFailure> list = getFailures();
        if (list.isEmpty()) {
            return Collections.emptyList();
        }
        return list.stream().map(TargetFailure::getTargetKey).toList();
    }

    /**
     * 获取各目标执行耗时的有序映射视图
     *
     * @return targetKey -> Duration 映射（永不为 null）
     */
    public Map<String, Duration> getTargetDurations() {
        List<MigrationOutcome> list = getOutcomes();
        if (list.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, Duration> map = new LinkedHashMap<>(list.size());
        for (MigrationOutcome outcome : list) {
            if (outcome != null && outcome.getTargetKey() != null) {
                map.put(outcome.getTargetKey(), outcome.getDuration() != null ? outcome.getDuration() : Duration.ZERO);
            }
        }
        return Collections.unmodifiableMap(map);
    }
}
