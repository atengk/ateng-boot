package io.github.atengk.boot.flyway.listener;

import io.github.atengk.boot.flyway.model.MigrationOutcome;

/**
 * Flyway 数据库迁移生命周期扩展监听接口（SPI）
 * <p>
 * 容器中注册的所有 {@link FlywayMigrationListener} Bean 均会在单库及受管目标迁移前后自动触发回调，
 * 并由 {@code FlywayMetricsBinder} 等可观测组件复用。监听回调异常会被隔离记录日志，不影响主迁移契约返回。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-17
 */
public interface FlywayMigrationListener {

    /**
     * 迁移执行前前置通知
     *
     * @param targetKey 目标数据源标识
     */
    default void onBeforeMigrate(String targetKey) {
    }

    /**
     * 迁移执行成功后回调
     *
     * @param outcome 迁移执行结果视图
     */
    default void onMigrationSucceeded(MigrationOutcome outcome) {
    }

    /**
     * 迁移执行失败后回调
     *
     * @param targetKey 目标数据源标识
     * @param outcome   失败结果摘要（错误消息已脱敏）
     * @param cause     触发失败的原始异常
     */
    default void onMigrationFailed(String targetKey, MigrationOutcome outcome, Throwable cause) {
    }
}
