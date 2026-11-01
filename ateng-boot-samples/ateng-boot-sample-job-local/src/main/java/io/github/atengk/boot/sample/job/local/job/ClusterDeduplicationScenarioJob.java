package io.github.atengk.boot.sample.job.local.job;

import io.github.atengk.boot.job.local.annotation.LocalJob;
import io.github.atengk.boot.job.local.model.JobContext;
import io.github.atengk.boot.job.local.model.JobResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 分布式多节点集群防重抢占与互斥调度业务场景任务
 * <p>
 * 演示特性：集群互斥锁租约（`clusterLock = true`）、原子防误删与多实例防重抢占。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-01
 */
@Component
public class ClusterDeduplicationScenarioJob {

    private static final Logger log = LoggerFactory.getLogger(ClusterDeduplicationScenarioJob.class);

    private final AtomicInteger executionCount = new AtomicInteger();

    /**
     * 执行集群互斥去重任务
     *
     * @param context 任务上下文
     * @return 任务执行结果
     */
    @LocalJob(
            name = "clusterDeduplicationJob",
            description = "分布式多节点集群防重抢占与互斥调度任务",
            clusterLock = true,
            clusterLockLeaseTime = "1m",
            fixedDelay = "1h",
            initialDelay = "1h"
    )
    public JobResult executeClusterDeduplication(JobContext context) {
        int count = executionCount.incrementAndGet();
        context.log("成功抢占集群互斥分布式锁，执行分布式去重任务，当前轮次: " + count);
        log.info("clusterDeduplicationJob acquired cluster lock and running, count={}, traceId={}",
                count, context.getTraceId());
        return JobResult.success("集群防重互斥锁获取成功，第 [" + count + "] 轮全局唯一任务调度完成");
    }

    /**
     * 获取累计调度次数
     *
     * @return 调度次数
     */
    public int getExecutionCount() {
        return executionCount.get();
    }
}
