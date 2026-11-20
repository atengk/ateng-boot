package io.github.atengk.boot.sample.job.snail.handler;

import com.aizuda.snailjob.client.job.core.annotation.JobExecutor;
import com.aizuda.snailjob.client.job.core.dto.JobArgs;
import com.aizuda.snailjob.model.dto.ExecuteResult;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.job.snail.annotation.AtengSnailJob;
import io.github.atengk.boot.job.snail.enums.JobTenantMode;
import io.github.atengk.boot.job.snail.template.BatchExecutionResult;
import io.github.atengk.boot.job.snail.template.TenantJobTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 多租户隔离调度任务示例
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TenantJobHandler {

    private final TenantJobTemplate tenantJobTemplate;

    /**
     * 全局归档任务：忽略多租户上下文，全局扫描
     */
    @AtengSnailJob(tenantMode = JobTenantMode.IGNORE)
    @JobExecutor(name = "globalArchiveJob")
    public ExecuteResult globalArchiveJob() {
        boolean ignoreTenant = TenantContextHolder.isIgnoreTenant();
        log.info("[TenantJobHandler] 执行全局归档任务, 忽略租户标记={}", ignoreTenant);
        return ExecuteResult.success("Global archive finished, ignoreTenant=" + ignoreTenant);
    }

    /**
     * 单租户限定任务：入参或请求头绑定当前租户
     */
    @AtengSnailJob(tenantMode = JobTenantMode.PARAM)
    @JobExecutor(name = "tenantScopedJob")
    public ExecuteResult tenantScopedJob(JobArgs args) {
        String currentTenant = TenantContextHolder.getTenantId();
        log.info("[TenantJobHandler] 执行租户专属任务, 租户ID: {}", currentTenant);
        return ExecuteResult.success("Tenant job finished for tenant=" + currentTenant);
    }

    /**
     * 租户批量遍历任务：按租户沙箱隔离逐一遍历执行
     */
    @AtengSnailJob(tenantMode = JobTenantMode.ITERATE)
    @JobExecutor(name = "tenantBatchJob")
    public ExecuteResult tenantBatchJob() {
        List<String> tenants = List.of("tenant-alpha", "tenant-beta");
        log.info("[TenantJobHandler] 开始批量遍历执行租户任务: {}", tenants);

        BatchExecutionResult result = tenantJobTemplate.executeForEachTenant(tenants, false, tenantId -> {
            log.info("[TenantJobHandler] 正在沙箱执行租户 [{}] 的结算逻辑, 当前上下文租户={}",
                    tenantId, TenantContextHolder.getTenantId());
        });

        return ExecuteResult.success("Tenant batch finished: total=" + result.totalCount() + ", success=" + result.successCount());
    }

    /**
     * 独立 @AtengSnailJob 多租户并行背压执行示例（Semaphore 限流 + 全链路上下文透传 + BatchFailureHandler）
     */
    @AtengSnailJob(name = "tenantParallelBatchJob", tenantMode = JobTenantMode.ITERATE)
    public ExecuteResult tenantParallelBatchJob(JobArgs args) {
        String param = args != null && args.getJobParams() != null ? String.valueOf(args.getJobParams()) : "";
        boolean injectTenantFail = "FAIL_ONE".equalsIgnoreCase(param);
        List<String> tenants = injectTenantFail
                ? List.of("tenant-alpha", "tenant-bad", "tenant-gamma")
                : List.of("tenant-alpha", "tenant-beta", "tenant-gamma");

        BatchExecutionResult<String> result = tenantJobTemplate.executeForEachTenantParallel(
                tenants,
                2,
                false,
                tenantId -> {
                    log.info("[TenantJobHandler] 并行执行租户 [{}] 任务, 上下文租户={}",
                            tenantId, TenantContextHolder.getTenantId());
                    if (injectTenantFail && "tenant-bad".equals(tenantId)) {
                        throw new IllegalStateException("模拟并发多租户单租户异常: " + tenantId);
                    }
                },
                failed -> log.warn("[TenantJobHandler] 捕获并发租户失败回调: tenant={}, reason={}",
                        failed.cursorStart(), failed.getCause() != null ? failed.getCause().getMessage() : "unknown")
        );

        return ExecuteResult.success("Tenant parallel finished: total=" + result.totalCount()
                + ", success=" + result.successCount() + ", fail=" + result.failCount());
    }
}
