package io.github.atengk.boot.datasource.template;

import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.baomidou.dynamic.datasource.creator.DataSourceProperty;
import com.baomidou.dynamic.datasource.creator.DefaultDataSourceCreator;
import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.datasource.enums.BatchExecutionPolicy;
import io.github.atengk.boot.datasource.enums.DatasourceResultCode;
import io.github.atengk.boot.datasource.enums.MultiTenancyMode;
import io.github.atengk.boot.datasource.exception.DataSourceSwitchException;
import io.github.atengk.boot.datasource.exception.TenantDataSourceNotFoundException;
import io.github.atengk.boot.datasource.guard.DynamicDatasourceGuard;
import io.github.atengk.boot.datasource.properties.DatasourceProperties;
import io.github.atengk.boot.datasource.routing.AtengDynamicRoutingDataSource;
import io.github.atengk.boot.datasource.tenant.DefaultTenantDataSourceProvider;
import io.github.atengk.boot.datasource.tenant.TenantDataSourceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import javax.sql.DataSource;
import java.sql.Connection;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 编程式动态切源对称门面与全库批处理容错沙箱
 * <p>
 * 提供轻量、栈安全且严格自动恢复的编程式切源 API，支持嵌套出入栈隔离、SaaS 物理多租户受管切源以及全库批量容错巡检。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-24
 */
public class DynamicDatasourceTemplate {

    private static final Logger log = LoggerFactory.getLogger(DynamicDatasourceTemplate.class);

    private final DynamicRoutingDataSource dataSource;
    private final DatasourceProperties datasourceProperties;
    private final TenantDataSourceProvider tenantDataSourceProvider;
    private final DynamicDatasourceGuard guard;
    private final DefaultDataSourceCreator defaultDataSourceCreator;
    private final ReentrantLock lifecycleLock = new ReentrantLock();

