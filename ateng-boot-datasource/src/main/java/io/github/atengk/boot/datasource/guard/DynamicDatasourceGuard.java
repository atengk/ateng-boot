package io.github.atengk.boot.datasource.guard;

import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import com.baomidou.dynamic.datasource.tx.TransactionContext;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.datasource.enums.MultiTenancyMode;
import io.github.atengk.boot.datasource.exception.DataSourceSwitchException;
import io.github.atengk.boot.datasource.properties.DatasourceProperties;
import io.github.atengk.boot.datasource.readwrite.ReadAfterWriteManager;
import io.github.atengk.boot.datasource.routing.AtengDynamicRoutingDataSource;
import io.github.atengk.boot.datasource.tenant.DefaultTenantDataSourceProvider;
import io.github.atengk.boot.datasource.tenant.TenantDataSourceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.StringUtils;

import java.lang.reflect.Method;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 动态数据源事务切源安全守卫
 * <p>
 * 负责智能协同 Spring 单连接事务 (DataSourceTransactionManager) 与 Baomidou 多源事务协调器 (@DSTransactional)。
 * 严密防卫并拦截在 Spring 原生单连接事务中跨库切源的假切源风险，安全放行 Propagation.REQUIRES_NEW 独立事务与 @DSTransactional 多源事务。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-24
 */
public class DynamicDatasourceGuard {

    private static final Logger log = LoggerFactory.getLogger(DynamicDatasourceGuard.class);

    /**
     * 绑定至 Spring 事务上下文的当前事务数据源标识资源 Key
     */
    public static final String TX_BOUND_DATASOURCE_RESOURCE_KEY = "ATENG_TX_BOUND_DATASOURCE_KEY";

    private final DatasourceProperties datasourceProperties;
    private final Supplier<TenantDataSourceProvider> tenantDataSourceProviderSupplier;
    private final Supplier<ReadAfterWriteManager> readAfterWriteManagerSupplier;
    private final Supplier<DynamicRoutingDataSource> routingDataSourceSupplier;

    public DynamicDatasourceGuard(DatasourceProperties datasourceProperties) {
        this(datasourceProperties, null, null, null);
    }

    /**
     * 构造具备租户感知与写后即读对齐能力的事务切源安全守卫
     *
     * @param datasourceProperties             数据源配置属性
     * @param tenantDataSourceProviderSupplier 租户数据源解析器提供者
     * @param readAfterWriteManagerSupplier    写后即读协调管理器提供者
     * @param routingDataSourceSupplier        动态路由数据源提供者
     */
    public DynamicDatasourceGuard(DatasourceProperties datasourceProperties,
                                  Supplier<TenantDataSourceProvider> tenantDataSourceProviderSupplier,
                                  Supplier<ReadAfterWriteManager> readAfterWriteManagerSupplier,
                                  Supplier<DynamicRoutingDataSource> routingDataSourceSupplier) {
        this.datasourceProperties = datasourceProperties != null ? datasourceProperties : new DatasourceProperties();
        this.tenantDataSourceProviderSupplier = tenantDataSourceProviderSupplier;
        this.readAfterWriteManagerSupplier = readAfterWriteManagerSupplier;
        this.routingDataSourceSupplier = routingDataSourceSupplier;
    }

    /**
     * 校验当前执行上下文是否已隔离且切源操作安全
     * 对应架构规格中编程式切源前置安全校验契约
     *
     * @param targetDsKey 目标数据源 Key
     */
    public void validateContextIsolated(String targetDsKey) {
        validateSwitch(targetDsKey);
    }

    /**
     * 校验当前执行上下文切源操作是否安全
     *
     * @param targetDsKey 目标数据源 Key
     */
    public void validateSwitch(String targetDsKey) {
        validateSwitch(targetDsKey, null, null);
    }

    /**
     * 校验当前执行上下文切源操作是否安全（携带方法反射信息）
     *
     * @param targetDsKey 目标数据源 Key
     * @param method      切源目标方法（用于提取 @Transactional 传播属性）
     */
    public void validateSwitch(String targetDsKey, Method method) {
        validateSwitch(targetDsKey, method, method != null ? method.getDeclaringClass() : null);
    }

