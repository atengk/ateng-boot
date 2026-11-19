package io.github.atengk.boot.job.local.interceptor;

import io.github.atengk.boot.job.local.model.JobContext;
import io.github.atengk.boot.job.local.model.JobResult;
import org.springframework.core.Ordered;

/**
 * 单机定时任务三阶段切面责任链拦截器 SPI
 * <p>
 * 在任务通过重叠并发策略与轻量集群互斥锁门禁后、进入单次或全租户遍历执行外层生效，
 * 提供前置拦截判定（{@link #preHandle}）、执行后置处理（{@link #postHandle}）与终态逆序清理（{@link #afterCompletion}）。
 * 多个拦截器按 Spring {@link Ordered} 从小到大正序执行 {@code preHandle}/{@code postHandle}，
 * 并在 {@code finally} 中按严格逆序对已通过前置校验的拦截器执行 {@code afterCompletion}。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-19
 */
public interface LocalJobInterceptor extends Ordered {

    /**
     * 获取拦截器执行优先级（数值越小越先执行）
     *
     * @return 优先级顺序值，默认为 {@code 0}
     */
    @Override
    default int getOrder() {
        return 0;
    }

    /**
     * 任务执行前置拦截判定
     * <p>
     * 若返回 {@code false}，将立即阻断后续拦截器及任务本体执行，
     * 记录 {@code DISCARDED} 状态并发布 {@code JobDiscardedEvent(DiscardReason.INTERCEPTOR_REJECTED)}，
     * 同时对此前已通过的拦截器按逆序执行 {@link #afterCompletion}。
     * </p>
     *
     * @param context 本次调度运行时上下文
     * @return true 允许继续调度；false 拦截跳过本次执行
     * @throws Exception 前置校验异常
     */
    default boolean preHandle(JobContext context) throws Exception {
        return true;
    }

    /**
     * 任务正常或降级完成后后置处理（正序执行）
     *
     * @param context 本次调度运行时上下文
     * @param result  本次执行（含超时降级兜底）产出的结果摘要对象
     * @throws Exception 后置处理异常
     */
    default void postHandle(JobContext context, JobResult result) throws Exception {
    }

    /**
     * 任务调度终态清理回调（严格逆序执行，且仅针对 {@link #preHandle} 已通过的拦截器）
     * <p>
     * 单个拦截器清理抛出的异常将被引擎隔离记录，不会阻断其余上游拦截器的清理执行。
     * </p>
     *
     * @param context 本次调度运行时上下文
     * @param result  执行结果产物（若未生成或发生未降级异常则为 null）
     * @param ex      执行期间抛出的未捕获异常（正常完成或已降级完成时为 null）
     * @throws Exception 清理异常
     */
    default void afterCompletion(JobContext context, JobResult result, Throwable ex) throws Exception {
    }
}
