package io.github.atengk.boot.ssh.model;

import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.time.Duration;

/**
 * SSH 集群多节点批量巡检控制选项配置
 * <p>
 * 封装并发度限流阈值 (concurrency)、快速失败熔断策略 (failFast)、全局批量超时及命令执行选项 (execOptions)。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Getter
@ToString
@EqualsAndHashCode
@Builder(toBuilder = true)
public final class SshBatchOptions {

    /**
     * 默认最大并发度：CPU核心数 * 2（保底不少于 2）
     */
    public static final int DEFAULT_CONCURRENCY = Math.max(2, Runtime.getRuntime().availableProcessors() * 2);

    /**
     * 默认缺省选项单例
     */
    private static final SshBatchOptions DEFAULT_OPTIONS = SshBatchOptions.builder().build();

    /**
     * 并发度节流上限（基于 Semaphore 实施限流）
     */
    @Builder.Default
    private final int concurrency = DEFAULT_CONCURRENCY;

    /**
     * 单节点失败策略（false 为 Fail-At-End 容错汇总，true 为 Fail-Fast 遇错熔断）
     */
    @Builder.Default
    private final boolean failFast = false;

    /**
     * 全局批量任务超时阈值（为 null 则不设全局超时）
     */
    private final Duration timeout;

    /**
     * 单节点命令执行扩展配置
     */
    private final SshExecOptions execOptions;

    /**
     * 获取生产级合理默认配置
     *
     * @return 默认配置单例
     */
    public static SshBatchOptions defaults() {
        return DEFAULT_OPTIONS;
    }

    /**
     * 便捷构造指定并发度的配置
     *
     * @param concurrency 并发度上限
     * @return 批量控制选项
     */
    public static SshBatchOptions ofConcurrency(int concurrency) {
        return SshBatchOptions.builder().concurrency(concurrency).build();
    }

    /**
     * 便捷构造指定失败策略的配置
     *
     * @param failFast 是否开启快速失败
     * @return 批量控制选项
     */
    public static SshBatchOptions ofFailFast(boolean failFast) {
        return SshBatchOptions.builder().failFast(failFast).build();
    }
}
