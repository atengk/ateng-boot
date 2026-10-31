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
import io.github.atengk.boot.datasource.tenant.TenantDataSourceProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * 物理多租户独立主从读写分离、从库自愈与写后读主防延迟测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
class AtengDynamicRoutingTenantReadWriteTest {

    private DataSource globalMasterDs;
    private DataSource globalSlaveDs;
    private DataSource tenant1001MasterDs;
    private DataSource tenant1001Slave1Ds;
    private DataSource tenant1001Slave2Ds;
    private DataSource tenant1002SingleDs;

    private DatasourceProperties properties;
    private HealthAwareDynamicDataSourceStrategy healthAwareStrategy;
    private DefaultWriteOccurredResolver writeOccurredResolver;
    private ReadAfterWriteManager readAfterWriteManager;
    private AtengDynamicRoutingDataSource routingDataSource;

    @BeforeEach
    void setUp() {
        globalMasterDs = mock(DataSource.class);
        globalSlaveDs = mock(DataSource.class);
        tenant1001MasterDs = mock(DataSource.class);
        tenant1001Slave1Ds = mock(DataSource.class);
        tenant1001Slave2Ds = mock(DataSource.class);
        tenant1002SingleDs = mock(DataSource.class);

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

        // 注册全局数据源
        routingDataSource.addDataSource("master", globalMasterDs);
        routingDataSource.addDataSource("slave", globalSlaveDs);

        // 注册租户 1001 独立主从拓扑：tenant_1001 (主库) + tenant_1001_slave (从库分组)
        routingDataSource.addDataSource("tenant_1001", tenant1001MasterDs);
        routingDataSource.addDataSource("tenant_1001_slave_1", tenant1001Slave1Ds);
        routingDataSource.addDataSource("tenant_1001_slave_2", tenant1001Slave2Ds);
        GroupDataSource tenant1001SlaveGroup = new GroupDataSource("tenant_1001_slave", healthAwareStrategy);
        tenant1001SlaveGroup.addDatasource("tenant_1001_slave_1", tenant1001Slave1Ds);
        tenant1001SlaveGroup.addDatasource("tenant_1001_slave_2", tenant1001Slave2Ds);
        routingDataSource.getGroupDataSources().put("tenant_1001_slave", tenant1001SlaveGroup);

        // 注册租户 1002 单物理库（无从库）
        routingDataSource.addDataSource("tenant_1002", tenant1002SingleDs);
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
    @DisplayName("测试租户写事务 (@Transactional) 自动绑定至租户主库并激活写后读主防延迟标记")
    void testTenantWriteTransaction_BindsToTenantMaster_AndMarksWriteOccurred() {
        TenantContextHolder.setTenantId("1001");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);

        DataSource resolved = routingDataSource.determineDataSource();
        assertThat(resolved).isSameAs(tenant1001MasterDs);
        assertThat(readAfterWriteManager.isWriteOccurred()).isTrue();
    }

    @Test
    @DisplayName("测试租户只读事务 (@Transactional(readOnly = true)) 自动路由至租户从库集群")
    void testTenantReadOnlyTransaction_RoutesToTenantSlaveGroup() {
        TenantContextHolder.setTenantId("1001");
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);

