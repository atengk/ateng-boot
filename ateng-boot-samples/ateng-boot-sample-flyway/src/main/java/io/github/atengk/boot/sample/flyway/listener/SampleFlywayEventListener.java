package io.github.atengk.boot.sample.flyway.listener;

import io.github.atengk.boot.flyway.event.FlywayMigrationCompletedEvent;
import io.github.atengk.boot.flyway.event.FlywayMigrationFailedEvent;
import io.github.atengk.boot.flyway.model.MigrationOutcome;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Flyway 迁移完成与失败领域事件监听示例
 *
 * @author Ateng
 * @since 2026-11-17
 */
@Slf4j
@Component
public class SampleFlywayEventListener {

    private final AtomicInteger completedEventCount = new AtomicInteger(0);
    private final AtomicInteger failedEventCount = new AtomicInteger(0);

    /**
     * 订阅单目标或多源迁移成功领域事件
     *
     * @param event 迁移完成事件
     */
    @EventListener
    public void onCompleted(FlywayMigrationCompletedEvent event) {
        completedEventCount.incrementAndGet();
        MigrationOutcome outcome = event.getOutcome();
        log.info("[SampleFlyway] 迁移完成事件 [target={}, version={}, executed={}]",
                outcome.getTargetKey(), outcome.getTargetSchemaVersion(), outcome.getMigrationsExecuted());
    }

    /**
     * 订阅迁移失败领域事件（失败原因已脱敏）
     *
     * @param event 迁移失败事件
     */
    @EventListener
    public void onFailed(FlywayMigrationFailedEvent event) {
        failedEventCount.incrementAndGet();
        log.error("[SampleFlyway] 迁移失败事件 [target={}, mode={}, reason={}]",
                event.getTargetKey(), event.getMode(), event.getErrorMessage());
    }

    /**
     * 获取累计接收到的迁移完成事件数
     *
     * @return 完成事件计数
     */
    public int getCompletedEventCount() {
        return completedEventCount.get();
    }

    /**
     * 获取累计接收到的迁移失败事件数
     *
     * @return 失败事件计数
     */
    public int getFailedEventCount() {
        return failedEventCount.get();
    }
}
