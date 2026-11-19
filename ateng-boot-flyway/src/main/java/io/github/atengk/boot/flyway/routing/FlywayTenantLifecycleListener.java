package io.github.atengk.boot.flyway.routing;

import io.github.atengk.boot.datasource.listener.DynamicDatasourceLifecycleListener;
import io.github.atengk.boot.datasource.template.DynamicDatasourceTemplate;
import io.github.atengk.boot.flyway.config.FlywayConfigurationSupport;
import io.github.atengk.boot.flyway.enums.DynamicMountFailurePolicy;
import io.github.atengk.boot.flyway.enums.FlywayExecutionMode;
import io.github.atengk.boot.flyway.properties.AtengFlywayProperties;
import io.github.atengk.boot.flyway.spi.FlywayTargetResolver;
import io.github.atengk.boot.flyway.template.FlywayTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.util.Objects;

/**
 * 动态数据源挂载生命周期迁移监听器
 * <p>
 * 监听 {@link DynamicDatasourceTemplate} 运行期动态新增或差量同步挂载的物理数据源事件：
 * 对匹配租户路由规则或显式纳管配置的新增目标库自动触发建表迁移或只读校验，
 * 并在迁移失败时按 {@link DynamicMountFailurePolicy} 策略执行保留上报或同线程可重入摘除回滚。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-17
 */
public class FlywayTenantLifecycleListener implements DynamicDatasourceLifecycleListener {

    private static final Logger log = LoggerFactory.getLogger(FlywayTenantLifecycleListener.class);

    private final ObjectProvider<FlywayTemplate> flywayTemplateProvider;
    private final AtengFlywayProperties atengProperties;
    private final ObjectProvider<FlywayTargetResolver> targetResolverProvider;
    private final ObjectProvider<DynamicDatasourceTemplate> dynamicDatasourceTemplateProvider;

    /**
     * 构造动态数据源生命周期迁移监听器
     *
     * @param flywayTemplateProvider            迁移门面延迟提供者
     * @param atengProperties                   ateng-boot-flyway 配置属性
     * @param targetResolverProvider            目标路由器延迟提供者
     * @param dynamicDatasourceTemplateProvider 动态数据源门面延迟提供者
     */
    public FlywayTenantLifecycleListener(ObjectProvider<FlywayTemplate> flywayTemplateProvider,
                                         AtengFlywayProperties atengProperties,
                                         ObjectProvider<FlywayTargetResolver> targetResolverProvider,
                                         ObjectProvider<DynamicDatasourceTemplate> dynamicDatasourceTemplateProvider) {
        this.flywayTemplateProvider = Objects.requireNonNull(flywayTemplateProvider, "flywayTemplateProvider 不能为空");
        this.atengProperties = Objects.requireNonNull(atengProperties, "atengProperties 不能为空");
        this.targetResolverProvider = targetResolverProvider;
        this.dynamicDatasourceTemplateProvider = dynamicDatasourceTemplateProvider;
    }

    @Override
    public void onDataSourceAdded(String dsKey, DataSource dataSource) {
        if (!StringUtils.hasText(dsKey) || !atengProperties.isEnabled()) {
            return;
        }
        FlywayExecutionMode mode = atengProperties.getMode() != null
                ? atengProperties.getMode()
                : FlywayExecutionMode.MIGRATE;
        if (mode == FlywayExecutionMode.DISABLED) {
            log.debug("Flyway 执行模式为 DISABLED，跳过动态挂载数据源 [dsKey={}] 的自动迁移", dsKey);
            return;
        }
        AtengFlywayProperties.TenantMigrationProperties tenantProps = atengProperties.getTenant();
        if (tenantProps != null && !tenantProps.isMigrateOnDynamicAdd()) {
            log.debug("ateng.boot.flyway.tenant.migrate-on-dynamic-add=false，跳过动态挂载数据源 [dsKey={}]", dsKey);
            return;
        }

        String normalizedKey = dsKey.trim();
        if (!isEligibleForDynamicMigration(normalizedKey)) {
            log.debug("动态挂载数据源 [dsKey={}] 未命中租户路由或显式纳管列表，保持零入侵跳过", normalizedKey);
            return;
        }

        FlywayTemplate flywayTemplate = flywayTemplateProvider.getIfAvailable();
        if (flywayTemplate == null) {
            log.warn("FlywayTemplate 尚未就绪，无法对动态挂载数据源 [dsKey={}] 执行自动迁移", normalizedKey);
            return;
        }

        DynamicMountFailurePolicy failurePolicy = resolveFailurePolicy();
        boolean rollbackOnFailure = failurePolicy == DynamicMountFailurePolicy.ROLLBACK_DATASOURCE;
        try {
            flywayTemplate.executeDynamicMountTarget(normalizedKey, mode, rollbackOnFailure);
            log.info("动态挂载数据源 [dsKey={}] 自动 Flyway [{}] 执行成功", normalizedKey, mode);
        } catch (Exception ex) {
            handleMigrationFailure(normalizedKey, failurePolicy, ex);
        }
    }