        DataSource resolved = routingDataSource.determineDataSource();
        assertThat(resolved).isIn(tenant1001Slave1Ds, tenant1001Slave2Ds);
    }

    @Test
    @DisplayName("测试租户写后即读防延迟：写操作后后续只读事务强制回退至租户主库")
    void testTenantReadAfterWrite_ForcesSubsequentReadOnlyToTenantMaster() {
        TenantContextHolder.setTenantId("1001");

        // 1. 模拟租户写事务发生
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        DataSource writeDs = routingDataSource.determineDataSource();
        assertThat(writeDs).isSameAs(tenant1001MasterDs);
        assertThat(readAfterWriteManager.isWriteOccurred()).isTrue();

        // 2. 紧接着在同一上下文发起只读事务
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        DataSource subsequentReadDs = routingDataSource.determineDataSource();

        // 触发防延迟，强制回退租户主库，而不是租户从库或全局从库
        assertThat(subsequentReadDs).isSameAs(tenant1001MasterDs);
    }

    @Test
    @DisplayName("测试租户显式声明 @DS('slave') 时路由至租户从库且受写后读主防延迟保护")
    void testTenantExplicitDsSlave_RoutesToTenantSlave_AndFallsBackToTenantMasterWhenRawActive() {
        TenantContextHolder.setTenantId("1001");
        DynamicDataSourceContextHolder.push("slave");

        try {
            // 尚未写过，路由至租户从库
            DataSource readDs = routingDataSource.determineDataSource();
            assertThat(readDs).isIn(tenant1001Slave1Ds, tenant1001Slave2Ds);

            // 触发写标记
            readAfterWriteManager.markWriteOccurred();

            // 再次调用，受防延迟保护强制回退租户主库
            DataSource protectedDs = routingDataSource.determineDataSource();
            assertThat(protectedDs).isSameAs(tenant1001MasterDs);
        } finally {
            DynamicDataSourceContextHolder.poll();
        }
    }

    @Test
    @DisplayName("测试租户从库全部熔断时自动保底降级至租户主库 (严守隔离边界，严禁降级到全局主库)")
    void testTenantSlaveAllDown_FallsBackToTenantMaster_NotGlobalMaster() {
        TenantContextHolder.setTenantId("1001");

        // 熔断租户 1001 的所有从库
        healthAwareStrategy.recordFailure("tenant_1001_slave_1");
        healthAwareStrategy.recordFailure("tenant_1001_slave_2");

        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);

        DataSource fallbackDs = routingDataSource.determineDataSource();
        // 关键断言：必须降级到 tenant_1001 (租户主库)，绝对不能降级到 global master
        assertThat(fallbackDs).isSameAs(tenant1001MasterDs);
    }

    @Test
    @DisplayName("测试单库租户（无从库）读写均严格保底在租户物理库且支持触发写标记")
    void testTenantSingleDatabase_ReadAndWrite_RemainOnTenantMaster() {
        TenantContextHolder.setTenantId("1002");

        // 1. 写事务
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        DataSource writeDs = routingDataSource.determineDataSource();
        assertThat(writeDs).isSameAs(tenant1002SingleDs);
        assertThat(readAfterWriteManager.isWriteOccurred()).isTrue();

        // 2. 只读事务（由于没有 tenant_1002_slave，继续安全保底在 tenant_1002）
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        DataSource readDs = routingDataSource.determineDataSource();
        assertThat(readDs).isSameAs(tenant1002SingleDs);
    }

    @Test
    @DisplayName("测试通过 TenantDataSourceProvider SPI 自定义解析租户主库与从库标识")
    void testCustomTenantDataSourceProvider_ResolvesMasterAndSlave() {
        DataSource customMaster = mock(DataSource.class);
        DataSource customSlave = mock(DataSource.class);
        routingDataSource.addDataSource("db_cust_master", customMaster);
        routingDataSource.addDataSource("db_cust_slave", customSlave);

        TenantDataSourceProvider customProvider = new TenantDataSourceProvider() {
            @Override
            public String resolveDataSourceKey(String tenantId) {
                return "db_cust_master";
            }

            @Override
            public String resolveMasterDataSourceKey(String tenantId) {
                return "db_cust_master";
            }

            @Override
            public String resolveSlaveDataSourceKey(String tenantId) {
                return "db_cust_slave";
            }
        };

        AtengDynamicRoutingDataSource customRoutingDs = new AtengDynamicRoutingDataSource(
                Collections.emptyList(),
                properties,
                customProvider,
                healthAwareStrategy,
                readAfterWriteManager
        );
        customRoutingDs.addDataSource("db_cust_master", customMaster);
        customRoutingDs.addDataSource("db_cust_slave", customSlave);

        TenantContextHolder.setTenantId("custom_999");

        // 1. 读事务
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
        assertThat(customRoutingDs.determineDataSource()).isSameAs(customSlave);

        // 2. 写事务
        TransactionSynchronizationManager.setCurrentTransactionReadOnly(false);
        assertThat(customRoutingDs.determineDataSource()).isSameAs(customMaster);
    }
}
