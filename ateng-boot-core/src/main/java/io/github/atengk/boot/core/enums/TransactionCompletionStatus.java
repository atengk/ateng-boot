package io.github.atengk.boot.core.enums;

/**
 * 事务最终完成状态枚举
 * 对应 Spring TransactionSynchronization 的 STATUS_COMMITTED、STATUS_ROLLED_BACK 与 STATUS_UNKNOWN
 *
 * @author Ateng
 * @since 2026-10-26
 */
public enum TransactionCompletionStatus {

    /**
     * 事务已成功提交
     */
    COMMITTED,

    /**
     * 事务已回滚
     */
    ROLLED_BACK,

    /**
     * 事务完成状态未知（如提交/回滚时遇到异常或非事务直接执行）
     */
    UNKNOWN;

    /**
     * 将 Spring 事务同步状态数值转译为标准强类型枚举
     *
     * @param status Spring 状态码（0: COMMITTED, 1: ROLLED_BACK, 2: UNKNOWN）
     * @return 对应的枚举项
     */
    public static TransactionCompletionStatus fromSpringStatus(int status) {
        return switch (status) {
            case 0 -> COMMITTED;
            case 1 -> ROLLED_BACK;
            default -> UNKNOWN;
        };
    }
}
