package io.github.atengk.boot.flyway.event;

import io.github.atengk.boot.flyway.model.MigrationOutcome;
import org.springframework.context.ApplicationEvent;

import java.util.Objects;

/**
 * Flyway 数据库迁移成功领域事件
 *
 * @author Ateng
 * @since 2026-11-17
 */
public class FlywayMigrationCompletedEvent extends ApplicationEvent {

    private static final long serialVersionUID = 1L;

    private final transient MigrationOutcome outcome;

    /**
     * 构造迁移成功领域事件
     *
     * @param source  事件发布源
     * @param outcome 本次迁移执行结果视图对象
     */
    public FlywayMigrationCompletedEvent(Object source, MigrationOutcome outcome) {
        super(source);
        this.outcome = Objects.requireNonNull(outcome, "outcome 不能为空");
    }

    public String getTargetKey() {
        return outcome.getTargetKey();
    }

    public MigrationOutcome getOutcome() {
        return outcome;
    }
}
