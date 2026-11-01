package io.github.atengk.boot.sample.job.local.job;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.job.local.annotation.LocalJob;
import io.github.atengk.boot.job.local.enums.JobTenantMode;
import io.github.atengk.boot.job.local.model.JobContext;
import io.github.atengk.boot.job.local.model.JobResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

/**
 * SaaS 多租户账单自动化结算与出账业务场景任务
 * <p>
 * 演示特性：{@link JobTenantMode#ITERATE} 全租户轮询隔离、租户上下文自动切换与单租户异常 Fail-Safe 容错。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-01
 */
@Component
public class MultiTenantBillingScenarioJob {

    private static final Logger log = LoggerFactory.getLogger(MultiTenantBillingScenarioJob.class);

    private final List<String> executedTenants = new CopyOnWriteArrayList<>();
    private final AtomicReference<String> simulatedFailedTenant = new AtomicReference<>(null);

    /**
     * 针对当前遍历到的租户执行账单核算
     *
     * @param context 任务执行上下文（承载当前租户 tenantId）
     * @return 针对该租户的核算结果
     */
    @LocalJob(
            name = "multiTenantBillingJob",
            description = "多租户账单自动化结算与出账任务",
            tenantMode = JobTenantMode.ITERATE,
            fixedDelay = "1h",
            initialDelay = "1h"
    )
    public JobResult executeMultiTenantBilling(JobContext context) {
        String tenantId = context.getTenantId();
        String currentHolderTenant = TenantContextHolder.getTenantId();
        executedTenants.add(tenantId);
        context.log("正在为租户 [" + tenantId + "] (Holder: " + currentHolderTenant + ") 执行账单核算...");

        String failedTenant = simulatedFailedTenant.get();
        if (failedTenant != null && failedTenant.equals(tenantId)) {
            log.warn("MultiTenantBillingScenarioJob: 模拟租户 [{}] 账单扣款异常", tenantId);
            throw new IllegalStateException("租户 [" + tenantId + "] 账单账户余额不足，触发容错隔离");
        }

        context.log("租户 [" + tenantId + "] 账单生成完毕，出账金额 88.00 元");
        log.info("multiTenantBillingJob executed for tenant={}, traceId={}", tenantId, context.getTraceId());
        return JobResult.success("租户 [" + tenantId + "] 账单核算成功");
    }

    /**
     * 设置待模拟失败的租户标识
     *
     * @param tenantId 租户标识
     */
    public void setSimulatedFailedTenant(String tenantId) {
        this.simulatedFailedTenant.set(tenantId);
    }

    /**
     * 获取本次调度的已执行租户清单
     *
     * @return 租户标识列表
     */
    public List<String> getExecutedTenants() {
        return executedTenants;
    }

    /**
     * 清理已执行租户记录
     */
    public void clearExecutedTenants() {
        executedTenants.clear();
    }
}