    /**
     * 校验当前执行上下文切源操作是否安全（完整重载）
     *
     * @param targetDsKey 目标数据源 Key
     * @param method      切源目标方法
     * @param targetClass 切源目标类
     */
    public void validateSwitch(String targetDsKey, Method method, Class<?> targetClass) {
        if (!datasourceProperties.getGuard().isEnabled() || !StringUtils.hasText(targetDsKey)) {
            return;
        }

        // 1. 若当前处于 Baomidou @DSTransactional 本地多源事务协调器上下文中，放行切源
        if (isDSTransactionActive()) {
            log.debug("当前处于 @DSTransactional 多源协调事务 (XID=[{}])，放行数据源切换至: [{}]",
                    TransactionContext.getXID(), targetDsKey);
            return;
        }

        // 2. 检查当前是否存在处于活动状态的 Spring 单物理连接事务
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            // 无活动事务，安全放行
            return;
        }

        // 3. 当前处于活动 Spring 事务中：检查是否通过 Propagation.REQUIRES_NEW 声明开启独立新事务
        if (isRequiresNewTransaction(method, targetClass)) {
            log.debug("检测到切源方法声明了 @Transactional(propagation = Propagation.REQUIRES_NEW)，放行独立事务切源至: [{}]",
                    targetDsKey);
            return;
        }

        // 4. 获取当前 Spring 事务初始绑定或执行中的物理数据源 Key
        String currentTxDsKey = getCurrentTransactionDataSourceKey();

        // 5. 若目标数据源与当前 Spring 事务绑定的数据源相同，未发生跨库物理切换，安全放行
        if (Objects.equals(currentTxDsKey, targetDsKey)) {
            return;
        }

        // 6. 检测到跨库切换，判定为 Spring 事务假切源致命隐患
        String errorMessage = String.format(
                "检测到 Spring 单连接事务 (DataSourceTransactionManager) 假切源致命风险：" +
                "当前事务已绑定数据源 [%s]，尝试切源至 [%s] 将被 Spring 事务静默忽略并造成串库！" +
                "若需跨库独立执行，请在目标方法声明 @Transactional(propagation = Propagation.REQUIRES_NEW)，" +
                "或使用 @DSTransactional 本地多源事务协调器。",
                currentTxDsKey, targetDsKey
        );

