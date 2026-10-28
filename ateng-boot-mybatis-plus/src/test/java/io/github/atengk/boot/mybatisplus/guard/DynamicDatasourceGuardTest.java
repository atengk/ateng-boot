package io.github.atengk.boot.mybatisplus.guard;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.mybatisplus.context.DataPermissionContextHolder;
import io.github.atengk.boot.mybatisplus.properties.MybatisPlusProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * DynamicDatasourceGuard 多数据源协同诊断探针与上下文守卫测试
 * 覆盖正常上下文校验、活跃事务拦截、数据权限未闭合残留拦截、租户不匹配断言、
 * 显式污染标记与恢复、以及启动期多数据源与动态路由数据源特征探针
 *
 * @author Ateng
 * @since 2026-10-18
 */
@DisplayName("DynamicDatasourceGuard 多数据源守卫与诊断探针测试")
class DynamicDatasourceGuardTest {

    @BeforeEach
    @AfterEach
    void resetContext() {
        DynamicDatasourceGuard.clearTaintedContext();
        DynamicDatasourceGuard.setMultiDataSourceDetected(false);
        TenantContextHolder.clear();
        DataPermissionContextHolder.clear();
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.setActualTransactionActive(false);
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // ==========================================
    // 1. 静态上下文隔离断言正常场景 (Happy Path)
    // ==========================================

    @Test
    @DisplayName("干净上下文环境：validateContextIsolated 校验正常通过，无任何异常")
    void shouldPassWhenContextIsCompletelyIsolatedAndClean() {
        assertThatCode(() -> DynamicDatasourceGuard.validateContextIsolated())
                .doesNotThrowAnyException();

        assertThatCode(() -> DynamicDatasourceGuard.validateContextIsolated(null))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("租户上下文匹配：当线程绑定的租户 ID 与期望租户完全一致时校验通过")
    void shouldPassWhenTenantIdMatchesExpected() {
        TenantContextHolder.setTenantId("tenant_001");

        assertThatCode(() -> DynamicDatasourceGuard.validateContextIsolated("tenant_001"))
                .doesNotThrowAnyException();
    }

    // ==========================================
    // 2. 静态上下文隔离断言异常拦截 (Failure Modes & Fail-Fast)
    // ==========================================

    @Test
    @DisplayName("活跃事务防卫：当前线程处于活跃事务中时切源断言失败，阻断假切源与串库风险")
    void shouldThrowWhenActiveTransactionIsDetected() {
        TransactionSynchronizationManager.setActualTransactionActive(true);

        assertThatThrownBy(() -> DynamicDatasourceGuard.validateContextIsolated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("当前线程处于活跃事务中")
                .hasMessageContaining("TransactionActive=true");
    }

    @Test
    @DisplayName("数据权限超管残留防卫：当前线程残留 isSuperAdmin=true 时触发告警阻断")
    void shouldThrowWhenSuperAdminContextRemains() {
        DataPermissionContextHolder.setSuperAdmin(true);

        assertThatThrownBy(() -> DynamicDatasourceGuard.validateContextIsolated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("数据权限上下文残留")
                .hasMessageContaining("isSuperAdmin=true");
    }

    @Test
    @DisplayName("数据权限忽略残留防卫：当前线程残留 isIgnore=true 时触发告警阻断")
    void shouldThrowWhenIgnoreDataPermissionContextRemains() {
        DataPermissionContextHolder.setIgnore(true);

        assertThatThrownBy(() -> DynamicDatasourceGuard.validateContextIsolated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("数据权限上下文残留")
                .hasMessageContaining("isIgnore=true");
    }

    @Test
    @DisplayName("租户不一致防卫：当前线程租户 ID 与期望租户不匹配时快速失败抛出异常")
    void shouldThrowWhenTenantIdDoesNotMatchExpected() {
        TenantContextHolder.setTenantId("tenant_A");

        assertThatThrownBy(() -> DynamicDatasourceGuard.validateContextIsolated("tenant_B"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("多数据源租户上下文不一致")
                .hasMessageContaining("tenant_A")
                .hasMessageContaining("tenant_B");
    }

    @Test
    @DisplayName("显式污染标记防卫：显式标记污染后断言失败，清理后恢复正常通过")
    void shouldThrowWhenExplicitlyMarkedTaintedAndRecoverAfterClear() {
        DynamicDatasourceGuard.markContextTainted("模拟多源切换中间件脏读异常");
        assertThat(DynamicDatasourceGuard.isContextTainted()).isTrue();

        assertThatThrownBy(() -> DynamicDatasourceGuard.validateContextIsolated())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("模拟多源切换中间件脏读异常");

        DynamicDatasourceGuard.clearTaintedContext();
        assertThat(DynamicDatasourceGuard.isContextTainted()).isFalse();

        assertThatCode(() -> DynamicDatasourceGuard.validateContextIsolated())
                .doesNotThrowAnyException();
    }

    // ==========================================
    // 3. 启动期协同诊断探针生命周期测试 (Probe)
    // ==========================================

    @Test
    @DisplayName("探针自检：Spring 容器无 DataSource 时优雅跳过不抛异常")
    void shouldHandleNoDataSourceGracefully() {
        ApplicationContext applicationContext = Mockito.mock(ApplicationContext.class);
        Mockito.when(applicationContext.getBeansOfType(DataSource.class)).thenReturn(Map.of());

        DynamicDatasourceGuard guard = new DynamicDatasourceGuard(applicationContext, new MybatisPlusProperties());
        guard.afterSingletonsInstantiated();

        assertThat(DynamicDatasourceGuard.isMultiDataSourceDetected()).isFalse();
    }

    @Test
    @DisplayName("探针自检：Spring 容器仅有单普通 DataSource 时判定为非多数据源环境")
    void shouldDetectSingleDataSourceEnvironment() {
        ApplicationContext applicationContext = Mockito.mock(ApplicationContext.class);
        DataSource singleDs = Mockito.mock(DataSource.class);
        Mockito.when(applicationContext.getBeansOfType(DataSource.class)).thenReturn(Map.of("dataSource", singleDs));

        DynamicDatasourceGuard guard = new DynamicDatasourceGuard(applicationContext, new MybatisPlusProperties());
        guard.afterSingletonsInstantiated();

        assertThat(DynamicDatasourceGuard.isMultiDataSourceDetected()).isFalse();
    }

    @Test
    @DisplayName("探针自检：Spring 容器存在多个 DataSource Bean 时成功识别为多数据源环境")
    void shouldDetectMultipleDataSourceBeansEnvironment() {
        ApplicationContext applicationContext = Mockito.mock(ApplicationContext.class);
        DataSource master = Mockito.mock(DataSource.class);
        DataSource slave = Mockito.mock(DataSource.class);
        Mockito.when(applicationContext.getBeansOfType(DataSource.class)).thenReturn(Map.of(
                "masterDataSource", master,
                "slaveDataSource", slave
        ));

        DynamicDatasourceGuard guard = new DynamicDatasourceGuard(applicationContext, new MybatisPlusProperties());
        guard.afterSingletonsInstantiated();

        assertThat(DynamicDatasourceGuard.isMultiDataSourceDetected()).isTrue();
    }

    @Test
    @DisplayName("探针自检：单一 DataSource 为 AbstractRoutingDataSource 子类时识别为动态路由多源环境")
    void shouldDetectAbstractRoutingDataSourceEnvironment() {
        ApplicationContext applicationContext = Mockito.mock(ApplicationContext.class);
        AbstractRoutingDataSource routingDs = Mockito.mock(AbstractRoutingDataSource.class);
        Mockito.when(applicationContext.getBeansOfType(DataSource.class)).thenReturn(Map.of(
                "routingDataSource", routingDs
        ));

        DynamicDatasourceGuard guard = new DynamicDatasourceGuard(applicationContext, new MybatisPlusProperties());
        guard.afterSingletonsInstantiated();

        assertThat(DynamicDatasourceGuard.isMultiDataSourceDetected()).isTrue();
    }

    static class CustomDynamicRoutingDataSource implements DataSource {
        @Override
        public java.sql.Connection getConnection() { return null; }
        @Override
        public java.sql.Connection getConnection(String username, String password) { return null; }
        @Override
        public java.io.PrintWriter getLogWriter() { return null; }
        @Override
        public void setLogWriter(java.io.PrintWriter out) {}
        @Override
        public void setLoginTimeout(int seconds) {}
        @Override
        public int getLoginTimeout() { return 0; }
        @Override
        public java.util.logging.Logger getParentLogger() { return null; }
        @Override
        public <T> T unwrap(Class<T> iface) { return null; }
        @Override
        public boolean isWrapperFor(Class<?> iface) { return false; }
    }

    @Test
    @DisplayName("探针自检：单一 DataSource 类名包含 DynamicRouting 时成功识别动态切源环境")
    void shouldDetectNamedDynamicRoutingDataSourceEnvironment() {
        ApplicationContext applicationContext = Mockito.mock(ApplicationContext.class);
        CustomDynamicRoutingDataSource dynamicDs = new CustomDynamicRoutingDataSource();
        Mockito.when(applicationContext.getBeansOfType(DataSource.class)).thenReturn(Map.of(
                "dynamicDataSource", dynamicDs
        ));

        DynamicDatasourceGuard guard = new DynamicDatasourceGuard(applicationContext, new MybatisPlusProperties());
        guard.afterSingletonsInstantiated();

        assertThat(DynamicDatasourceGuard.isMultiDataSourceDetected()).isTrue();
    }
}
