package io.github.atengk.boot.sample.liteflow.interceptor;

import io.github.atengk.boot.liteflow.core.LiteflowExecutionInterceptor;
import io.github.atengk.boot.liteflow.trace.LiteflowExecutionTrace;
import io.github.atengk.boot.sample.liteflow.model.InterceptorStatsResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * LiteFlow 样例工程全局三阶段执行审计拦截器演示 Bean
 * <p>
 * 演示业务服务只需声明 {@link LiteflowExecutionInterceptor} Spring Bean，即可由底座自动收集排序并挂载至
 * {@code LiteflowTemplate} 同步/异步/即时 EL/决策路由全链路生命周期，记录执行耗时与异常审计摘要。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-21
 */
@Slf4j
@Component
public class SampleAuditExecutionInterceptor implements LiteflowExecutionInterceptor {

    private static final int MAX_RECENT_RECORDS = 20;

    private final AtomicLong successCount = new AtomicLong(0L);
    private final AtomicLong errorCount = new AtomicLong(0L);
    private final ReentrantLock lock = new ReentrantLock();
    private final Deque<String> recentRecords = new ArrayDeque<>(MAX_RECENT_RECORDS);

    @Override
    public int getOrder() {
        return 100;
    }

    @Override
    public void beforeExecute(String chainId, Object param) {
        log.debug("【全局拦截器-前置】准备调度规则链: chainId={}", chainId);
    }

    @Override
    public void afterExecute(String chainId, Object param, LiteflowExecutionTrace trace) {
        successCount.incrementAndGet();
        long timeMs = trace != null ? trace.getTotalTimeSpentMs() : 0L;
        boolean slow = trace != null && trace.isSlow();
        String entry = "SUCCESS:" + chainId + ":" + timeMs + "ms" + (slow ? ":SLOW" : "");
        appendRecord(entry);
        if (slow) {
            log.warn("【全局拦截器-后置慢告警】chainId={}, elapsed={}ms, slowNodes={}",
                    chainId, timeMs, trace.getSlowNodeIds());
        } else {
            log.info("【全局拦截器-后置成功】chainId={}, elapsed={}ms", chainId, timeMs);
        }
    }

    @Override
    public void onError(String chainId, Object param, LiteflowExecutionTrace trace, Throwable error) {
        errorCount.incrementAndGet();
        String errType = error != null ? error.getClass().getSimpleName() : "UnknownError";
        appendRecord("ERROR:" + chainId + ":" + errType);
        log.warn("【全局拦截器-异常审计】chainId={}, errorType={}", chainId, errType);
    }

    /**
     * 获取当前全局拦截器只读审计统计快照
     *
     * @return 审计统计快照
     */
    public InterceptorStatsResponse snapshot() {
        List<String> records;
        lock.lock();
        try {
            records = List.copyOf(new ArrayList<>(recentRecords));
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
        return new InterceptorStatsResponse(successCount.get(), errorCount.get(), records);
    }

    private void appendRecord(String entry) {
        lock.lock();
        try {
            recentRecords.addFirst(entry);
            while (recentRecords.size() > MAX_RECENT_RECORDS) {
                recentRecords.removeLast();
            }
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }
}
