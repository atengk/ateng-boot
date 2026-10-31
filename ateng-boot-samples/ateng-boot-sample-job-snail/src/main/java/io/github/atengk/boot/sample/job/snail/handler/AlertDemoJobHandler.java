package io.github.atengk.boot.sample.job.snail.handler;

import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import io.github.atengk.boot.job.snail.alert.SnailJobAlertAggregator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 告警防风暴与三阶自愈演练任务执行器
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AlertDemoJobHandler {

    private final SnailJobAlertAggregator alertAggregator;
    private final AtomicBoolean faultInjected = new AtomicBoolean(false);

    /**
     * 演练任务：可注入故障并配合聚合器触发首发告警/抑制聚合/自愈恢复
     */
    @JobExecutor(name = "alertDemoJob")
    public ExecuteResult alertDemoJob(JobArgs args) {
        String jobName = "alertDemoJob";
        String param = args != null && args.getJobParams() != null ? String.valueOf(args.getJobParams()) : "";

        if (faultInjected.get() || "FAIL".equalsIgnoreCase(param)) {
            log.warn("[AlertDemoJobHandler] 演练任务注入故障触发失败");
            alertAggregator.onJobFailure(jobName, param, null, 150L, new IllegalStateException("Mock simulated database timeout"));
            return ExecuteResult.failure("Mock failure occurred");
        }

        // 成功分支：记录自愈
        alertAggregator.onJobSuccess(jobName);
        log.info("[AlertDemoJobHandler] 演练任务执行成功并记录自愈现场");
        return ExecuteResult.success("Alert demo job succeeded");
    }

    public void setFaultInjected(boolean fault) {
        this.faultInjected.set(fault);
    }

    public boolean isFaultInjected() {
        return this.faultInjected.get();
    }
}
