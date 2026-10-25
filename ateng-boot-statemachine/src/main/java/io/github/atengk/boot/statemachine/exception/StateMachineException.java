package io.github.atengk.boot.statemachine.exception;

import io.github.atengk.boot.core.code.IResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.statemachine.enums.StateMachineResultCode;
import lombok.Getter;

/**
 * 状态机受管业务异常
 *
 * @author Ateng
 * @since 2026-10-25
 */
@Getter
public class StateMachineException extends BusinessException {

    private static final long serialVersionUID = 1L;

    /**
     * 源状态
     */
    private final Object sourceState;

    /**
     * 目标状态
     */
    private final Object targetState;

    /**
     * 触发事件
     */
    private final Object event;

    public StateMachineException(String message) {
        this(StateMachineResultCode.TRANSITION_NOT_FOUND, message);
    }

    public StateMachineException(String message, Throwable cause) {
        this(StateMachineResultCode.ACTION_EXECUTION_FAILED, message, cause);
    }

    public StateMachineException(IResultCode resultCode) {
        super(resultCode);
        this.sourceState = null;
        this.targetState = null;
        this.event = null;
    }

    public StateMachineException(IResultCode resultCode, String message) {
        super(resultCode, message);
        this.sourceState = null;
        this.targetState = null;
        this.event = null;
    }

    public StateMachineException(IResultCode resultCode, String message, Throwable cause) {
        super(resultCode, message, cause);
        this.sourceState = null;
        this.targetState = null;
        this.event = null;
    }

    public StateMachineException(IResultCode resultCode, Object sourceState, Object event, String message) {
        this(resultCode, sourceState, null, event, message, null);
    }

    public StateMachineException(IResultCode resultCode, Object sourceState, Object event, String message, Throwable cause) {
        this(resultCode, sourceState, null, event, message, cause);
    }

    public StateMachineException(IResultCode resultCode, Object sourceState, Object targetState, Object event, String message) {
        this(resultCode, sourceState, targetState, event, message, null);
    }

    public StateMachineException(IResultCode resultCode, Object sourceState, Object targetState, Object event, String message, Throwable cause) {
        super(resultCode, message, cause);
        this.sourceState = sourceState;
        this.targetState = targetState;
        this.event = event;
    }
}
