package io.github.atengk.boot.flyway.exception;

import io.github.atengk.boot.core.code.IResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.flyway.enums.FlywayResultCode;

/**
 * Flyway 数据库版本化迁移专有业务异常
 *
 * @author Ateng
 * @since 2026-11-17
 */
public class FlywayException extends BusinessException {

    private static final long serialVersionUID = 1L;

    /**
     * 使用 Flyway 结果状态码构造异常
     *
     * @param resultCode 结果状态码
     */
    public FlywayException(IResultCode resultCode) {
        super(resultCode);
    }

    /**
     * 使用状态码与底层原因构造异常
     *
     * @param resultCode 结果状态码
     * @param cause      原始异常原因
     */
    public FlywayException(IResultCode resultCode, Throwable cause) {
        super(resultCode, cause);
    }

    /**
     * 使用状态码与脱敏描述构造异常
     *
     * @param resultCode 结果状态码
     * @param customMsg  自定义错误描述
     */
    public FlywayException(IResultCode resultCode, String customMsg) {
        super(resultCode, customMsg);
    }

    /**
     * 使用状态码、脱敏描述与底层原因构造异常
     *
     * @param resultCode 结果状态码
     * @param customMsg  自定义错误描述
     * @param cause      原始异常原因
     */
    public FlywayException(IResultCode resultCode, String customMsg, Throwable cause) {
        super(resultCode, customMsg, cause);
    }

    /**
     * 默认归类为 {@link FlywayResultCode#MIGRATION_FAILED} 构造异常
     *
     * @param message 错误描述
     */
    public FlywayException(String message) {
        super(FlywayResultCode.MIGRATION_FAILED, message);
    }

    /**
     * 默认归类为 {@link FlywayResultCode#MIGRATION_FAILED} 并保留因果链构造异常
     *
     * @param message 错误描述
     * @param cause   原始异常原因
     */
    public FlywayException(String message, Throwable cause) {
        super(FlywayResultCode.MIGRATION_FAILED, message, cause);
    }
}
