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
 * 电商每日订单对账差错告警与平账自愈任务执行器
 *
 * <p>演示 SnailJob 三阶告警防风暴聚合状态机：
 * <ul>
 *     <li>对账产生差错时触发异常，聚合器首发立即发出高危预警；</li>
 *     <li>静默聚合窗口内持续失败自动收敛为摘要卡片，防止告警风暴；</li>
 *     <li>对账成功平账后自动触发绿色自愈通知，恢复健康态。</li>
 * </ul></p>
 *
 * @author Ateng
 * @since 2026-11-11
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderReconciliationJobHandler {

    private final SnailJobAlertAggregator alertAggregator;
    private final AtomicBoolean faultInjected = new AtomicBoolean(false);

    /**
     * 电商订单对账任务：可按需注入差错故障演练告警自愈
     */
    @JobExecutor(name = "orderReconciliationJob")
    public ExecuteResult orderReconciliationJob(JobArgs args) {
        String jobName = "orderReconciliationJob";
        String param = args != null && args.getJobParams() != null ? String.valueOf(args.getJobParams()) : "";

        if (faultInjected.get() || "FAIL".equalsIgnoreCase(param)) {
            log.warn("[OrderReconciliationJobHandler] 订单对账演练任务注入差错故障触发失败");
            alertAggregator.onJobFailure(jobName, param, null, 150L, new IllegalStateException("Simulated bank reconciliation timeout error"));
            return ExecuteResult.failure("Simulated reconciliation mismatch occurred");
        }

        // 成功分支：平账并记录自愈
        alertAggregator.onJobSuccess(jobName);
        log.info("[OrderReconciliationJobHandler] 订单对账演练任务执行成功，账目平账并记录自愈现场");
        return ExecuteResult.success("Order reconciliation job succeeded and balanced");
    }

    public void setFaultInjected(boolean fault) {
        this.faultInjected.set(fault);
    }

    public boolean isFaultInjected() {
        return this.faultInjected.get();
    }
}
