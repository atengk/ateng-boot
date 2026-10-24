package io.github.atengk.boot.datasource.routing;

import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.baomidou.dynamic.datasource.provider.DynamicDataSourceProvider;
import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.datasource.enums.MultiTenancyMode;
import io.github.atengk.boot.datasource.exception.TenantDataSourceNotFoundException;
import io.github.atengk.boot.datasource.properties.DatasourceProperties;
import io.github.atengk.boot.datasource.tenant.TenantDataSourceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.util.List;

/**
 * 具备 SaaS 物理多租户透明切库能力的动态路由数据源
 *
 * @author Ateng
 * @since 2026-10-24
 */
public class AtengDynamicRoutingDataSource extends DynamicRoutingDataSource {

    private static final Logger log = LoggerFactory.getLogger(AtengDynamicRoutingDataSource.class);

    private final DatasourceProperties datasourceProperties;
    private final TenantDataSourceProvider tenantDataSourceProvider;

    public AtengDynamicRoutingDataSource(List<DynamicDataSourceProvider> providers,
                                        DatasourceProperties datasourceProperties,
                                        TenantDataSourceProvider tenantDataSourceProvider) {
        super(providers);
        this.datasourceProperties = datasourceProperties != null ? datasourceProperties : new DatasourceProperties();
        this.tenantDataSourceProvider = tenantDataSourceProvider;
    }

    @Override
    public DataSource determineDataSource() {
        // 1. 显式优先级最高：当前线程已通过 @DS 或编程式门面显式指定数据源 Key
        String explicitKey = DynamicDataSourceContextHolder.peek();
        if (StringUtils.hasText(explicitKey)) {
            return getDataSource(explicitKey);
        }

        // 2. 物理多租户全自动透明切源
        DatasourceProperties.MultiTenancyProperties multiTenancy = datasourceProperties.getMultiTenancy();
        String currentTenantId = TenantContextHolder.getTenantId();
        if (multiTenancy.isEnabled() && StringUtils.hasText(currentTenantId)) {
            // 免租户标记或 IGNORE 模式直接路由至默认主库
            if (TenantContextHolder.isIgnoreTenant() || multiTenancy.getMode() == MultiTenancyMode.IGNORE) {
                log.debug("检测到免租户标记 (isIgnoreTenant=true) 或 IGNORE 模式，路由至默认主库: [{}]", multiTenancy.getDefaultDataSource());
                return resolveDefaultOrPrimary(multiTenancy.getDefaultDataSource());
            }

            String tenantDsKey = tenantDataSourceProvider != null
                    ? tenantDataSourceProvider.resolveDataSourceKey(currentTenantId)
                    : (multiTenancy.getDataSourcePrefix() != null ? multiTenancy.getDataSourcePrefix() : "") + currentTenantId;

            if (StringUtils.hasText(tenantDsKey)) {
                // 目标独立物理库已注册
                if (getDataSources().containsKey(tenantDsKey) || getGroupDataSources().containsKey(tenantDsKey)) {
                    log.debug("租户物理分库透明切源命中: tenantId=[{}], dsKey=[{}]", currentTenantId, tenantDsKey);
                    return getDataSource(tenantDsKey);
                }

                // 未匹配到独立物理库，执行隔离策略决策
                MultiTenancyMode mode = multiTenancy.getMode();
                if (mode == MultiTenancyMode.STRICT) {
                    throw new TenantDataSourceNotFoundException(currentTenantId, tenantDsKey);
                }
                if (mode == MultiTenancyMode.FALLBACK_MASTER) {
                    log.debug("租户 [{}] 未分配独立物理库 [{}]，FALLBACK_MASTER 平滑回退主库 [{}]",
                            currentTenantId, tenantDsKey, multiTenancy.getDefaultDataSource());
                    return resolveDefaultOrPrimary(multiTenancy.getDefaultDataSource());
                }
            }
        }

        // 3. 默认路由至主数据源（基于 Baomidou 原生 primary 机制）
        return super.determineDataSource();
    }

    /**
     * 解析默认主数据源，若未配置或不存在则安全回退至 Baomidou primary 数据源
     */
    private DataSource resolveDefaultOrPrimary(String defaultDataSourceKey) {
        if (StringUtils.hasText(defaultDataSourceKey)
                && (getDataSources().containsKey(defaultDataSourceKey) || getGroupDataSources().containsKey(defaultDataSourceKey))) {
            return getDataSource(defaultDataSourceKey);
        }
        return super.determineDataSource();
    }
}
