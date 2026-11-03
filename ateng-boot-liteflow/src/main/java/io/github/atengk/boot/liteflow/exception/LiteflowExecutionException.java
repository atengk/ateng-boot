package io.github.atengk.boot.liteflow.exception;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.code.IResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.liteflow.trace.LiteflowExecutionTrace;
import lombok.Getter;

/**
 * LiteFlow 规则执行业务受管异常
 * 封装流程链 ID、失败节点 ID、结构化执行轨迹与完整因果链，严格保留底层根因堆栈
 *
 * @author Ateng
 * @since 2026-11-03
 */
@Getter
public class LiteflowExecutionException extends BusinessException {

    private static final long serialVersionUID = 1L;

    /**
     * 执行失败的规则链 ID
     */
    private final String chainId;

    /**
     * 触发失败的目标节点 ID（若能定位到具体组件节点）
     */
    private final String failedNodeId;

    /**
     * 执行失败时捕获的结构化时序链路轨迹（可为 null）
     */
    private final LiteflowExecutionTrace trace;

    public LiteflowExecutionException(String chainId, String failedNodeId, String message, Throwable cause) {
        this(CommonResultCode.INTERNAL_SERVER_ERROR, chainId, failedNodeId, null, message, cause);
    }

    public LiteflowExecutionException(String chainId, String failedNodeId, LiteflowExecutionTrace trace, String message, Throwable cause) {
        this(CommonResultCode.INTERNAL_SERVER_ERROR, chainId, failedNodeId, trace, message, cause);
    }

    public LiteflowExecutionException(IResultCode resultCode, String chainId, String failedNodeId, String message, Throwable cause) {
        this(resultCode, chainId, failedNodeId, null, message, cause);
    }

    public LiteflowExecutionException(IResultCode resultCode, String chainId, String failedNodeId, LiteflowExecutionTrace trace, String message, Throwable cause) {
        super(resultCode != null ? resultCode : CommonResultCode.INTERNAL_SERVER_ERROR, message, cause, true);
        this.chainId = chainId;
        this.failedNodeId = failedNodeId;
        this.trace = trace;
    }
}
