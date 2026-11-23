package io.github.atengk.boot.liteflow.core;

import io.github.atengk.boot.liteflow.trace.LiteflowExecutionTrace;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.OrderComparator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/**
 * 全局执行拦截器排序过滤与 Fail-Open 生命周期调度协作者
 *
 * @author Ateng
 * @since 2026-11-21
 */
final class LiteflowInterceptorDispatcher {

    private static final Logger log = LoggerFactory.getLogger(LiteflowInterceptorDispatcher.class);

    private final List<LiteflowExecutionInterceptor> interceptors;

    LiteflowInterceptorDispatcher(List<LiteflowExecutionInterceptor> interceptors) {
        if (interceptors == null || interceptors.isEmpty()) {
            this.interceptors = Collections.emptyList();
            return;
        }
        List<LiteflowExecutionInterceptor> sorted = new ArrayList<>(interceptors.size());
        for (LiteflowExecutionInterceptor interceptor : interceptors) {
            if (interceptor != null) {
                sorted.add(interceptor);
            }
        }
        sorted.sort(OrderComparator.INSTANCE);
        this.interceptors = Collections.unmodifiableList(sorted);
    }

    List<LiteflowExecutionInterceptor> filter(String chainId) {
        if (this.interceptors.isEmpty()) {
            return Collections.emptyList();
        }
        List<LiteflowExecutionInterceptor> matched = new ArrayList<>(this.interceptors.size());
        for (LiteflowExecutionInterceptor interceptor : this.interceptors) {
            try {
                if (interceptor.supports(chainId)) {
                    matched.add(interceptor);
                }
            } catch (Throwable t) {
                log.warn("ateng-boot-liteflow: 执行拦截器 supports 判定异常已 Fail-Open 跳过 [chainId={}, interceptor={}]: {}",
                        chainId, interceptor.getClass().getName(), t.getMessage(), t);
            }
        }
        return matched;
    }

    void beforeExecute(List<LiteflowExecutionInterceptor> active, String chainId, Object param) {
        invokeStage(active, chainId, "beforeExecute", interceptor -> interceptor.beforeExecute(chainId, param));
    }

    void afterExecute(List<LiteflowExecutionInterceptor> active,
                      String chainId,
                      Object param,
                      LiteflowExecutionTrace trace) {
        invokeStage(active, chainId, "afterExecute", interceptor -> interceptor.afterExecute(chainId, param, trace));
    }

    void onError(List<LiteflowExecutionInterceptor> active,
                 String chainId,
                 Object param,
                 LiteflowExecutionTrace trace,
                 Throwable error) {
        invokeStage(active, chainId, "onError", interceptor -> interceptor.onError(chainId, param, trace, error));
    }

    private void invokeStage(List<LiteflowExecutionInterceptor> active,
                             String chainId,
                             String stage,
                             Consumer<LiteflowExecutionInterceptor> action) {
        for (LiteflowExecutionInterceptor interceptor : active) {
            try {
                action.accept(interceptor);
            } catch (Throwable t) {
                log.warn("ateng-boot-liteflow: 执行拦截器 {} 异常已 Fail-Open 放行 [chainId={}, interceptor={}]: {}",
                        stage, chainId, interceptor.getClass().getName(), t.getMessage(), t);
            }
        }
    }
}
