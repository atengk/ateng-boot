package io.github.atengk.boot.job.local.handler;

import io.github.atengk.boot.job.local.exception.JobTimeoutException;
import io.github.atengk.boot.job.local.model.JobContext;
import io.github.atengk.boot.job.local.model.JobResult;

/**
 * 任务执行处理函数式契约
 * <p>
 * 声明式方法与编程式任务均统一适配为此契约进行统一调度与生命周期拦截。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-25
 */
@FunctionalInterface
public interface JobHandler {

    /**
     * 执行具体任务逻辑
     *
     * @param context 任务运行时执行上下文
     * @return 业务产物摘要或结果（可为 null、String 或 JobResult）
     * @throws Exception 业务执行异常
     */
    Object execute(JobContext context) throws Exception;

    /**
     * 执行硬超时降级补偿（可选扩展钩子，默认返回 {@code null} 表示未提供 Handler 级降级）
     *
     * @param context 任务运行时执行上下文
     * @param ex      触发熔断的硬超时异常
     * @return 降级补偿结果；返回 {@code null} 时由调度引擎继续按全局 SPI 或原异常路径处理
     */
    default JobResult executeTimeoutFallback(JobContext context, JobTimeoutException ex) {
        return null;
    }
}
