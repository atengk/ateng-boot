package io.github.atengk.boot.datasource.routing;

import com.baomidou.dynamic.datasource.ds.GroupDataSource;
import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.datasource.enums.ReadAfterWriteScope;
import io.github.atengk.boot.datasource.properties.DatasourceProperties;
import io.github.atengk.boot.datasource.readwrite.DefaultWriteOccurredResolver;
import io.github.atengk.boot.datasource.readwrite.ReadAfterWriteContextHolder;
import io.github.atengk.boot.datasource.readwrite.ReadAfterWriteManager;
import io.github.atengk.boot.datasource.strategy.HealthAwareDynamicDataSourceStrategy;
import io.github.atengk.boot.datasource.tenant.DefaultTenantDataSourceProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

/**
 * 读写分离路由、写后读主防延迟与高可用从库宕机熔断自愈集成测试
 *
 * @author Ateng
 * @since 2026-10-24
 */
class AtengDynamicRoutingDataSourceReadWriteTest {

    private DataSource masterDataSource;
    private DataSource slave1DataSource;
    private DataSource slave2DataSource;

    private DatasourceProperties properties;
    private HealthAwareDynamicDataSourceStrategy healthAwareStrategy;
    private DefaultWriteOccurredResolver writeOccurredResolver;
    private ReadAfterWriteManager readAfterWriteManager;
    private AtengDynamicRoutingDataSource routingDataSource;

    @BeforeEach
    void setUp() {
        masterDataSource = mock(DataSource.class);
        slave1DataSource = mock(DataSource.class);
        slave2DataSource = mock(DataSource.class);

        properties = new DatasourceProperties();
        properties.getReadWriteSplit().setEnabled(true);
        properties.getReadWriteSplit().setMasterDataSource("master");
        properties.getReadWriteSplit().setSlaveDataSource("slave");
        properties.getReadWriteSplit().getReadAfterWrite().setEnabled(true);
        properties.getReadWriteSplit().getReadAfterWrite().setScope(ReadAfterWriteScope.REQUEST);
        properties.getReadWriteSplit().getFailover().setEnabled(true);
        properties.getReadWriteSplit().getFailover().setCooldownWindow(Duration.ofSeconds(30));
        properties.getReadWriteSplit().getFailover().setFallbackToMaster(true);

        healthAwareStrategy = new HealthAwareDynamicDataSourceStrategy(properties);
        writeOccurredResolver = new DefaultWriteOccurredResolver();
        readAfterWriteManager = new ReadAfterWriteManager(properties.getReadWriteSplit().getReadAfterWrite(), writeOccurredResolver);

        routingDataSource = new AtengDynamicRoutingDataSource(
                Collections.emptyList(),
                properties,
                new DefaultTenantDataSourceProvider(properties),
                healthAwareStrategy,
                readAfterWriteManager
        );
        routingDataSource.setPrimary("master");
        routingDataSource.addDataSource("master", masterDataSource);
        routingDataSource.addDataSource("slave_1", slave1DataSource);
        routingDataSource.addDataSource("slave_2", slave2DataSource);

        GroupDataSource slaveGroup = new GroupDataSource("slave", healthAwareStrategy);
        slaveGroup.addDatasource("slave_1", slave1DataSource);
        slaveGroup.addDatasource("slave_2", slave2DataSource);
        routingDataSource.getGroupDataSources().put("slave", slaveGroup);
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
        TransactionSynchronizationManager.clear();
        ReadAfterWriteContextHolder.clear();
        writeOccurredResolver.clearAll();
        UserContextHolder.clear();
        healthAwareStrategy.clearBlacklist();
        DynamicDataSourceContextHolder.clear();
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("测试只读事务 (@Transactional(readOnly = true)) 自动路由至读从库组")
    void testReadOnlyTransaction_RoutesToSlave() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);

        DataSource resolved = routingDataSource.determineDataSource();
        // 应该从从库组 slave_1 或 slave_2 中获取
        assertThat(resolved).isIn(slave1DataSource, slave2DataSource);
    }

    @Test
    @DisplayName("测试写事务 (@Transactional) 自动绑定至主库并触发写标记")
    void testWriteTransaction_BindsToMaster_AndMarksWriteOccurred() {
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);

        DataSource resolved = routingDataSource.determineDataSource();
        assertThat(resolved).isSameAs(masterDataSource);
        assertThat(readAfterWriteManager.isWriteOccurred()).isTrue();
    }

    @Test
    @DisplayName("测试写后即读防延迟：写操作后后续只读事务强制锁定主库")
    void testReadAfterWrite_PreventsLag_ForcesSubsequentReadOnlyToMaster() {
        // 1. 模拟写操作发生
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        routingDataSource.determineDataSource();
        assertThat(readAfterWriteManager.isWriteOccurred()).isTrue();

        // 2. 紧接着发起只读查询
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        DataSource subsequentReadDs = routingDataSource.determineDataSource();

        // 命中写后读主防延迟，强制路由 master 主库而不是从库
        assertThat(subsequentReadDs).isSameAs(masterDataSource);
    }

    @Test
    @DisplayName("测试从库组全部宕机熔断时自动保底降级路由至 master 主库")
    void testSlaveGroup_AllDown_FallbackToMaster() {
        // 记录所有从库故障
        healthAwareStrategy.recordFailure("slave_1");
        healthAwareStrategy.recordFailure("slave_2");

        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);

        // 全部从库不可用，自动降级主库
        DataSource fallbackDs = routingDataSource.determineDataSource();
        assertThat(fallbackDs).isSameAs(masterDataSource);
    }

    @Test
    @DisplayName("测试从库连接物理异常时 getConnection 自动故障转移至主库并隔离故障从库")
    void testSlaveConnectionException_FailoverToMaster() throws SQLException {
        Connection masterConnection = mock(Connection.class);
        when(masterDataSource.getConnection()).thenReturn(masterConnection);
        when(slave1DataSource.getConnection()).thenThrow(new SQLException("从库物理宕机拒绝连接"));
        when(slave2DataSource.getConnection()).thenThrow(new SQLException("从库物理宕机拒绝连接"));

        // 处于只读事务中
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);

        // 执行 getConnection 获取连接，虽然从库抛出 SQLException，但底层自动捕获并故障转移至主库连接
        Connection connection = routingDataSource.getConnection();
        assertThat(connection).isSameAs(masterConnection);

        // 验证故障从库已被加入黑名单
        assertThat(healthAwareStrategy.getBlacklistedDataSources()).isNotEmpty();
    }

    @Test
    @DisplayName("测试租户物理分库透明路由优先于全局事务读写分离 (防止事务劫持租户路由)")
    void testTenantRoutingTakesPrecedenceOverTransactionalReadOnlyAndWrite() {
        DataSource tenant1001Ds = mock(DataSource.class);
        routingDataSource.addDataSource("tenant_1001", tenant1001Ds);

        TenantContextHolder.setTenantId("1001");

        // 1. 写事务 (@Transactional) 依然严格路由至租户专属物理库
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        DataSource resolvedWrite = routingDataSource.determineDataSource();
        assertThat(resolvedWrite).isSameAs(tenant1001Ds);

        // 2. 只读事务 (@Transactional(readOnly = true)) 也严格路由至租户专属物理库
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        DataSource resolvedReadOnly = routingDataSource.determineDataSource();
        assertThat(resolvedReadOnly).isSameAs(tenant1001Ds);
    }
}
