package io.github.atengk.boot.job.local.fallback;

import io.github.atengk.boot.job.local.exception.JobTimeoutException;
import io.github.atengk.boot.job.local.model.JobContext;
import io.github.atengk.boot.job.local.model.JobResult;

/**
 * 单机定时任务硬超时全局降级补偿处理器 SPI
 * <p>
 * 当任务触发硬超时强中断且退避重试耗尽后，若任务方法本身未通过
 * {@code @LocalJob(fallbackMethod = "...")} 声明方法级降级补偿，
 * 调度引擎将在记录 {@code TIMEOUT} 状态并广播 {@code JobTimeoutEvent} 后委托此全局 Bean 执行降级补偿。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-19
 */
@FunctionalInterface
public interface LocalJobTimeoutFallbackHandler {

    /**
     * 执行超时降级补偿并返回兜底结果
     *
     * @param context   本次调度运行时上下文
     * @param exception 触发熔断的硬超时异常
     * @return 降级兜底结果产物（引擎会将结果摘要写入 TIMEOUT 审计记录并平滑返回）
     */
    JobResult handleTimeoutFallback(JobContext context, JobTimeoutException exception);
}
