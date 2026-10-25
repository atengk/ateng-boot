package io.github.atengk.boot.job.local.handler;

import io.github.atengk.boot.job.local.model.JobContext;

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
}
