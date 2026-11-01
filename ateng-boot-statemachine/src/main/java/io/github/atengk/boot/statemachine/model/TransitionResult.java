package io.github.atengk.boot.statemachine.model;

import io.github.atengk.boot.statemachine.enums.StateMachineResultCode;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;

import java.io.Serializable;

/**
 * 状态跃迁结构化执行结果承载对象
 *
 * @param <S> 业务状态类型
 * @author Ateng
 * @since 2026-10-25
 */
@Getter
@ToString
@EqualsAndHashCode
public class TransitionResult<S> implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 是否成功接受并执行跃迁
     */
    private final boolean accepted;

    /**
     * 是否成功（accepted 语义别名）
     *
     * @return true 代表跃迁成功
     */
    public boolean isSuccess() {
        return accepted;
    }

    /**
     * 源状态
     */
    private final S sourceState;

    /**
     * 目标状态（成功时为新状态，失败或自跃迁时为源状态）
     */
    private final S targetState;

    /**
     * 触发事件
     */
    private final Object event;

    /**
     * 失败原因描述
     */
    private final String failureReason;

    /**
     * 失败异常原因（如有）
     */
    private final Throwable cause;

    /**
     * 是否为终态幂等放行（重复事件投递）
     */
    private final boolean idempotent;

    /**
     * 强类型状态机错误码（失败时有值，成功时为 null）
     */
    private final StateMachineResultCode resultCode;

    /**
     * 是否为终态幂等放行
     *
     * @return true 代表为幂等放行
     */
    public boolean isIdempotent() {
        return idempotent;
    }

    /**
     * 获取强类型状态机错误码
     *
     * @return 错误码，成功时为 null
     */
    public StateMachineResultCode getResultCode() {
        return resultCode;
    }

    private TransitionResult(boolean accepted, S sourceState, S targetState, Object event, String failureReason, Throwable cause) {
        this(accepted, sourceState, targetState, event, failureReason, cause, false, null);
    }

    private TransitionResult(boolean accepted, S sourceState, S targetState, Object event, String failureReason, Throwable cause, boolean idempotent) {
        this(accepted, sourceState, targetState, event, failureReason, cause, idempotent, null);
    }

    private TransitionResult(boolean accepted, S sourceState, S targetState, Object event, String failureReason, Throwable cause, boolean idempotent, StateMachineResultCode resultCode) {
        this.accepted = accepted;
        this.sourceState = sourceState;
        this.targetState = targetState;
        this.event = event;
        this.failureReason = failureReason;
        this.cause = cause;
        this.idempotent = idempotent;
        this.resultCode = resultCode;
    }

    /**
     * 构建成功跃迁结果
     *
     * @param sourceState 源状态
     * @param targetState 目标状态
     * @param event       触发事件
     * @param <S>         状态类型
     * @return 成功结果
     */
    public static <S> TransitionResult<S> success(S sourceState, S targetState, Object event) {
        return new TransitionResult<>(true, sourceState, targetState, event, null, null, false, null);
    }

    /**
     * 构建终态幂等放行成功结果（重复事件投递放行）
     *
     * @param state 当前状态（源状态与目标状态一致）
     * @param event 触发事件
     * @param <S>   状态类型
     * @return 幂等成功结果
     */
    public static <S> TransitionResult<S> idempotent(S state, Object event) {
        return new TransitionResult<>(true, state, state, event, null, null, true, null);
    }

    /**
     * 构建失败跃迁结果（含错误码）
     *
     * @param resultCode    错误码
     * @param sourceState   源状态
     * @param event         触发事件
     * @param failureReason 失败原因
     * @param <S>           状态类型
     * @return 失败结果
     */
    public static <S> TransitionResult<S> failure(StateMachineResultCode resultCode, S sourceState, Object event, String failureReason) {
        return new TransitionResult<>(false, sourceState, sourceState, event, failureReason, null, false, resultCode);
    }

    /**
     * 构建失败跃迁结果（含错误码与异常因果链）
     *
     * @param resultCode    错误码
     * @param sourceState   源状态
     * @param event         触发事件
     * @param failureReason 失败原因
     * @param cause         原始异常
     * @param <S>           状态类型
     * @return 失败结果
     */
    public static <S> TransitionResult<S> failure(StateMachineResultCode resultCode, S sourceState, Object event, String failureReason, Throwable cause) {
        return new TransitionResult<>(false, sourceState, sourceState, event, failureReason, cause, false, resultCode);
    }

    /**
     * 构建失败跃迁结果
     *
     * @param sourceState   源状态
     * @param event         触发事件
     * @param failureReason 失败原因
     * @param <S>           状态类型
     * @return 失败结果
     */
    public static <S> TransitionResult<S> failure(S sourceState, Object event, String failureReason) {
        return failure(null, sourceState, event, failureReason, null);
    }

    /**
     * 构建失败跃迁结果（含异常因果链）
     *
     * @param sourceState   源状态
     * @param event         触发事件
     * @param failureReason 失败原因
     * @param cause         原始异常
     * @param <S>           状态类型
     * @return 失败结果
     */
    public static <S> TransitionResult<S> failure(S sourceState, Object event, String failureReason, Throwable cause) {
        return failure(null, sourceState, event, failureReason, cause);
    }
}
