package io.github.atengk.boot.ai.exception;

import io.github.atengk.boot.core.code.IResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import lombok.Getter;

/**
 * AI 智能体与模型通信业务异常基类
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Getter
public class AiException extends BusinessException {

    private static final long serialVersionUID = 1L;

    public AiException(IResultCode resultCode) {
        super(resultCode);
    }

    public AiException(IResultCode resultCode, Throwable cause) {
        super(resultCode, cause);
    }

    public AiException(IResultCode resultCode, String msg) {
        super(resultCode, msg);
    }

    public AiException(IResultCode resultCode, String msg, Throwable cause) {
        super(resultCode, msg, cause);
    }

    public AiException(String msg) {
        super(AiResultCode.AI_CALL_FAILED, msg);
    }

    public AiException(String msg, Throwable cause) {
        super(AiResultCode.AI_CALL_FAILED, msg, cause);
    }
}
