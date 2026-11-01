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
        if (this.healthAwareStrategy.getDataSourceLocator() == null) {
            this.healthAwareStrategy.setDataSourceLocator(this::findDataSource);
        }
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
        DatasourceProperties.ReadWriteSplitProperties readWrite = datasourceProperties.getReadWriteSplit();
        String masterKey = StringUtils.hasText(readWrite.getMasterDataSource()) ? readWrite.getMasterDataSource() : "master";
        String slaveKey = StringUtils.hasText(readWrite.getSlaveDataSource()) ? readWrite.getSlaveDataSource() : "slave";

        String explicitKey = DynamicDataSourceContextHolder.peek();
        String currentTenantId = TenantContextHolder.getTenantId();
        boolean hasTenant = StringUtils.hasText(currentTenantId) && !TenantContextHolder.isIgnoreTenant();

        boolean writeOccurred = readWrite.isEnabled() && readAfterWriteManager != null && readAfterWriteManager.isWriteOccurred();
        String tenantMasterKey = null;
        String tenantSlaveKey = null;
        if (hasTenant) {
            tenantMasterKey = resolveTenantMasterKey(currentTenantId);
            tenantSlaveKey = resolveTenantSlaveKey(currentTenantId, tenantMasterKey);
        }

        // 1. 显式指定通用从库或当前目标为从库时的写后读主防延迟检查
        if (writeOccurred) {
            if (Objects.equals(explicitKey, slaveKey)) {
                if (hasTenant) {
                    log.debug("写后读主 (Read-After-Write) 防延迟生效，租户 [{}] 显式切源 @DS('{}') 强制只读查询路由至主库: [{}]",
                            currentTenantId, explicitKey, tenantMasterKey);
                    return resolveWithFailover(tenantMasterKey);
                } else {
                    log.debug("写后读主 (Read-After-Write) 防延迟生效，强制只读查询路由至主库: [{}] (原路由: [{}])", masterKey, explicitKey);
                    return resolveWithFailover(masterKey);
                }
            }
            if (hasTenant && StringUtils.hasText(explicitKey) && Objects.equals(explicitKey, tenantSlaveKey)) {
                log.debug("写后读主 (Read-After-Write) 防延迟生效，租户 [{}] 显式切源 @DS('{}') 强制只读查询路由至主库: [{}]",
                        currentTenantId, explicitKey, tenantMasterKey);
                return resolveWithFailover(tenantMasterKey);
            }
        }

        // 2. 显式 @DS 切源优先级处理
        if (StringUtils.hasText(explicitKey)) {
            // 如果显式指定了通用从库标识 (例如 "slave") 且当前处于租户上下文中，优先尝试路由至租户专属从库
            if (hasTenant && Objects.equals(explicitKey, slaveKey)) {
                if (StringUtils.hasText(tenantSlaveKey) && containsDataSource(tenantSlaveKey)) {
                    log.debug("租户 [{}] 显式切源 @DS('{}') 自动分流至租户专属从库: [{}]", currentTenantId, explicitKey, tenantSlaveKey);
                    return resolveWithFailover(tenantSlaveKey, tenantMasterKey);
                } else if (StringUtils.hasText(tenantMasterKey) && containsDataSource(tenantMasterKey)) {
                    log.debug("租户 [{}] 显式切源 @DS('{}') 未配置专属从库，安全保底路由至租户主库: [{}]", currentTenantId, explicitKey, tenantMasterKey);
                    return resolveWithFailover(tenantMasterKey);
                }
            }
            return resolveWithFailover(explicitKey);
        }

        // 3. 物理多租户全自动透明切源与租户读写分离
        DatasourceProperties.MultiTenancyProperties multiTenancy = datasourceProperties.getMultiTenancy();
        if (multiTenancy.isEnabled() && hasTenant && multiTenancy.getMode() != MultiTenancyMode.IGNORE) {
            if (containsDataSource(tenantMasterKey)) {
                // 3.1 租户写事务 (@Transactional)
                if (readWrite.isEnabled() && TransactionSynchronizationManager.isActualTransactionActive()
                        && !TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
                    log.debug("租户 [{}] 写事务 (@Transactional) 自动绑定至租户主库: [{}]", currentTenantId, tenantMasterKey);
                    if (readAfterWriteManager != null) {
                        readAfterWriteManager.markWriteOccurred();
                    }
                    return resolveWithFailover(tenantMasterKey);
                }

                // 3.2 租户只读事务 (@Transactional(readOnly = true))
                if (readWrite.isEnabled() && TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
                    if (writeOccurred) {
                        log.debug("写后读主 (Read-After-Write) 防延迟生效，租户 [{}] 只读事务强制回退主库: [{}]", currentTenantId, tenantMasterKey);
                        return resolveWithFailover(tenantMasterKey);
                    }
                    if (StringUtils.hasText(tenantSlaveKey) && containsDataSource(tenantSlaveKey)) {
                        log.debug("租户 [{}] 只读事务自动路由至租户从库集群: [{}]", currentTenantId, tenantSlaveKey);
                        return resolveWithFailover(tenantSlaveKey, tenantMasterKey);
                    }
                    log.debug("租户 [{}] 未配置专属从库，只读事务平滑保底租户主库: [{}]", currentTenantId, tenantMasterKey);
                    return resolveWithFailover(tenantMasterKey);
                }

                // 3.3 租户非事务/自动提交语句
                if (writeOccurred) {
                    log.debug("写后读主 (Read-After-Write) 防延迟生效，租户 [{}] 非事务查询强制路由至主库: [{}]", currentTenantId, tenantMasterKey);
                    return resolveWithFailover(tenantMasterKey);
                }

                log.debug("租户物理分库透明切源命中: tenantId=[{}], dsKey=[{}]", currentTenantId, tenantMasterKey);
                return resolveWithFailover(tenantMasterKey);
            }

            // 未匹配到独立物理库，执行隔离策略决策
            MultiTenancyMode mode = multiTenancy.getMode();
            if (mode == MultiTenancyMode.STRICT) {
                throw new TenantDataSourceNotFoundException(currentTenantId, tenantMasterKey);
            }
            if (mode == MultiTenancyMode.FALLBACK_MASTER) {
                log.debug("租户 [{}] 未分配独立物理库 [{}]，FALLBACK_MASTER 平滑回退全局主库 [{}]",
                        currentTenantId, tenantMasterKey, multiTenancy.getDefaultDataSource());
                // 回退全局主库并走全局读写分离逻辑
            }
        }

        // 4. 全局读写分离：只读事务 (@Transactional(readOnly = true))
        if (readWrite.isEnabled() && TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            if (writeOccurred) {
                log.debug("写后读主 (Read-After-Write) 防延迟生效，只读事务强制回退全局主库: [{}]", masterKey);
                return resolveWithFailover(masterKey);
            }
            if (containsDataSource(slaveKey)) {
                log.debug("只读事务 (@Transactional(readOnly = true)) 自动路由至全局读从库组: [{}]", slaveKey);
                return resolveWithFailover(slaveKey, masterKey);
            } else {
                log.debug("未找到读从库 [{}]，只读事务平滑回退主库: [{}]", slaveKey, masterKey);
                return resolveWithFailover(masterKey);
            }
        }

        // 5. 全局写事务：当前处于活动 Spring 事务中且非只读，强制绑定至主库并触发写标记
        if (readWrite.isEnabled() && TransactionSynchronizationManager.isActualTransactionActive()) {
            log.debug("写事务 (@Transactional) 自动绑定至全局主库: [{}]", masterKey);
            if (readAfterWriteManager != null) {
                readAfterWriteManager.markWriteOccurred();
            }
            return resolveWithFailover(masterKey);
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
            return handleConnectionFailure(resolvedDataSource, e, DataSource::getConnection);
        }
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        String xid = TransactionContext.getXID();
        if (StringUtils.hasText(xid)) {
            return super.getConnection(username, password);
        }

        DataSource resolvedDataSource = determineDataSource();
        try {
            return resolvedDataSource.getConnection(username, password);
        } catch (SQLException e) {
            return handleConnectionFailure(resolvedDataSource, e, ds -> ds.getConnection(username, password));
        }
    }

    @FunctionalInterface
    private interface ConnectionFunction {
        Connection get(DataSource ds) throws SQLException;
    }

    private Connection handleConnectionFailure(DataSource resolvedDataSource,
                                               SQLException e,
                                               ConnectionFunction function) throws SQLException {
        // 捕获物理连接异常并执行自愈熔断与故障转移
        if (healthAwareStrategy != null && datasourceProperties.getReadWriteSplit().getFailover().isEnabled()) {
            String failedKey = findDataSourceKey(resolvedDataSource);
            if (StringUtils.hasText(failedKey)) {
                healthAwareStrategy.recordFailure(failedKey);
                if (datasourceProperties.getReadWriteSplit().getFailover().isFallbackToMaster()) {
                    String currentTenantId = TenantContextHolder.getTenantId();
                    String fallbackKey = null;
                    if (StringUtils.hasText(currentTenantId) && !TenantContextHolder.isIgnoreTenant()) {
                        fallbackKey = resolveTenantMasterKey(currentTenantId);
                    }
                    if (!StringUtils.hasText(fallbackKey)) {
                        fallbackKey = datasourceProperties.getReadWriteSplit().getMasterDataSource();
                    }
                    if (!Objects.equals(failedKey, fallbackKey) && containsDataSource(fallbackKey)) {
                        log.warn("从库节点 [{}] 获取连接失败，高可用自愈降级获取主库 [{}] 连接", failedKey, fallbackKey, e);
                        return function.get(getDataSource(fallbackKey));
                    }
                }
            }
        }
        throw e;
    }

    /**
     * 具备故障自愈熔断的分组与单数据源解析
     *
     * @param dsKey 目标数据源 Key
     * @return 实际物理数据源
     */
    public DataSource resolveWithFailover(String dsKey) {
        return resolveWithFailover(dsKey, null);
    }

    /**
     * 具备故障自愈熔断的分组与单数据源解析（支持显式指定降级主库）
     *
     * @param dsKey              目标数据源 Key
     * @param fallbackMasterKey 故障自愈降级主库 Key（为空时使用全局 master）
     * @return 实际物理数据源
     */
    public DataSource resolveWithFailover(String dsKey, String fallbackMasterKey) {
        if (!containsDataSource(dsKey)) {
            if (StringUtils.hasText(fallbackMasterKey) && containsDataSource(fallbackMasterKey)) {
                return getDataSource(fallbackMasterKey);
            }
            return super.determineDataSource();
        }

        String masterFallback = StringUtils.hasText(fallbackMasterKey)
                ? fallbackMasterKey
                : datasourceProperties.getReadWriteSplit().getMasterDataSource();

        // 若是分组数据源 (GroupDataSource)
        if (getGroupDataSources().containsKey(dsKey)) {
            GroupDataSource groupDataSource = getGroupDataSources().get(dsKey);
            if (healthAwareStrategy != null && datasourceProperties.getReadWriteSplit().getFailover().isEnabled()) {
                List<String> nodeNames = new ArrayList<>(groupDataSource.getDataSourceMap().keySet());
                List<String> healthyNodes = healthAwareStrategy.filterHealthyNodes(nodeNames);
                if (healthyNodes.isEmpty()) {
                    log.warn("从库组 [{}] 全部从节点均处于熔断黑名单中，高可用保底自动降级路由至主库: [{}]", dsKey, masterFallback);
                    return containsDataSource(masterFallback) ? getDataSource(masterFallback) : super.determineDataSource();
                }
                String selectedNodeKey = healthAwareStrategy.determineKey(healthyNodes);
                return groupDataSource.getDataSourceMap().get(selectedNodeKey);
            }
            return groupDataSource.determineDataSource();
        }

        // 若是单数据源
        if (healthAwareStrategy != null && datasourceProperties.getReadWriteSplit().getFailover().isEnabled()
                && healthAwareStrategy.isBlacklisted(dsKey)) {
            log.warn("从库节点 [{}] 处于熔断黑名单中，高可用保底自动降级路由至主库: [{}]", dsKey, masterFallback);
            return containsDataSource(masterFallback) ? getDataSource(masterFallback) : super.determineDataSource();
        }

        return getDataSource(dsKey);
    }

    /**
     * 解析租户的主数据源 Key
     *
     * @param tenantId 租户标识
     * @return 租户主数据源 Key
     */
    public String resolveTenantMasterKey(String tenantId) {
        if (!StringUtils.hasText(tenantId)) {
            return null;
        }
        String key = null;
        if (tenantDataSourceProvider != null) {
            key = tenantDataSourceProvider.resolveMasterDataSourceKey(tenantId);
            if (!StringUtils.hasText(key)) {
                key = tenantDataSourceProvider.resolveDataSourceKey(tenantId);
            }
        }
        if (!StringUtils.hasText(key)) {
            String prefix = datasourceProperties.getMultiTenancy().getDataSourcePrefix();
            key = (prefix != null ? prefix : "") + tenantId;
        }
        if (containsDataSource(key + "_master")) {
            return key + "_master";
        }
        if (containsDataSource(key)) {
            return key;
        }
        return key;
    }

    /**
     * 解析租户的从库数据源或从库分组 Key
     *
     * @param tenantId        租户标识
     * @param tenantMasterKey 租户主数据源 Key
     * @return 租户从库数据源 Key，未匹配返回 null
     */
    public String resolveTenantSlaveKey(String tenantId, String tenantMasterKey) {
        if (!StringUtils.hasText(tenantId)) {
            return null;
        }
        String slaveKey = null;
        if (tenantDataSourceProvider != null) {
            slaveKey = tenantDataSourceProvider.resolveSlaveDataSourceKey(tenantId);
        }
        if (StringUtils.hasText(slaveKey) && containsDataSource(slaveKey)) {
            return slaveKey;
        }
        // 约定探测 1: tenantMasterKey + "_slave"
        if (StringUtils.hasText(tenantMasterKey) && containsDataSource(tenantMasterKey + "_slave")) {
            return tenantMasterKey + "_slave";
        }
        // 约定探测 2: prefix + tenantId + "_slave"
        String prefix = datasourceProperties.getMultiTenancy().getDataSourcePrefix();
        String defaultPrefixKey = (prefix != null ? prefix : "") + tenantId + "_slave";
        if (containsDataSource(defaultPrefixKey)) {
            return defaultPrefixKey;
        }
        // 约定探测 3: 如果 prefix + tenantId 本身注册为 GroupDataSource (从库集群)
        String rawKey = (prefix != null ? prefix : "") + tenantId;
        if (getGroupDataSources().containsKey(rawKey)) {
            return rawKey;
        }
        return slaveKey;
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
     * 反向查找已注册的物理数据源 Key（遍历单物理库与从库分组库内各节点）
     */
    private String findDataSourceKey(DataSource targetDs) {
        if (targetDs == null) {
            return null;
        }
        for (Map.Entry<String, DataSource> entry : getDataSources().entrySet()) {
            if (entry.getValue() == targetDs) {
                return entry.getKey();
            }
        }
        for (Map.Entry<String, GroupDataSource> groupEntry : getGroupDataSources().entrySet()) {
            if (groupEntry.getValue() == targetDs) {
                return groupEntry.getKey();
            }
            if (groupEntry.getValue().getDataSourceMap() != null) {
                for (Map.Entry<String, DataSource> nodeEntry : groupEntry.getValue().getDataSourceMap().entrySet()) {
                    if (nodeEntry.getValue() == targetDs) {
                        return nodeEntry.getKey();
                    }
                }
            }
        }
        return null;
    }

    /**
     * 依据数据源 Key 查找实际物理数据源实例
     * 支持单物理库与从库分组库内各节点
     *
     * @param dsKey 数据源 Key
     * @return 匹配的 DataSource 实例，未匹配返回 null
     */
    public DataSource findDataSource(String dsKey) {
        if (!StringUtils.hasText(dsKey)) {
            return null;
        }
        DataSource ds = getDataSources().get(dsKey);
        if (ds != null) {
            return ds;
        }
        GroupDataSource group = getGroupDataSources().get(dsKey);
        if (group != null) {
            return group.determineDataSource();
        }
        for (GroupDataSource g : getGroupDataSources().values()) {
            if (g != null && g.getDataSourceMap() != null && g.getDataSourceMap().containsKey(dsKey)) {
                return g.getDataSourceMap().get(dsKey);
            }
        }
        return null;
    }

    /**
     * 注册分组数据源 (GroupDataSource)
     *
     * @param groupKey        分组标识
     * @param groupDataSource 分组数据源实例
     */
    public void addGroupDataSource(String groupKey, GroupDataSource groupDataSource) {
        if (!StringUtils.hasText(groupKey) || groupDataSource == null) {
            return;
        }
        getGroupDataSources().put(groupKey, groupDataSource);
        log.info("ateng-boot-datasource: 成功注册分组数据源: [{}]", groupKey);
    }

    /**
     * 注销分组数据源 (GroupDataSource)
     *
     * @param groupKey 分组标识
     * @return 被移除的分组数据源，若不存在返回 null
     */
    public GroupDataSource removeGroupDataSource(String groupKey) {
        if (!StringUtils.hasText(groupKey)) {
            return null;
        }
        GroupDataSource removed = getGroupDataSources().remove(groupKey);
        if (removed != null) {
            log.info("ateng-boot-datasource: 成功注销分组数据源: [{}]", groupKey);
        }
        return removed;
    }

    @Override
    public void destroy() {
        if (healthAwareStrategy != null) {
            healthAwareStrategy.destroy();
        }
        super.destroy();
    }

    public HealthAwareDynamicDataSourceStrategy getHealthAwareStrategy() {
        return healthAwareStrategy;
    }

    public ReadAfterWriteManager getReadAfterWriteManager() {
        return readAfterWriteManager;
    }
}
