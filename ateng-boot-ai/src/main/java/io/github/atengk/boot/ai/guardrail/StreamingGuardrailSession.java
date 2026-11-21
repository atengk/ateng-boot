package io.github.atengk.boot.ai.guardrail;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 流式响应实时增量内容安全护栏会话状态机。
 * <p>
 * 统一封装累计输出缓冲区、一次性终止原子标记及增量合规检查逻辑，供 {@code AiTemplate}、
 * {@code SseTokenStreamBridge} 与 {@code GuardrailedTokenStream} 共享复用，避免多处重复状态机代码。
 *
 * @author Ateng
 * @since 2026-11-21
 */
public final class StreamingGuardrailSession {

    private final AiGuardrail guardrail;
    private final StringBuilder accumulated = new StringBuilder();
    private final AtomicBoolean terminated = new AtomicBoolean(false);
    private final ReentrantLock bufferLock = new ReentrantLock();

    public StreamingGuardrailSession(AiGuardrail guardrail) {
        this.guardrail = guardrail;
    }

    /**
     * 判断当前流是否已因触发护栏动作而终止。
     *
     * @return 已终止返回 {@code true}
     */
    public boolean isTerminated() {
        return terminated.get();
    }

    /**
     * 将外部终止信号标记为已终止（用于桥接器统一收口）。
     */
    public void markTerminated() {
        terminated.set(true);
    }

    /**
     * 追加增量 Token 并执行实时后置护栏审查；命中违规时原子置位并执行断流回调。
     *
     * @param token        当前增量片段
     * @param cancelAction 物理断流动作（允许为空）
     * @return 本次判定决策
     */
    public Decision evaluateNext(String token, Runnable cancelAction) {
        if (terminated.get()) {
            return Decision.ALREADY_TERMINATED;
        }
        String snapshot;
        bufferLock.lock();
        try {
            if (terminated.get()) {
                return Decision.ALREADY_TERMINATED;
            }
            if (token != null) {
                accumulated.append(token);
            }
            if (guardrail == null) {
                return Decision.ALLOW;
            }
            snapshot = accumulated.toString();
        } finally {
            if (bufferLock.isHeldByCurrentThread()) {
                bufferLock.unlock();
            }
        }
        GuardrailResult postResult = guardrail.inspectResponse(snapshot);
        return evaluatePostResult(postResult, cancelAction);
    }

    /**
     * 在流式完结阶段对累计全文执行终检（未配置护栏或已提前终止时安全跳过）。
     *
     * @param cancelAction 物理断流动作（允许为空）
     * @return 终检判定决策
     */
    public Decision evaluateComplete(Runnable cancelAction) {
        if (terminated.get()) {
            return Decision.ALREADY_TERMINATED;
        }
        if (guardrail == null) {
            return Decision.ALLOW;
        }
        String snapshot;
        bufferLock.lock();
        try {
            if (terminated.get()) {
                return Decision.ALREADY_TERMINATED;
            }
            snapshot = accumulated.toString();
        } finally {
            if (bufferLock.isHeldByCurrentThread()) {
                bufferLock.unlock();
            }
        }
        GuardrailResult postResult = guardrail.inspectResponse(snapshot);
        return evaluatePostResult(postResult, cancelAction);
    }

    private Decision evaluatePostResult(GuardrailResult postResult, Runnable cancelAction) {
        if (postResult == null || postResult.getAction() == GuardrailAction.ALLOW) {
            return Decision.ALLOW;
        }
        if (!terminated.compareAndSet(false, true)) {
            return Decision.ALREADY_TERMINATED;
        }
        if (cancelAction != null) {
            cancelAction.run();
        }
        if (postResult.getAction() == GuardrailAction.BLOCK) {
            return new Decision(DecisionStatus.BLOCK, postResult);
        }
        return new Decision(DecisionStatus.REPLACE, postResult);
    }

    /**
     * 增量审查状态枚举。
     */
    public enum DecisionStatus {
        ALLOW,
        BLOCK,
        REPLACE,
        TERMINATED
    }

    /**
     * 增量审查判定结果。
     *
     * @param status 判定状态
     * @param result 原始护栏审查结果（放行或重复终止时可能为 {@code null}）
     */
    public record Decision(DecisionStatus status, GuardrailResult result) {

        public static final Decision ALLOW = new Decision(DecisionStatus.ALLOW, null);
        public static final Decision ALREADY_TERMINATED = new Decision(DecisionStatus.TERMINATED, null);

        public String safeContent() {
            return result != null && result.getSafeContent() != null ? result.getSafeContent() : "";
        }

        public String reason() {
            return result != null ? result.getReason() : null;
        }
    }
}
