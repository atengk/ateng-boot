package io.github.atengk.boot.sample.job.xxl.handler;

import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.job.xxl.annotation.AtengXxlJob;
import io.github.atengk.boot.job.xxl.enums.JobTenantMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 多租户隔离模式任务样例
 *
 * @author Ateng
 * @since 2026-10-31
 */
@Component
public class TenantJobHandler {

    private static final Logger log = LoggerFactory.getLogger(TenantJobHandler.class);

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
}
