package io.github.atengk.boot.datasource.routing;

import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.baomidou.dynamic.datasource.ds.GroupDataSource;
import com.baomidou.dynamic.datasource.provider.DynamicDataSourceProvider;
import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import com.baomidou.dynamic.datasource.tx.TransactionContext;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.datasource.enums.MultiTenancyMode;
import io.github.atengk.boot.datasource.exception.TenantDataSourceNotFoundException;
import io.github.atengk.boot.datasource.properties.DatasourceProperties;
import io.github.atengk.boot.datasource.readwrite.DefaultWriteOccurredResolver;
import io.github.atengk.boot.datasource.readwrite.ReadAfterWriteManager;
import io.github.atengk.boot.datasource.strategy.HealthAwareDynamicDataSourceStrategy;
import io.github.atengk.boot.datasource.tenant.TenantDataSourceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

/**
 * 具备 SaaS 物理多租户透明切库、读写分离自动路由、主从故障自愈熔断与写后读主防延迟能力的受管动态路由数据源
 *
 * @author Ateng
 * @since 2026-10-24
 */
public class AtengDynamicRoutingDataSource extends DynamicRoutingDataSource {

    private static final Logger log = LoggerFactory.getLogger(AtengDynamicRoutingDataSource.class);

    private final DatasourceProperties datasourceProperties;
    private final TenantDataSourceProvider tenantDataSourceProvider;
    private final HealthAwareDynamicDataSourceStrategy healthAwareStrategy;
    private final ReadAfterWriteManager readAfterWriteManager;

    public AtengDynamicRoutingDataSource(List<DynamicDataSourceProvider> providers,
                                         DatasourceProperties datasourceProperties,
                                         TenantDataSourceProvider tenantDataSourceProvider,
                                         HealthAwareDynamicDataSourceStrategy healthAwareStrategy,
                                         ReadAfterWriteManager readAfterWriteManager) {
        super(providers);
        this.datasourceProperties = datasourceProperties != null ? datasourceProperties : new DatasourceProperties();
        this.tenantDataSourceProvider = tenantDataSourceProvider;
        this.healthAwareStrategy = healthAwareStrategy != null
                ? healthAwareStrategy
                : new HealthAwareDynamicDataSourceStrategy(this.datasourceProperties);
        this.readAfterWriteManager = readAfterWriteManager != null
                ? readAfterWriteManager
                : new ReadAfterWriteManager(this.datasourceProperties.getReadWriteSplit().getReadAfterWrite(), new DefaultWriteOccurredResolver());
    }

    @Override
    public String getPrimary() {
        return super.getPrimary();
    }

    public AtengDynamicRoutingDataSource(List<DynamicDataSourceProvider> providers,
                                         DatasourceProperties datasourceProperties,
                                         TenantDataSourceProvider tenantDataSourceProvider) {
        this(providers, datasourceProperties, tenantDataSourceProvider, null, null);
    }

    @Override
    public DataSource determineDataSource() {
        // 1. 显式优先级最高：当前线程已通过 @DS 或编程式门面显式指定数据源 Key
        String explicitKey = DynamicDataSourceContextHolder.peek();
        if (StringUtils.hasText(explicitKey)) {
            return resolveWithFailover(explicitKey);
        }

        DatasourceProperties.ReadWriteSplitProperties readWrite = datasourceProperties.getReadWriteSplit();
        String masterKey = StringUtils.hasText(readWrite.getMasterDataSource()) ? readWrite.getMasterDataSource() : "master";
        String slaveKey = StringUtils.hasText(readWrite.getSlaveDataSource()) ? readWrite.getSlaveDataSource() : "slave";

        // 2. 写后读主防延迟检查：若当前上下文（单请求或时间窗口内）发生过写操作，强制路由至主库
        if (readWrite.isEnabled() && readAfterWriteManager != null && readAfterWriteManager.isWriteOccurred()) {
            log.debug("写后读主 (Read-After-Write) 防延迟生效，强制只读查询路由至主库: [{}]", masterKey);
            return resolveWithFailover(masterKey);
        }

        // 3. 读写分离路由：检测当前是否处于只读事务中 (@Transactional(readOnly = true))
        if (readWrite.isEnabled() && TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            if (containsDataSource(slaveKey)) {
                log.debug("只读事务 (@Transactional(readOnly = true)) 自动路由至读从库组: [{}]", slaveKey);
                return resolveWithFailover(slaveKey);
            } else {
                log.debug("未找到读从库 [{}]，只读事务平滑回退主库: [{}]", slaveKey, masterKey);
                return resolveWithFailover(masterKey);
            }
        }

        // 4. 写事务路由：当前处于活动 Spring 事务中且非只读，强制绑定至主库并触发写标记
        if (readWrite.isEnabled() && TransactionSynchronizationManager.isActualTransactionActive()) {
            log.debug("写事务 (@Transactional) 自动绑定至主库: [{}]", masterKey);
            if (readAfterWriteManager != null) {
                readAfterWriteManager.markWriteOccurred();
            }
            return resolveWithFailover(masterKey);
        }

        // 5. 物理多租户全自动透明切源
        DatasourceProperties.MultiTenancyProperties multiTenancy = datasourceProperties.getMultiTenancy();
        String currentTenantId = TenantContextHolder.getTenantId();
        if (multiTenancy.isEnabled() && StringUtils.hasText(currentTenantId)) {
            // 免租户标记或 IGNORE 模式直接路由至默认主库
            if (TenantContextHolder.isIgnoreTenant() || multiTenancy.getMode() == MultiTenancyMode.IGNORE) {
                log.debug("检测到免租户标记 (isIgnoreTenant=true) 或 IGNORE 模式，路由至默认主库: [{}]", multiTenancy.getDefaultDataSource());
                return resolveWithFailover(multiTenancy.getDefaultDataSource());
            }

            String tenantDsKey = tenantDataSourceProvider != null
                    ? tenantDataSourceProvider.resolveDataSourceKey(currentTenantId)
                    : (multiTenancy.getDataSourcePrefix() != null ? multiTenancy.getDataSourcePrefix() : "") + currentTenantId;

            if (StringUtils.hasText(tenantDsKey)) {
                // 目标独立物理库已注册
                if (containsDataSource(tenantDsKey)) {
                    log.debug("租户物理分库透明切源命中: tenantId=[{}], dsKey=[{}]", currentTenantId, tenantDsKey);
                    return resolveWithFailover(tenantDsKey);
                }

                // 未匹配到独立物理库，执行隔离策略决策
                MultiTenancyMode mode = multiTenancy.getMode();
                if (mode == MultiTenancyMode.STRICT) {
                    throw new TenantDataSourceNotFoundException(currentTenantId, tenantDsKey);
                }
                if (mode == MultiTenancyMode.FALLBACK_MASTER) {
                    log.debug("租户 [{}] 未分配独立物理库 [{}]，FALLBACK_MASTER 平滑回退主库 [{}]",
                            currentTenantId, tenantDsKey, multiTenancy.getDefaultDataSource());
                    return resolveWithFailover(multiTenancy.getDefaultDataSource());
                }
            }
        }

        // 6. 默认路由至主数据源（基于 Baomidou 原生 primary 机制）
        return super.determineDataSource();
    }

