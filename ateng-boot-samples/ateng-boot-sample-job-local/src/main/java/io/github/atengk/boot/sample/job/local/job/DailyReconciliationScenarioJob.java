package io.github.atengk.boot.sample.job.local.job;

import io.github.atengk.boot.job.local.annotation.LocalJob;
import io.github.atengk.boot.job.local.model.JobContext;
import io.github.atengk.boot.job.local.model.JobResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 每日财务与订单对账业务场景任务
 * <p>
 * 演示特性：Cron 表达式调度、JobContext 结构化日志追踪注入与强类型 JobResult 返回。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-01
 */
@Component
public class DailyReconciliationScenarioJob {

    private static final Logger log = LoggerFactory.getLogger(DailyReconciliationScenarioJob.class);

    private final AtomicInteger executionCount = new AtomicInteger();

    /**
     * 执行每日定时财务对账业务
     *
     * @param context 任务上下文
     * @return 强类型任务执行结果
     */
    @LocalJob(name = "dailyReconciliationJob", description = "每日定时财务对账 Cron 任务", cron = "0 0 2 * * ?")
    public JobResult executeDailyReconciliation(JobContext context) {
        int count = executionCount.incrementAndGet();
        context.log("开始执行第 [" + count + "] 次对账数据巡检...");
        context.log("对账批次加载完成，匹配记录数: 1200 条");
        log.info("dailyReconciliationJob executed successfully, traceId={}", context.getTraceId());
        return JobResult.success("对账完成，匹配成功: 1200 条，异常 0 条");
    }

    /**
     * 获取累计执行次数
     *
     * @return 累计执行次数
     */
    public int getExecutionCount() {
        return executionCount.get();
    }
}
