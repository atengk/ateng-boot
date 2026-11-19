package io.github.atengk.boot.flyway.model;

import io.github.atengk.boot.flyway.enums.BatchExecutionPolicy;
import lombok.Builder;
import lombok.Getter;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多数据源 / 物理多租户非迁移类批量操作（预检、历史探查、修复、基线）泛型不可变聚合回执
 *
 * @param <T> 单目标操作结果视图类型
 * @author Ateng
 * @since 2026-11-19
 */
@Getter
public class BatchOperationResult<T> {

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
     * 失败目标数
     */
    private final int failedCount;

    /**
     * 各成功目标操作结果的保序不可变映射（targetKey -> T，永不为 null）
     */
    private final Map<String, T> results;

    /**
     * 失败目标脱敏明细不可变列表（永不为 null）
     */
    private final List<TargetFailure> failures;

    /**
     * 各目标执行耗时的保序不可变映射（targetKey -> Duration，永不为 null）
     */
    private final Map<String, Duration> targetDurations;

    @Builder
    private BatchOperationResult(BatchExecutionPolicy policy,
                                 Instant executedAt,
                                 Duration totalDuration,
                                 int totalTargets,
                                 int successCount,
                                 int failedCount,
                                 Map<String, T> results,
                                 List<TargetFailure> failures,
                                 Map<String, Duration> targetDurations) {
        this.policy = policy;
        this.executedAt = executedAt;
        this.totalDuration = totalDuration != null ? totalDuration : Duration.ZERO;
        this.totalTargets = totalTargets;
        this.successCount = successCount;
        this.failedCount = failedCount;
        this.results = (results == null || results.isEmpty())
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(results));
        this.failures = (failures == null || failures.isEmpty())
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(failures));
        this.targetDurations = (targetDurations == null || targetDurations.isEmpty())
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(targetDurations));
    }

    /**
     * 是否全部目标执行成功且无失败项
     *
     * @return true 表示零失败且至少处理了 1 个目标
     */
    public boolean isAllSuccess() {
        return failedCount == 0 && totalTargets > 0 && failures.isEmpty();
    }

    /**
     * 是否存在失败项
     *
     * @return true 表示存在至少一个失败目标
     */
    public boolean hasFailures() {
        return failedCount > 0 || !failures.isEmpty();
    }

    /**
     * 提取所有失败目标的标识列表
     *
     * @return 失败目标 Key 不可变列表（永不为 null）
     */
    public List<String> getFailedTargets() {
        if (failures.isEmpty()) {
            return Collections.emptyList();
        }
        return failures.stream().map(TargetFailure::getTargetKey).toList();
    }
}
