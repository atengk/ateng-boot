package io.github.atengk.boot.flyway.event;

import io.github.atengk.boot.flyway.config.FlywayConfigurationSupport;
import io.github.atengk.boot.flyway.enums.FlywayExecutionMode;
import io.github.atengk.boot.flyway.model.MigrationOutcome;
import org.springframework.context.ApplicationEvent;

import java.util.Objects;

/**
 * Flyway 数据库迁移失败领域事件
 * <p>
 * 事件承载的失败原因摘要与异常信息均经过统一脱敏，防止连接串口令与密钥参数经事件总线外泄。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-17
 */
public class FlywayMigrationFailedEvent extends ApplicationEvent {

    private static final long serialVersionUID = 1L;

    private final String targetKey;
    private final FlywayExecutionMode mode;
    private final String errorMessage;
    private final String exceptionType;
    private final transient MigrationOutcome outcome;
    private final transient Throwable cause;

    /**
     * 构造迁移失败领域事件
     *
     * @param source       事件发布源
     * @param targetKey    目标数据源标识
     * @param mode         执行模式
     * @param errorMessage 失败原因摘要（将强制执行统一脱敏）
     * @param outcome      失败结果摘要快照
     * @param cause        原始异常
     */
    public FlywayMigrationFailedEvent(Object source,
                                      String targetKey,
                                      FlywayExecutionMode mode,
                                      String errorMessage,
                                      MigrationOutcome outcome,
                                      Throwable cause) {
        super(source);
        this.targetKey = Objects.requireNonNull(targetKey, "targetKey 不能为空");
        this.mode = mode;
        this.errorMessage = FlywayConfigurationSupport.sanitizeSensitiveText(errorMessage);
        this.exceptionType = cause != null ? cause.getClass().getName() : null;
        this.outcome = outcome;
        this.cause = cause;
    }

    public String getTargetKey() {
        return targetKey;
    }

    public FlywayExecutionMode getMode() {
        return mode;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getExceptionType() {
        return exceptionType;
    }

    public MigrationOutcome getOutcome() {
        return outcome;
    }

    public Throwable getCause() {
        return cause;
    }
}
