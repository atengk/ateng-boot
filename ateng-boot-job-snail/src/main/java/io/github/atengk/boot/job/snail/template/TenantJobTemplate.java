package io.github.atengk.boot.job.snail.template;

import com.aizuda.snailjob.common.log.SnailJobLog;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.job.snail.tenant.TenantListProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * SnailJob 多租户隔离遍历执行模板
 *
 * <p>为分布式调度任务提供安全、沙箱化的租户级批量执行能力（对应 {@link io.github.atengk.boot.job.snail.enums.JobTenantMode#ITERATE} 模式）。
 * 自动在每个有效租户上下文中封装调用 {@link TenantContextHolder#runWithTenant(String, Runnable)}，
 * 确保各租户数据操作完全隔离，并在异常或完成时确定性恢复原始现场，彻底杜绝租户数据串写与越权。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class TenantJobTemplate {

    private static final Logger log = LoggerFactory.getLogger(TenantJobTemplate.class);

    private final TenantListProvider tenantListProvider;
    private final ObjectProvider<PlatformTransactionManager> transactionManagerProvider;

    public TenantJobTemplate() {
        this(null, null);
    }

    public TenantJobTemplate(TenantListProvider tenantListProvider) {
        this(tenantListProvider, null);
    }

    public TenantJobTemplate(TenantListProvider tenantListProvider,
                             ObjectProvider<PlatformTransactionManager> transactionManagerProvider) {
        this.tenantListProvider = tenantListProvider;
        this.transactionManagerProvider = transactionManagerProvider;
    }

    /**
     * 从注册的 {@link TenantListProvider} 获取有效租户列表并执行租户遍历处理（容错继续模式）
     *
     * @param action 租户执行回调动作
     * @return 批处理执行统计结果
     */
    public BatchExecutionResult executeForEachTenant(Consumer<String> action) {
        return executeForEachTenant(resolveTenantIds(), false, action);
    }

    /**
     * 指定租户集合执行遍历处理（容错继续模式）
     *
     * @param tenantIds 租户标识集合
     * @param action    租户执行回调动作
     * @return 批处理执行统计结果
     */
    public BatchExecutionResult executeForEachTenant(Collection<String> tenantIds, Consumer<String> action) {
        return executeForEachTenant(tenantIds, false, action);
    }

    /**
     * 指定租户集合执行遍历处理
     *
     * @param tenantIds 租户标识集合
     * @param failFast  是否快速失败（true 表示任一租户报错即中断，false 表示记录异常并继续处理其他租户）
     * @param action    租户执行回调动作
     * @return 批处理执行统计结果
     */
    public BatchExecutionResult executeForEachTenant(Collection<String> tenantIds, boolean failFast, Consumer<String> action) {
        Objects.requireNonNull(action, "action 不能为 null");
        if (tenantIds == null || tenantIds.isEmpty()) {
            log.info("[TenantJobTemplate] 无有效租户标识，跳过租户遍历执行");
            return new BatchExecutionResult(0, 0, 0, 0, Duration.ZERO);
        }

        long startTime = System.currentTimeMillis();
        long total = tenantIds.size();
        long successCount = 0;
        long failCount = 0;
        List<String> failedTenants = new ArrayList<>();

        log.info("[TenantJobTemplate] 开始执行多租户遍历任务，共 {} 个租户，failFast={}", total, failFast);
        try {
            SnailJobLog.LOCAL.info("[TENANT-JOB] 开始执行多租户遍历任务 (租户数: {}, failFast: {})", total, failFast);
        } catch (Throwable ignored) {
        }

        int currentIndex = 0;
        for (String tenantId : tenantIds) {
            currentIndex++;
            if (tenantId == null || tenantId.isBlank()) {
                continue;
            }

            log.info("[TenantJobTemplate] [{}/{}] 正在执行租户 [{}] 的调度任务...", currentIndex, total, tenantId);
            try {
                runInTenantSandbox(tenantId, action);
                successCount++;
            } catch (Throwable t) {
                failCount++;
                failedTenants.add(tenantId);

                if (failFast) {
                    throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                            "多租户遍历任务在租户 [" + tenantId + "] 快速失败中断: " + t.getMessage(), t);
                }

                log.error("[TenantJobTemplate] 租户 [{}] 调度任务执行失败: {}", tenantId, t.getMessage(), t);
                try {
                    SnailJobLog.LOCAL.error("[TENANT-JOB] 租户 [{}] 执行失败: {}", tenantId, t.getMessage(), t);
                } catch (Throwable ignored) {
                }
            }
        }

        Duration duration = Duration.ofMillis(System.currentTimeMillis() - startTime);
        log.info("[TenantJobTemplate] 多租户遍历任务执行完毕，总数: {}, 成功: {}, 失败: {}, 耗时: {}ms",
                total, successCount, failCount, duration.toMillis());
        try {
            SnailJobLog.LOCAL.info("[TENANT-JOB] 租户遍历完成 (总数: {}, 成功: {}, 失败: {}, 耗时: {}ms)",
                    total, successCount, failCount, duration.toMillis());
        } catch (Throwable ignored) {
        }

        return new BatchExecutionResult(total, successCount, failCount, (int) total, duration);
    }

    /**
     * 在隔离租户沙箱中执行任务，若存在受管事务管理器，则自动包装为独立的 PROPAGATION_REQUIRES_NEW 事务沙箱
     *
     * @param tenantId 租户标识
     * @param action   业务动作
     */
    protected void runInTenantSandbox(String tenantId, Consumer<String> action) {
        TenantContextHolder.runWithTenant(tenantId, () -> {
            PlatformTransactionManager txManager = (transactionManagerProvider != null)
                    ? transactionManagerProvider.getIfAvailable() : null;
            if (txManager != null) {
                TransactionTemplate txTemplate = new TransactionTemplate(txManager);
                txTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                txTemplate.executeWithoutResult(status -> action.accept(tenantId));
            } else {
                action.accept(tenantId);
            }
        });
    }

    /**
     * 获取租户列表，无 Provider 时保底返回空列表
     *
     * @return 租户标识列表
     */
    protected List<String> resolveTenantIds() {
        if (tenantListProvider == null) {
            return Collections.emptyList();
        }
        List<String> list = tenantListProvider.getTenantIds();
        return list != null ? list : Collections.emptyList();
    }
}
