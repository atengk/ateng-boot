package io.github.atengk.boot.liteflow.thread;

import com.yomahub.liteflow.lifecycle.LifeCycleHolder;
import com.yomahub.liteflow.lifecycle.PostProcessFlowExecuteLifeCycle;
import com.yomahub.liteflow.slot.Slot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;

/**
 * LiteFlow 流程执行超时协同中断生命周期拦截器
 * 负责在流程开始前记录请求上下文映射，并在流程发生硬超时或异常时，
 * 协同中断受管线程池中仍在运行的子线程，防止工作线程死锁或无限阻塞拖垮应用线程池。
 *
 * @author Ateng
 * @since 2026-11-03
 */
public class FlowTimeoutInterruptionLifeCycle implements PostProcessFlowExecuteLifeCycle, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(FlowTimeoutInterruptionLifeCycle.class);

    private final boolean interruptOnTimeout;

    public FlowTimeoutInterruptionLifeCycle() {
        this(true);
    }

    public FlowTimeoutInterruptionLifeCycle(boolean interruptOnTimeout) {
        this.interruptOnTimeout = interruptOnTimeout;
    }

    public boolean isInterruptOnTimeout() {
        return interruptOnTimeout;
    }

    @Override
    public void postProcessBeforeFlowExecute(String chainId, Slot slot) {
        if (slot != null && slot.getRequestId() != null) {
            FlowThreadTracker.bindCurrentSlot(slot.getRequestId());
        }
    }

    @Override
    public void postProcessAfterFlowExecute(String chainId, Slot slot) {
        String requestId = slot != null ? slot.getRequestId() : null;
        try {
            if (interruptOnTimeout && slot != null && slot.getException() != null && requestId != null) {
                // 流程异常失败（如 WhenTimeoutException 等），协同向活动子线程发送中断信号
                int interrupted = FlowThreadTracker.interruptRunningThreads(requestId);
                if (interrupted > 0) {
                    log.warn("[LiteFlow Timeout] 流程 [{}] 发生异常 (原因: {})，已协同向 {} 个悬挂子线程发送中断信号",
                            chainId, slot.getException().getClass().getSimpleName(), interrupted);
                }
            }
        } catch (Throwable t) {
            log.error("[LiteFlow Timeout] 流程 [{}] 后置超时中断处理发生异常: {}", chainId, t.getMessage(), t);
        } finally {
            if (requestId != null) {
                FlowThreadTracker.clean(requestId);
            }
            FlowThreadTracker.unbindCurrentSlot();
        }
    }

    @Override
    public void destroy() {
        try {
            LifeCycleHolder.getPostProcessFlowExecuteLifeCycleList().remove(this);
        } catch (Throwable ignored) {
        }
        FlowThreadTracker.clearAll();
    }
}