    @Override
    public Connection getConnection() throws SQLException {
        String xid = TransactionContext.getXID();
        if (StringUtils.hasText(xid)) {
            return super.getConnection();
        }

        DataSource resolvedDataSource = determineDataSource();
        try {
            return resolvedDataSource.getConnection();
        } catch (SQLException e) {
            // 捕获物理连接异常并执行自愈熔断与故障转移
            if (healthAwareStrategy != null && datasourceProperties.getReadWriteSplit().getFailover().isEnabled()) {
                String failedKey = findDataSourceKey(resolvedDataSource);
                if (StringUtils.hasText(failedKey)) {
                    healthAwareStrategy.recordFailure(failedKey);
                    if (datasourceProperties.getReadWriteSplit().getFailover().isFallbackToMaster()) {
                        String masterKey = datasourceProperties.getReadWriteSplit().getMasterDataSource();
                        if (!Objects.equals(failedKey, masterKey)) {
                            log.warn("从库节点 [{}] 获取连接失败，高可用自愈降级获取主库 [{}] 连接", failedKey, masterKey, e);
                            return getDataSource(masterKey).getConnection();
                        }
                    }
                }
            }
            throw e;
        }
    }

    /**
     * 具备故障自愈熔断的分组与单数据源解析
     */
    public DataSource resolveWithFailover(String dsKey) {
        if (!containsDataSource(dsKey)) {
            return super.determineDataSource();
        }

        // 若是分组数据源 (GroupDataSource)
        if (getGroupDataSources().containsKey(dsKey)) {
            GroupDataSource groupDataSource = getGroupDataSources().get(dsKey);
            if (healthAwareStrategy != null && datasourceProperties.getReadWriteSplit().getFailover().isEnabled()) {
                List<String> nodeNames = new ArrayList<>(groupDataSource.getDataSourceMap().keySet());
                List<String> healthyNodes = healthAwareStrategy.filterHealthyNodes(nodeNames);
                if (healthyNodes.isEmpty()) {
                    String masterKey = datasourceProperties.getReadWriteSplit().getMasterDataSource();
                    log.warn("从库组 [{}] 全部从节点均处于熔断黑名单中，高可用保底自动降级路由至主库: [{}]", dsKey, masterKey);
                    return getDataSource(masterKey);
                }
                String selectedNodeKey = healthAwareStrategy.determineKey(healthyNodes);
                return groupDataSource.getDataSourceMap().get(selectedNodeKey);
            }
            return groupDataSource.determineDataSource();
        }

        // 若是单数据源
        return getDataSource(dsKey);
    }

    /**
     * 判断数据源 Key 是否已注册（支持单物理库与分组库）
     */
    public boolean containsDataSource(String dsKey) {
        if (!StringUtils.hasText(dsKey)) {
            return false;
        }
        return getDataSources().containsKey(dsKey) || getGroupDataSources().containsKey(dsKey);
    }

    /**
     * 反向查找已注册的物理数据源 Key
     */
    private String findDataSourceKey(DataSource targetDs) {
        for (Map.Entry<String, DataSource> entry : getDataSources().entrySet()) {
            if (entry.getValue() == targetDs) {
                return entry.getKey();
            }
        }
        return null;
    }

    public HealthAwareDynamicDataSourceStrategy getHealthAwareStrategy() {
        return healthAwareStrategy;
    }

    public ReadAfterWriteManager getReadAfterWriteManager() {
        return readAfterWriteManager;
    }
}
