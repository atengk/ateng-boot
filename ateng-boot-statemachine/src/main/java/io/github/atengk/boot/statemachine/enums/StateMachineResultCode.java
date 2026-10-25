package io.github.atengk.boot.statemachine.enums;

import io.github.atengk.boot.core.code.IResultCode;
import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 状态机专属受管业务结果码枚举 (59001~59020)
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Getter
@AllArgsConstructor
public enum StateMachineResultCode implements IResultCode {

    /**
     * 未找到合法的状态跃迁路由
     */
    TRANSITION_NOT_FOUND(59001, "未找到合法的状态跃迁路由"),

    /**
     * 状态跃迁前置条件不满足
     */
    CONDITION_FAILED(59002, "状态跃迁前置条件不满足"),

    /**
     * 状态跃迁动作执行失败
     */
    ACTION_EXECUTION_FAILED(59003, "状态跃迁动作执行失败"),

    /**
     * 状态机标识重复定义
     */
    DUPLICATE_MACHINE_ID(59004, "状态机标识重复定义"),

    /**
     * 状态并发更新冲突（CAS 乐观锁防重拦截）
     */
    CONCURRENT_CAS_CONFLICT(59005, "状态并发更新冲突（CAS 乐观锁防重拦截）");

    private final Integer code;
    private final String msg;
}