        if (datasourceProperties.getGuard().isFailOnFalseSwitch()) {
            log.error(errorMessage);
            throw new DataSourceSwitchException(currentTxDsKey, targetDsKey, errorMessage);
        } else {
            log.warn("假切源拦截降级 (failOnFalseSwitch=false): {}", errorMessage);
        }
    }

    /**
     * 判断当前线程是否处于 Baomidou @DSTransactional 多数据源事务协调器上下文中
     *
     * @return true 表示处于 @DSTransactional 事务中；false 表示未激活
     */
    public boolean isDSTransactionActive() {
        return StringUtils.hasText(TransactionContext.getXID());
    }

    /**
     * 判断当前线程是否处于标准 Spring 事务 (DataSourceTransactionManager) 活跃上下文中
     *
     * @return true 表示处于 Spring 事务中；false 表示未激活
     */
    public boolean isSpringTransactionActive() {
        return TransactionSynchronizationManager.isActualTransactionActive();
    }

    /**
     * 获取当前处于活动状态的 Spring 事务绑定的数据源 Key
     * 若未绑定，则记录当前线程栈顶数据源（或默认主库）并注册自动解绑同步器
     *
     * @return 当前事务绑定的数据源 Key，若无活动事务则返回 null
     */
    public String getCurrentTransactionDataSourceKey() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return null;
        }

        // 优先从 Spring 事务资源绑定表中读取
        if (TransactionSynchronizationManager.hasResource(TX_BOUND_DATASOURCE_RESOURCE_KEY)) {
            Object resource = TransactionSynchronizationManager.getResource(TX_BOUND_DATASOURCE_RESOURCE_KEY);
            if (resource instanceof String boundKey && StringUtils.hasText(boundKey)) {
                return boundKey;
            }
        }

        // 若尚未记录，则取当前线程栈顶数据源，栈为空则按租户与读写分离拓扑解析默认数据源
        String currentKey = DynamicDataSourceContextHolder.peek();
        if (!StringUtils.hasText(currentKey)) {
            currentKey = resolveDefaultDataSourceKey();
        }

        bindTransactionDataSourceKeyIfAbsent(currentKey);
        return currentKey;
    }

    /**
     * 当处于活动 Spring 本地事务且尚未绑定数据源标识时，绑定当前事务数据源并注册事务完结解绑同步器
     *
     * @param dsKey 实际绑定的数据源标识
     */
    public static void bindTransactionDataSourceKeyIfAbsent(String dsKey) {
        if (!StringUtils.hasText(dsKey)
                || StringUtils.hasText(TransactionContext.getXID())
                || !TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.isSynchronizationActive()
                || TransactionSynchronizationManager.hasResource(TX_BOUND_DATASOURCE_RESOURCE_KEY)) {
            return;
        }
        try {
            TransactionSynchronizationManager.bindResource(TX_BOUND_DATASOURCE_RESOURCE_KEY, dsKey);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    if (TransactionSynchronizationManager.hasResource(TX_BOUND_DATASOURCE_RESOURCE_KEY)) {
                        TransactionSynchronizationManager.unbindResource(TX_BOUND_DATASOURCE_RESOURCE_KEY);
                    }
                }
            });
        } catch (Throwable t) {
            log.debug("注册事务数据源解绑同步器异常: {}", t.getMessage());
        }
    }

    /**
     * 判定目标方法或目标类是否声明了 Propagation.REQUIRES_NEW 独立事务
     */
    public boolean isRequiresNewTransaction(Method method, Class<?> targetClass) {
        if (method != null) {
            Transactional methodTx = AnnotatedElementUtils.findMergedAnnotation(method, Transactional.class);
            if (methodTx != null) {
                return methodTx.propagation() == Propagation.REQUIRES_NEW;
            }
        }
        if (targetClass != null) {
            Transactional classTx = AnnotatedElementUtils.findMergedAnnotation(targetClass, Transactional.class);
            if (classTx != null) {
                return classTx.propagation() == Propagation.REQUIRES_NEW;
            }
        }
        return false;
    }

    /**
     * 解析当前事务默认数据源 Key（同步对齐 SaaS 多租户、读写分离与写后即读状态）
     */
    private String resolveDefaultDataSourceKey() {
        DatasourceProperties.ReadWriteSplitProperties readWrite = datasourceProperties.getReadWriteSplit();
        DatasourceProperties.MultiTenancyProperties multiTenancy = datasourceProperties.getMultiTenancy();

        String masterKey = (readWrite != null && StringUtils.hasText(readWrite.getMasterDataSource()))
                ? readWrite.getMasterDataSource()
                : (multiTenancy != null && StringUtils.hasText(multiTenancy.getDefaultDataSource()) ? multiTenancy.getDefaultDataSource() : "master");
        String slaveKey = (readWrite != null && StringUtils.hasText(readWrite.getSlaveDataSource()))
                ? readWrite.getSlaveDataSource()
                : "slave";

        boolean readWriteEnabled = readWrite != null && readWrite.isEnabled();
        boolean readOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        ReadAfterWriteManager rawManager = readAfterWriteManagerSupplier != null ? readAfterWriteManagerSupplier.get() : null;
        boolean writeOccurred = readWriteEnabled && rawManager != null && rawManager.isWriteOccurred();
        DynamicRoutingDataSource routingDs = routingDataSourceSupplier != null ? routingDataSourceSupplier.get() : null;

        // 1. 物理多租户透明路由优先级（与 AtengDynamicRoutingDataSource 对齐）
        String currentTenantId = TenantContextHolder.getTenantId();
        boolean hasTenant = StringUtils.hasText(currentTenantId) && !TenantContextHolder.isIgnoreTenant();
        if (multiTenancy != null && multiTenancy.isEnabled() && hasTenant && multiTenancy.getMode() != MultiTenancyMode.IGNORE) {
            String tenantMasterKey = resolveTenantMasterKey(currentTenantId, multiTenancy, routingDs);
            boolean tenantMasterExists = StringUtils.hasText(tenantMasterKey) && (routingDs == null || containsDataSource(routingDs, tenantMasterKey));
            if (tenantMasterExists) {
                if (readWriteEnabled && readOnly && !writeOccurred) {
                    String tenantSlaveKey = resolveTenantSlaveKey(currentTenantId, tenantMasterKey, multiTenancy, routingDs);
                    if (StringUtils.hasText(tenantSlaveKey) && containsDataSource(routingDs, tenantSlaveKey)) {
                        return tenantSlaveKey;
                    }
                }
                return tenantMasterKey;
            }
            if (multiTenancy.getMode() == MultiTenancyMode.STRICT && StringUtils.hasText(tenantMasterKey)) {
                return tenantMasterKey;
            }
        }

        // 2. 全局读写分离路由（含写后即读回退主库）
        if (readWriteEnabled) {
            if (readOnly && !writeOccurred && StringUtils.hasText(slaveKey)) {
                if (routingDs == null || containsDataSource(routingDs, slaveKey)) {
                    return slaveKey;
                }
            }
            if (StringUtils.hasText(masterKey)) {
                return masterKey;
            }
        }

        if (multiTenancy != null && StringUtils.hasText(multiTenancy.getDefaultDataSource())) {
            return multiTenancy.getDefaultDataSource();
        }
        return "master";
    }

    private String resolveTenantMasterKey(String tenantId,
                                          DatasourceProperties.MultiTenancyProperties multiTenancy,
                                          DynamicRoutingDataSource routingDs) {
        if (routingDs instanceof AtengDynamicRoutingDataSource atengRouting) {
            return atengRouting.resolveTenantMasterKey(tenantId);
        }
        TenantDataSourceProvider provider = resolveProvider();
        String key = provider.resolveMasterDataSourceKey(tenantId);
        if (!StringUtils.hasText(key)) {
            key = provider.resolveDataSourceKey(tenantId);
        }
        if (!StringUtils.hasText(key)) {
            String prefix = multiTenancy.getDataSourcePrefix();
            key = (prefix != null ? prefix : "") + tenantId;
        }
        if (routingDs != null && containsDataSource(routingDs, key + "_master")) {
            return key + "_master";
        }
        return key;
    }

    private String resolveTenantSlaveKey(String tenantId,
                                         String tenantMasterKey,
                                         DatasourceProperties.MultiTenancyProperties multiTenancy,
                                         DynamicRoutingDataSource routingDs) {
        if (routingDs instanceof AtengDynamicRoutingDataSource atengRouting) {
            return atengRouting.resolveTenantSlaveKey(tenantId, tenantMasterKey);
        }
        TenantDataSourceProvider provider = resolveProvider();
        String slaveKey = provider.resolveSlaveDataSourceKey(tenantId);
        if (StringUtils.hasText(slaveKey) && (routingDs == null || containsDataSource(routingDs, slaveKey))) {
            return slaveKey;
        }
        if (routingDs != null) {
            if (StringUtils.hasText(tenantMasterKey) && containsDataSource(routingDs, tenantMasterKey + "_slave")) {
                return tenantMasterKey + "_slave";
            }
            String prefix = multiTenancy.getDataSourcePrefix();
            String defaultPrefixSlave = (prefix != null ? prefix : "") + tenantId + "_slave";
            if (containsDataSource(routingDs, defaultPrefixSlave)) {
                return defaultPrefixSlave;
            }
            String rawKey = (prefix != null ? prefix : "") + tenantId;
            if (routingDs.getGroupDataSources().containsKey(rawKey)) {
                return rawKey;
            }
        }
        return slaveKey;
    }

    private TenantDataSourceProvider resolveProvider() {
        TenantDataSourceProvider provider = tenantDataSourceProviderSupplier != null ? tenantDataSourceProviderSupplier.get() : null;
        return provider != null ? provider : new DefaultTenantDataSourceProvider(datasourceProperties);
    }

    private boolean containsDataSource(DynamicRoutingDataSource routingDs, String dsKey) {
        if (routingDs == null || !StringUtils.hasText(dsKey)) {
            return false;
        }
        if (routingDs instanceof AtengDynamicRoutingDataSource atengRouting) {
            return atengRouting.containsDataSource(dsKey);
        }
        return routingDs.getDataSources().containsKey(dsKey) || routingDs.getGroupDataSources().containsKey(dsKey);
    }
}
