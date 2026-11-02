package io.github.atengk.boot.job.snail.interceptor;

/**
 * SnailJob 调度任务与分布式切片三阶生命周期拦截器 SPI
 *
 * <p>为 SnailJob 任务执行提供前置拦截 (preHandle)、后置处理 (postHandle) 与终态清理 (afterCompletion) 扩展点。
 * 支持通过 Spring {@link org.springframework.core.annotation.Order} 或 {@link org.springframework.core.Ordered} 指定执行时序。</p>
 *
 * <p>核心契约：
 * <ul>
 *     <li><b>preHandle</b>：任务调度执行前触发。返回 {@code true} 允许继续执行下游拦截器与任务逻辑；
 *     返回 {@code false} 将中断下游链并优雅跳过任务，记录跳过日志并向调度中心返回成功；</li>
 *     <li><b>postHandle</b>：任务逻辑成功执行后触发（发生异常时不触发）；</li>
 *     <li><b>afterCompletion</b>：无论任务成功、发生异常或被下游中断，均在 {@code finally} 块中严格触发，执行逆序回调与资源清理。</li>
 * </ul></p>
 *
 * @author Ateng
 * @since 2026-11-02
 */
public interface SnailJobInterceptor {

    /**
     * 前置拦截处理
     *
     * @param context 任务执行上下文
     * @return true 允许继续执行下游拦截器与任务逻辑；false 优雅中断任务并跳过执行
     * @throws Exception 拦截器内部异常
     */
    default boolean preHandle(SnailJobExecutionContext context) throws Exception {
        return true;
    }

    /**
     * 后置拦截处理 (任务正常执行完成后调用)
     *
     * @param context 任务执行上下文
     * @param result  任务执行结果
     * @throws Exception 拦截器内部异常
     */
    default void postHandle(SnailJobExecutionContext context, Object result) throws Exception {
    }

    /**
     * 完成阶段拦截处理 (无论成功或异常均在 finally 逆序调用)
     *
     * @param context 任务执行上下文
     * @param result  任务执行结果 (若发生异常则为 null)
     * @param ex      执行过程中抛出的异常 (若成功执行则为 null)
     */
    default void afterCompletion(SnailJobExecutionContext context, Object result, Throwable ex) {
    }
}
