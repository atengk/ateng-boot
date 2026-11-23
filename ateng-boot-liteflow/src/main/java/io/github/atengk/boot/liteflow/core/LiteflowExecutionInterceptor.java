package io.github.atengk.boot.liteflow.core;

import io.github.atengk.boot.liteflow.trace.LiteflowExecutionTrace;
import org.springframework.core.Ordered;

/**
 * LiteFlow 全局执行生命周期拦截器 SPI
 * <p>
 * 挂载于 {@link LiteflowTemplate} 全路径执行入口（同步、异步底层执行、即时 EL 与决策路由编排），
 * 按 {@link Ordered} 升序执行，支持按 {@code chainId} 维度按需启用。所有回调阶段均采用 Fail-Open 容错策略，
 * 拦截器自身抛出的异常将被记录告警并隔离，绝不阻断业务主流程或篡改原始异常因果链。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-21
 */
public interface LiteflowExecutionInterceptor extends Ordered {

    /**
     * 获取当前拦截器优先级（数值越小越先执行，默认最低优先级）
     *
     * @return 优先级序号
     */
    @Override
    default int getOrder() {
        return Ordered.LOWEST_PRECEDENCE;
    }

    /**
     * 判断当前拦截器是否作用于指定规则链（默认对所有规则链生效）
     *
     * @param chainId 规则链标识（即时 EL 为 {@code DYNAMIC_EL[...]}，决策路由为命名空间或 {@code ROUTE_CHAIN}）
     * @return {@code true} 表示介入执行，{@code false} 表示跳过
     */
    default boolean supports(String chainId) {
        return true;
    }

    /**
     * 规则链执行前置回调（主流程真正调度前触发）
     *
     * @param chainId 规则链标识
     * @param param   流程入参（可能为 {@code null}）
     */
    default void beforeExecute(String chainId, Object param) {
    }

    /**
     * 规则链执行成功完成后的后置回调
     *
     * @param chainId 规则链标识
     * @param param   流程入参（可能为 {@code null}）
     * @param trace   本次执行的完整结构化时序轨迹（可能包含慢执行诊断信息）
     */
    default void afterExecute(String chainId, Object param, LiteflowExecutionTrace trace) {
    }

    /**
     * 规则链执行异常或失败时的错误回调
     *
     * @param chainId 规则链标识
     * @param param   流程入参（可能为 {@code null}）
     * @param trace   失败时刻的结构化时序轨迹（底层调用直接异常中止时可能为 {@code null}）
     * @param error   触发失败的受管或原始异常实例
     */
    default void onError(String chainId, Object param, LiteflowExecutionTrace trace, Throwable error) {
    }
}