    @Override
    public void onDataSourceRemoved(String dsKey) {
        if (!StringUtils.hasText(dsKey) || !atengProperties.isEnabled()) {
            return;
        }
        String normalizedKey = dsKey.trim();
        if (TargetRoutingSupport.isExcludedOrSlave(normalizedKey, atengProperties)) {
            return;
        }
        FlywayTemplate flywayTemplate = flywayTemplateProvider.getIfAvailable();
        if (flywayTemplate == null) {
            return;
        }
        boolean removed = flywayTemplate.removeTargetFromSummary(normalizedKey, true);
        if (removed) {
            log.info("动态数据源 [dsKey={}] 已从路由表摘除，已同步清理全局迁移摘要滞留记录并刷新观测缓存", normalizedKey);
        } else {
            log.debug("动态数据源 [dsKey={}] 已从路由表摘除，已递增加载观测摘要版本号", normalizedKey);
        }
    }

    private boolean isEligibleForDynamicMigration(String dsKey) {
        if (TargetRoutingSupport.isExcludedOrSlave(dsKey, atengProperties)) {
            return false;
        }
        FlywayTargetResolver resolver = targetResolverProvider != null ? targetResolverProvider.getIfAvailable() : null;
        return resolver != null && resolver.isDynamicMountManaged(dsKey);
    }

    private void handleMigrationFailure(String dsKey, DynamicMountFailurePolicy policy, Exception ex) {
        String safeMessage = FlywayConfigurationSupport.sanitizeSensitiveText(ex.getMessage());
        if (policy == DynamicMountFailurePolicy.ROLLBACK_DATASOURCE) {
            DynamicDatasourceTemplate dsTemplate = dynamicDatasourceTemplateProvider != null
                    ? dynamicDatasourceTemplateProvider.getIfAvailable()
                    : null;
            if (dsTemplate != null && !dsTemplate.isProtectedCoreKey(dsKey)) {
                try {
                    dsTemplate.removeDataSource(dsKey, true);
                    log.error("动态挂载数据源 [dsKey={}] Flyway 迁移失败，已按 ROLLBACK_DATASOURCE 策略从路由表安全摘除: {}",
                            dsKey, safeMessage);
                    return;
                } catch (Exception removeEx) {
                    log.error("动态挂载数据源 [dsKey={}] 迁移失败后执行回滚摘除异常: {}",
                            dsKey, FlywayConfigurationSupport.sanitizeSensitiveText(removeEx.getMessage()), removeEx);
                    return;
                }
            }
        }
        log.error("动态挂载数据源 [dsKey={}] Flyway 迁移失败，已按 KEEP_AND_REPORT 策略保留数据源并上报异常以便重试补迁: {}",
                dsKey, safeMessage);
    }

    private DynamicMountFailurePolicy resolveFailurePolicy() {
        AtengFlywayProperties.TenantMigrationProperties tenantProps = atengProperties.getTenant();
        return tenantProps != null && tenantProps.getDynamicMountFailurePolicy() != null
                ? tenantProps.getDynamicMountFailurePolicy()
                : DynamicMountFailurePolicy.KEEP_AND_REPORT;
    }
}
