package io.github.atengk.boot.job.xxl.template;

import com.xxl.job.core.context.XxlJobHelper;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.job.xxl.tenant.TenantListProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * XXL-Job 多租户隔离遍历执行模板
 *
 * <p>为分布式调度任务提供安全、沙箱化的租户级批量执行能力（对应 {@link io.github.atengk.boot.job.xxl.enums.JobTenantMode#ITERATE} 模式）。
 * 自动在每个有效租户上下文中封装调用 {@link TenantContextHolder#runWithTenant(String, Runnable)}，
 * 确保各租户数据操作完全隔离，并在异常或完成时确定性恢复原始现场，彻底杜绝租户数据串写与越权。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
public class TenantJobTemplate {

    private static final Logger log = LoggerFactory.getLogger(TenantJobTemplate.class);

    private final TenantListProvider tenantListProvider;

    public TenantJobTemplate(TenantListProvider tenantListProvider) {
        this.tenantListProvider = tenantListProvider;
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
        Objects.requireNonNull(action, "action 不能为空");
        if (tenantIds == null || tenantIds.isEmpty()) {
            log.info("[TenantJobTemplate] 无有效租户标识，跳过租户遍历执行");
            return new BatchExecutionResult(0, 0, 0, 0, Duration.ZERO);
        }

        long startTime = System.currentTimeMillis();
        long total = tenantIds.size();
        long successCount = 0;
        long failCount = 0;
        List<String> failedTenants = new ArrayList<>();
        Throwable firstError = null;

        log.info("[TenantJobTemplate] 开始执行多租户遍历任务，共 {} 个租户，failFast={}", total, failFast);
        try {
            XxlJobHelper.log("<br>----------- [TENANT-JOB] 开始执行多租户遍历任务 (租户数: " + total + ", failFast: " + failFast + ") -----------");
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
                TenantContextHolder.runWithTenant(tenantId, () -> action.accept(tenantId));
                successCount++;
            } catch (Throwable t) {
                failCount++;
                failedTenants.add(tenantId);
                log.error("[TenantJobTemplate] 租户 [{}] 调度任务执行失败: {}", tenantId, t.getMessage(), t);
                try {
                    XxlJobHelper.log("<font color=\"red\">[TENANT-JOB] 租户 [" + tenantId + "] 执行失败: " + t.getMessage() + "</font>");
                } catch (Throwable ignored) {
                }

                if (firstError == null) {
                    firstError = t;
                }
                if (failFast) {
                    Duration duration = Duration.ofMillis(System.currentTimeMillis() - startTime);
                    throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                            "多租户遍历任务在租户 [" + tenantId + "] 快速失败中断: " + t.getMessage(), t);
                }
            }
        }

        Duration duration = Duration.ofMillis(System.currentTimeMillis() - startTime);
        log.info("[TenantJobTemplate] 多租户遍历任务执行完毕，总数: {}, 成功: {}, 失败: {}, 耗时: {}ms",
                total, successCount, failCount, duration.toMillis());
        try {
            String summaryColor = (failCount == 0) ? "green" : "red";
            XxlJobHelper.log("<br><font color=\"" + summaryColor + "\">----------- [TENANT-JOB] 租户遍历完成 (总数: " +
                    total + ", 成功: " + successCount + ", 失败: " + failCount + ", 耗时: " + duration.toMillis() + "ms) -----------</font>");
            if (failCount > 0) {
                XxlJobHelper.handleFail("多租户遍历存在失败租户: " + failedTenants);
            }
        } catch (Throwable ignored) {
        }

        return new BatchExecutionResult(total, successCount, failCount, (int) total, duration);
    }

    /**
     * 获取租户列表，无 Provider 时保底返回空列表
     */
    protected List<String> resolveTenantIds() {
        if (tenantListProvider == null) {
            return Collections.emptyList();
        }
        List<String> list = tenantListProvider.getTenantIds();
        return list != null ? list : Collections.emptyList();
    }

}