    public DynamicDatasourceTemplate(DynamicRoutingDataSource dataSource,
                                     DatasourceProperties datasourceProperties,
                                     TenantDataSourceProvider tenantDataSourceProvider,
                                     DynamicDatasourceGuard guard,
                                     DefaultDataSourceCreator defaultDataSourceCreator) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource 不能为空");
        this.datasourceProperties = datasourceProperties != null ? datasourceProperties : new DatasourceProperties();
        this.tenantDataSourceProvider = tenantDataSourceProvider != null
                ? tenantDataSourceProvider
                : new DefaultTenantDataSourceProvider(this.datasourceProperties);
        this.guard = guard != null ? guard : new DynamicDatasourceGuard(this.datasourceProperties);
        this.defaultDataSourceCreator = defaultDataSourceCreator;
    }

    public DynamicDatasourceTemplate(DynamicRoutingDataSource dataSource,
                                     DatasourceProperties datasourceProperties,
                                     TenantDataSourceProvider tenantDataSourceProvider,
                                     DynamicDatasourceGuard guard) {
        this(dataSource, datasourceProperties, tenantDataSourceProvider, guard, null);
    }

    public DynamicDatasourceTemplate(DynamicRoutingDataSource dataSource,
                                     DatasourceProperties datasourceProperties,
                                     TenantDataSourceProvider tenantDataSourceProvider) {
        this(dataSource, datasourceProperties, tenantDataSourceProvider, new DynamicDatasourceGuard(datasourceProperties), null);
    }

    /**
     * 在指定物理数据源上下文中执行代码块（带返回值）
     * 依托 try-finally 保证 DynamicDataSourceContextHolder 入栈与出栈绝对对称，杜绝 ThreadLocal 泄漏与跨数据源串源
     *
     * @param dsKey  目标数据源 Key
     * @param action 业务执行闭包
     * @param <T>    返回数据类型
     * @return 业务执行结果
     */
    public <T> T callInDataSource(String dsKey, Supplier<T> action) {
        Assert.hasText(dsKey, "数据源 Key (dsKey) 不能为空");
        Objects.requireNonNull(action, "执行动作 (action) 不能为空");

        if (guard != null) {
            guard.validateSwitch(dsKey);
        }

        DynamicDataSourceContextHolder.push(dsKey);
        try {
            return action.get();
        } finally {
            DynamicDataSourceContextHolder.poll();
        }
    }

    /**
     * 在指定物理数据源上下文中执行代码块（无返回值）
     *
     * @param dsKey  目标数据源 Key
     * @param action 业务执行闭包
     */
    public void runInDataSource(String dsKey, Runnable action) {
        Objects.requireNonNull(action, "执行动作 (action) 不能为空");
        callInDataSource(dsKey, () -> {
            action.run();
            return null;
        });
    }

    /**
     * 在指定租户物理分库上下文中执行代码块（带返回值）
     * 自动协调 TenantContextHolder 与 DynamicDataSourceContextHolder，并根据隔离模式执行 STRICT / FALLBACK_MASTER / IGNORE 判定
     *
     * @param tenantId 租户标识
     * @param action   业务执行闭包
     * @param <T>      返回数据类型
     * @return 业务执行结果
     */
    public <T> T callInTenantDataSource(String tenantId, Supplier<T> action) {
        Assert.hasText(tenantId, "租户标识 (tenantId) 不能为空");
        Objects.requireNonNull(action, "执行动作 (action) 不能为空");

        DatasourceProperties.MultiTenancyProperties multiTenancy = datasourceProperties.getMultiTenancy();
        String tenantDsKey = resolveTenantDataSourceKey(tenantId);

        String targetDsKey;
        if (containsDataSource(tenantDsKey)) {
            targetDsKey = tenantDsKey;
        } else {
            MultiTenancyMode mode = multiTenancy.getMode();
            if (mode == MultiTenancyMode.STRICT) {
                throw new TenantDataSourceNotFoundException(tenantId, tenantDsKey);
            }
            if (mode == MultiTenancyMode.FALLBACK_MASTER) {
                targetDsKey = multiTenancy.getDefaultDataSource();
                log.debug("租户 [{}] 未匹配到物理数据源 [{}]，FALLBACK_MASTER 模式回退默认主库 [{}]",
                        tenantId, tenantDsKey, targetDsKey);
            } else {
                targetDsKey = multiTenancy.getDefaultDataSource();
            }
        }

        return TenantContextHolder.runWithTenant(tenantId, () -> {
            if (StringUtils.hasText(targetDsKey)) {
                return callInDataSource(targetDsKey, action);
            } else {
                return action.get();
            }
        });
    }

    /**
     * 在指定租户物理分库上下文中执行代码块（无返回值）
     *
     * @param tenantId 租户标识
     * @param action   业务执行闭包
     */
    public void runInTenantDataSource(String tenantId, Runnable action) {
        Objects.requireNonNull(action, "执行动作 (action) 不能为空");
        callInTenantDataSource(tenantId, () -> {
            action.run();
            return null;
        });
    }

    /**
     * 获取所有已注册的物理数据源 Key 集合（只读）
     *
     * @return 不可修改的物理数据源 Key 集合
     */
    public Set<String> getDataSourceKeys() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(dataSource.getDataSources().keySet()));
    }

    /**
     * 获取所有已注册的分组数据源 Key 集合（只读）
     *
     * @return 不可修改的分组数据源 Key 集合
     */
    public Set<String> getGroupKeys() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(dataSource.getGroupDataSources().keySet()));
    }

    /**
     * 判断指定数据源 Key 是否已注册（支持单物理库与分组库）
     *
     * @param dsKey 数据源 Key
     * @return true 表示存在；false 表示不存在
     */
    public boolean containsDataSource(String dsKey) {
        if (!StringUtils.hasText(dsKey)) {
            return false;
        }
        return dataSource.getDataSources().containsKey(dsKey)
                || dataSource.getGroupDataSources().containsKey(dsKey);
    }

    /**
     * 获取当前线程上下文显式绑定的数据源 Key
     *
     * @return 当前数据源 Key，若未显式指定则返回 null
     */
    public String getCurrentDataSourceKey() {
        return DynamicDataSourceContextHolder.peek();
    }

    /**
     * 全库批量遍历所有已注册物理数据源（默认 FAIL_SAFE 容错聚合策略）
     *
     * @param action 遍历消费动作
     * @return 批处理聚合执行结果
     */
    public BatchExecutionResult forEachDataSource(Consumer<String> action) {
        return forEachDataSource(BatchExecutionPolicy.FAIL_SAFE, action);
    }

    /**
     * 全库批量遍历所有已注册物理数据源
     *
     * @param policy 批处理容错策略
     * @param action 遍历消费动作
     * @return 批处理聚合执行结果
     */
    public BatchExecutionResult forEachDataSource(BatchExecutionPolicy policy, Consumer<String> action) {
        return forEachDataSource(getDataSourceKeys(), policy, action);
    }

    /**
     * 批量遍历指定物理数据源集合（默认 FAIL_SAFE 容错聚合策略）
     *
     * @param dsKeys 指定数据源集合
     * @param action 遍历消费动作
     * @return 批处理聚合执行结果
     */
    public BatchExecutionResult forEachDataSource(Collection<String> dsKeys, Consumer<String> action) {
        return forEachDataSource(dsKeys, BatchExecutionPolicy.FAIL_SAFE, action);
    }

    /**
     * 批量遍历指定物理数据源集合
     *
     * @param dsKeys 指定数据源集合
     * @param policy 批处理容错策略
     * @param action 遍历消费动作
     * @return 批处理聚合执行结果
     */
    public BatchExecutionResult forEachDataSource(Collection<String> dsKeys,
                                                  BatchExecutionPolicy policy,
                                                  Consumer<String> action) {
        Objects.requireNonNull(policy, "批处理容错策略 (policy) 不能为空");
        Objects.requireNonNull(action, "执行动作 (action) 不能为空");

        BatchExecutionResult.Builder builder = BatchExecutionResult.builder();
        if (dsKeys == null || dsKeys.isEmpty()) {
            return builder.build();
        }

        for (String dsKey : dsKeys) {
            if (!StringUtils.hasText(dsKey)) {
                continue;
            }
            try {
                runInDataSource(dsKey, () -> action.accept(dsKey));
                builder.recordSuccess();
            } catch (Throwable t) {
                builder.recordFailure(dsKey, t);
                if (policy == BatchExecutionPolicy.FAIL_FAST) {
                    if (t instanceof RuntimeException runtimeException) {
                        throw runtimeException;
                    }
                    if (t instanceof Error error) {
                        throw error;
                    }
                    throw new RuntimeException("数据源 [" + dsKey + "] 批处理执行异常", t);
                }
                log.warn("数据源 [{}] 批处理执行失败，FAIL_SAFE 容错隔离继续: {}", dsKey, t.getMessage(), t);
            }
        }
        return builder.build();
    }

    /**
     * 全租户批量遍历（默认 FAIL_SAFE 容错聚合策略，自动探测活跃租户）
     *
     * @param action 遍历消费动作（入参：tenantId, dsKey）
     * @return 批处理聚合执行结果
     */
    public BatchExecutionResult forEachTenant(BiConsumer<String, String> action) {
        return forEachTenant(BatchExecutionPolicy.FAIL_SAFE, action);
    }

    /**
     * 全租户批量遍历（自动探测活跃租户）
     *
     * @param policy 批处理容错策略
     * @param action 遍历消费动作（入参：tenantId, dsKey）
     * @return 批处理聚合执行结果
     */
    public BatchExecutionResult forEachTenant(BatchExecutionPolicy policy, BiConsumer<String, String> action) {
        Map<String, String> activeTenants = resolveActiveTenants();
        return executeBatchTenants(activeTenants, policy, action);
    }

    /**
     * 批量遍历指定租户集合（默认 FAIL_SAFE 容错聚合策略）
     *
     * @param tenantIds 指定租户标识集合
     * @param action    遍历消费动作（入参：tenantId, dsKey）
     * @return 批处理聚合执行结果
     */
    public BatchExecutionResult forEachTenant(Collection<String> tenantIds, BiConsumer<String, String> action) {
        return forEachTenant(tenantIds, BatchExecutionPolicy.FAIL_SAFE, action);
    }

    /**
     * 批量遍历指定租户集合
     *
     * @param tenantIds 指定租户标识集合
     * @param policy    批处理容错策略
     * @param action    遍历消费动作（入参：tenantId, dsKey）
     * @return 批处理聚合执行结果
     */
    public BatchExecutionResult forEachTenant(Collection<String> tenantIds,
                                              BatchExecutionPolicy policy,
                                              BiConsumer<String, String> action) {
        Objects.requireNonNull(policy, "批处理容错策略 (policy) 不能为空");
        Objects.requireNonNull(action, "执行动作 (action) 不能为空");

        if (tenantIds == null || tenantIds.isEmpty()) {
            return BatchExecutionResult.builder().build();
        }

        Map<String, String> tenantMap = new LinkedHashMap<>();
        for (String tenantId : tenantIds) {
            if (StringUtils.hasText(tenantId)) {
                String dsKey = resolveTenantDataSourceKey(tenantId);
                tenantMap.put(tenantId, dsKey);
            }
        }
        return executeBatchTenants(tenantMap, policy, action);
    }

    /**
     * 自动探测与解析所有活跃租户及其对应的数据源 Key
     *
     * @return 租户 ID 到数据源 Key 的映射表
     */
    public Map<String, String> resolveActiveTenants() {
        Map<String, String> tenantMap = new LinkedHashMap<>();

        // 1. 优先读取 TenantDataSourceProvider SPI 提供的映射
        if (tenantDataSourceProvider != null) {
            Map<String, String> spiMap = tenantDataSourceProvider.getTenantDataSourceMap();
            if (spiMap != null && !spiMap.isEmpty()) {
                tenantMap.putAll(spiMap);
            }
        }

        // 2. 扫描已注册物理数据源，根据前缀自动推导租户 ID
        String prefix = datasourceProperties.getMultiTenancy().getDataSourcePrefix();
        if (StringUtils.hasText(prefix)) {
            for (String dsKey : getDataSourceKeys()) {
                if (dsKey.startsWith(prefix) && dsKey.length() > prefix.length()) {
                    String tenantId = dsKey.substring(prefix.length());
                    tenantMap.putIfAbsent(tenantId, dsKey);
                }
            }
        }
        return Collections.unmodifiableMap(tenantMap);
    }

    /**
     * 执行多租户沙箱批处理循环
     */
    private BatchExecutionResult executeBatchTenants(Map<String, String> tenantMap,
                                                     BatchExecutionPolicy policy,
                                                     BiConsumer<String, String> action) {
        Objects.requireNonNull(policy, "批处理容错策略 (policy) 不能为空");
        Objects.requireNonNull(action, "执行动作 (action) 不能为空");

        BatchExecutionResult.Builder builder = BatchExecutionResult.builder();
        if (tenantMap == null || tenantMap.isEmpty()) {
            return builder.build();
        }

        for (Map.Entry<String, String> entry : tenantMap.entrySet()) {
            String tenantId = entry.getKey();
            String dsKey = entry.getValue();
            try {
                runInTenantDataSource(tenantId, () -> action.accept(tenantId, dsKey));
                builder.recordSuccess();
            } catch (Throwable t) {
                builder.recordFailure(tenantId, t);
                if (policy == BatchExecutionPolicy.FAIL_FAST) {
                    if (t instanceof RuntimeException runtimeException) {
                        throw runtimeException;
                    }
                    if (t instanceof Error error) {
                        throw error;
                    }
                    throw new RuntimeException("租户 [" + tenantId + "] 批处理执行异常", t);
                }
                log.warn("租户 [{}] 批处理执行失败，FAIL_SAFE 容错隔离继续: {}", tenantId, t.getMessage(), t);
            }
        }
        return builder.build();
    }

    /**
     * 动态注册并初始化受管数据源（带并发互斥与 2 秒连通性 Fail-Fast 预检）
     *
     * @param dsKey    数据源唯一标识
     * @param property 数据源创建配置属性
     */
    public void addDataSource(String dsKey, DataSourceProperty property) {
        Assert.hasText(dsKey, "数据源标识 (dsKey) 不能为空");
        Objects.requireNonNull(property, "数据源属性配置 (property) 不能为空");
        if (defaultDataSourceCreator == null) {
            throw new IllegalStateException("DefaultDataSourceCreator 未初始化，无法根据 DataSourceProperty 动态创建数据源");
        }
        lifecycleLock.lock();
        try {
            if (dataSource.getDataSources().containsKey(dsKey)) {
                throw new DataSourceSwitchException("数据源标识已存在，拒绝重复添加: " + dsKey);
            }
            DataSource newDs;
            try {
                newDs = defaultDataSourceCreator.createDataSource(property);
            } catch (Exception e) {
                log.error("ateng-boot-datasource: 创建物理数据源实例失败 [dsKey={}]", dsKey, e);
                throw new DataSourceSwitchException("创建物理数据源实例失败 [dsKey=" + dsKey + "]: " + e.getMessage(), e,
                        DatasourceResultCode.DATASOURCE_INIT_ERROR);
            }
            validateConnection(dsKey, newDs);
            dataSource.addDataSource(dsKey, newDs);
            log.info("ateng-boot-datasource: 动态注册受管数据源成功 [dsKey={}]", dsKey);
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * 动态注册已就绪的受管物理数据源（带并发互斥与 2 秒连通性 Fail-Fast 预检）
     *
     * @param dsKey  数据源唯一标识
     * @param newDs  待注册的物理数据源实例
     */
    public void addDataSource(String dsKey, DataSource newDs) {
        Assert.hasText(dsKey, "数据源标识 (dsKey) 不能为空");
        Objects.requireNonNull(newDs, "数据源实例 (newDs) 不能为空");
        lifecycleLock.lock();
        try {
            if (dataSource.getDataSources().containsKey(dsKey)) {
                throw new DataSourceSwitchException("数据源标识已存在，拒绝重复添加: " + dsKey);
            }
            validateConnection(dsKey, newDs);
            dataSource.addDataSource(dsKey, newDs);
            log.info("ateng-boot-datasource: 动态注册受管物理数据源成功 [dsKey={}]", dsKey);
        } finally {
            lifecycleLock.unlock();
        }
    }

    /**
     * 优雅注销并安全销毁数据源（从路由表安全摘除流量，并按需等待在途事务连接归还）
     *
     * @param dsKey    待注销数据源标识
     * @param graceful 是否执行优雅排空（等待在途连接归还，超时时间由 ateng.boot.datasource.dynamic-load.drain-timeout 纳管）
     */
    public void removeDataSource(String dsKey, boolean graceful) {
        Assert.hasText(dsKey, "数据源标识 (dsKey) 不能为空");
        String primary = resolvePrimaryDataSourceKey();
        if (dsKey.equals(primary) || "master".equals(dsKey)) {
            throw new DataSourceSwitchException("受管主数据源严禁动态注销: " + dsKey);
        }
        lifecycleLock.lock();
        try {
            DataSource targetDs = dataSource.getDataSources().get(dsKey);
            if (targetDs == null) {
                log.warn("ateng-boot-datasource: 待注销数据源不存在或已被移除 [dsKey={}]", dsKey);
                return;
            }
            // 1. 先从动态路由注册表中摘除，切断新请求流量
            dataSource.removeDataSource(dsKey);
            log.info("ateng-boot-datasource: 已从动态路由表摘除数据源流量 [dsKey={}]", dsKey);

            // 2. 若指定 graceful，执行优雅排空 (Drain)
            if (graceful) {
                drainDataSource(dsKey, targetDs);
            }

            // 3. 物理关闭底层连接池
            closeDataSource(targetDs);
            log.info("ateng-boot-datasource: 受管数据源已安全物理关闭 [dsKey={}]", dsKey);
        } finally {
            lifecycleLock.unlock();
        }
    }

    private void validateConnection(String dsKey, DataSource ds) {
        Duration probeTimeout = datasourceProperties.getDynamicLoad() != null
                ? datasourceProperties.getDynamicLoad().getProbeTimeout()
                : Duration.ofSeconds(2);
        int timeoutSeconds = Math.max(1, (int) probeTimeout.toSeconds());
        try (Connection conn = ds.getConnection()) {
            if (!conn.isValid(timeoutSeconds)) {
                closeDataSource(ds);
                throw new DataSourceSwitchException("数据源连通性预检失败 (isValid=false) [dsKey=" + dsKey + "]");
            }
        } catch (Exception e) {
            closeDataSource(ds);
            if (e instanceof DataSourceSwitchException switchException) {
                throw switchException;
            }
            throw new DataSourceSwitchException("新增数据源连通性预检异常 [dsKey=" + dsKey + "]: " + e.getMessage(), e,
                    DatasourceResultCode.DATASOURCE_INIT_ERROR);
        }
    }

    private void drainDataSource(String dsKey, DataSource ds) {
        if (ds instanceof HikariDataSource hikari) {
            Duration drainTimeout = datasourceProperties.getDynamicLoad() != null
                    ? datasourceProperties.getDynamicLoad().getDrainTimeout()
                    : Duration.ofSeconds(5);
            long deadline = System.currentTimeMillis() + drainTimeout.toMillis();
            while (System.currentTimeMillis() < deadline) {
                HikariPoolMXBean poolBean = hikari.getHikariPoolMXBean();
                if (poolBean == null || poolBean.getActiveConnections() <= 0) {
                    log.debug("ateng-boot-datasource: 数据源在途活跃连接已全部归还 [dsKey={}]", dsKey);
                    break;
                }
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private void closeDataSource(DataSource ds) {
        if (ds instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                log.warn("ateng-boot-datasource: 关闭数据源连接池异常: {}", e.getMessage(), e);
            }
        }
    }

    private String resolvePrimaryDataSourceKey() {
        if (dataSource instanceof AtengDynamicRoutingDataSource atengDs) {
            String p = atengDs.getPrimary();
            if (StringUtils.hasText(p)) {
                return p;
            }
        }
        if (datasourceProperties != null && datasourceProperties.getMultiTenancy() != null &&
                StringUtils.hasText(datasourceProperties.getMultiTenancy().getDefaultDataSource())) {
            return datasourceProperties.getMultiTenancy().getDefaultDataSource();
        }
        return "master";
    }

    /**
     * 解析租户对应的数据源 Key
     */
    private String resolveTenantDataSourceKey(String tenantId) {
        String tenantDsKey = tenantDataSourceProvider != null
                ? tenantDataSourceProvider.resolveDataSourceKey(tenantId)
                : null;
        if (StringUtils.hasText(tenantDsKey)) {
            return tenantDsKey;
        }
        String prefix = datasourceProperties.getMultiTenancy().getDataSourcePrefix();
        return (prefix != null ? prefix : "") + tenantId;
    }
}
