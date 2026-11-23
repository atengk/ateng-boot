package io.github.atengk.boot.sample.job.xxl.handler;

import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.job.xxl.annotation.AtengXxlJob;
import io.github.atengk.boot.job.xxl.enums.JobTenantMode;
import io.github.atengk.boot.job.xxl.template.BatchExecutionResult;
import io.github.atengk.boot.job.xxl.template.TenantJobTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 多租户隔离模式任务样例
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Component
public class TenantJobHandler {

    private static final Logger log = LoggerFactory.getLogger(TenantJobHandler.class);

    private final TenantJobTemplate tenantJobTemplate;
    private final List<String> parallelProcessedTenants = new CopyOnWriteArrayList<>();

    public TenantJobHandler(TenantJobTemplate tenantJobTemplate) {
        this.tenantJobTemplate = tenantJobTemplate;
    }

    public List<String> getParallelProcessedTenants() {
        return parallelProcessedTenants;
    }

    public void clearParallelProcessedTenants() {
        parallelProcessedTenants.clear();
    }

    /**
     * 忽略租户任务：用于跨租户全局归档与数据统计
     */
    @XxlJob("globalArchiveJob")
    @AtengXxlJob(value = "globalArchiveJob", tenantMode = JobTenantMode.IGNORE)
    public void executeGlobalArchive() {
        boolean ignoreTenant = TenantContextHolder.isIgnoreTenant();
        log.info(">>>>>> [TenantJobHandler] 执行全局归档任务, ignoreTenant: {}", ignoreTenant);
        XxlJobHelper.handleSuccess("全局归档成功, ignoreTenant=" + ignoreTenant);
    }

    /**
     * 参数绑定租户任务：自动从任务入参 (JSON 或键值对) 中提取 tenantId 并绑定至当前上下文
     */
    @XxlJob("tenantScopedJob")
    @AtengXxlJob(value = "tenantScopedJob", tenantMode = JobTenantMode.PARAM)
    public void executeTenantScoped() {
        String currentTenant = TenantContextHolder.getTenantId();
        log.info(">>>>>> [TenantJobHandler] 执行特定租户任务, 当前上下文 tenantId: {}", currentTenant);
        XxlJobHelper.handleSuccess("特定租户任务完成, 当前租户=" + currentTenant);
    }

    /**
     * 多租户并发遍历任务：使用 TenantJobTemplate 执行多租户独立沙箱并发遍历
     */
    @XxlJob("parallelTenantJob")
    @AtengXxlJob(value = "parallelTenantJob", tenantMode = JobTenantMode.ITERATE)
    public void executeParallelTenant() {
        parallelProcessedTenants.clear();
        log.info(">>>>>> [TenantJobHandler] 开始执行多租户并发遍历任务...");

        BatchExecutionResult result = tenantJobTemplate.executeForEachTenantParallel(tenantId -> {
            String currentTenant = TenantContextHolder.getTenantId();
            log.info(">>>>>> [TenantJobHandler] 并发处理租户: {}, 上下文租户: {}", tenantId, currentTenant);
            if (!Objects.equals(tenantId, currentTenant)) {
                throw new IllegalStateException("租户上下文不匹配: expected=" + tenantId + ", actual=" + currentTenant);
            }
            parallelProcessedTenants.add(currentTenant);
        });

        XxlJobHelper.handleSuccess(String.format("多租户并发遍历完成: 总数 %d, 成功 %d, 失败 %d",
                result.getTotalCount(), result.getSuccessCount(), result.getFailCount()));
    }
}
