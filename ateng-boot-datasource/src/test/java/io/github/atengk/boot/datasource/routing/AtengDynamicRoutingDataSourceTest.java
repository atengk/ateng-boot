package io.github.atengk.boot.datasource.routing;

import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.datasource.enums.MultiTenancyMode;
import io.github.atengk.boot.datasource.exception.TenantDataSourceNotFoundException;
import io.github.atengk.boot.datasource.properties.DatasourceProperties;
import io.github.atengk.boot.datasource.tenant.DefaultTenantDataSourceProvider;
import io.github.atengk.boot.datasource.tenant.TenantDataSourceProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * SaaS 物理多租户透明切库引擎单元测试
 *
 * @author Ateng
 * @since 2026-10-24
 */
class AtengDynamicRoutingDataSourceTest {

    private DataSource masterDataSource;
    private DataSource tenant1001DataSource;
    private DataSource tenant1002DataSource;

    private DatasourceProperties properties;
    private TenantDataSourceProvider provider;
    private AtengDynamicRoutingDataSource routingDataSource;

    @BeforeEach
    void setUp() {
        masterDataSource = mock(DataSource.class);
        tenant1001DataSource = mock(DataSource.class);
        tenant1002DataSource = mock(DataSource.class);

        properties = new DatasourceProperties();
        properties.getMultiTenancy().setEnabled(true);
        properties.getMultiTenancy().setDefaultDataSource("master");
        properties.getMultiTenancy().setDataSourcePrefix("tenant_");
        properties.getMultiTenancy().setMode(MultiTenancyMode.FALLBACK_MASTER);

        provider = new DefaultTenantDataSourceProvider(properties);

        routingDataSource = new AtengDynamicRoutingDataSource(Collections.emptyList(), properties, provider);
        routingDataSource.setPrimary("master");
        routingDataSource.addDataSource("master", masterDataSource);
        routingDataSource.addDataSource("tenant_1001", tenant1001DataSource);
        routingDataSource.addDataSource("tenant_1002", tenant1002DataSource);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        DynamicDataSourceContextHolder.clear();
    }

    @Test
    @DisplayName("测试无租户上下文时默认路由至 master 主数据源")
    void testDetermineDataSourceWithoutTenant() {
        DataSource resolved = routingDataSource.determineDataSource();
        assertThat(resolved).isSameAs(masterDataSource);
    }

    @Test
    @DisplayName("测试基于 TenantContextHolder 物理分库透明切源 (免注 @DS)")
    void testDetermineDataSourceWithTenant() {
        TenantContextHolder.setTenantId("1001");
        DataSource resolved1001 = routingDataSource.determineDataSource();
        assertThat(resolved1001).isSameAs(tenant1001DataSource);

        TenantContextHolder.setTenantId("1002");
        DataSource resolved1002 = routingDataSource.determineDataSource();
        assertThat(resolved1002).isSameAs(tenant1002DataSource);
    }

    @Test
    @DisplayName("测试显式指定数据源 (@DS 或门面) 优先级高于租户隐式切源")
    void testExplicitKeyOverridesTenant() {
        TenantContextHolder.setTenantId("1001");
        // 显式切到 master
        DynamicDataSourceContextHolder.push("master");
        try {
            DataSource resolved = routingDataSource.determineDataSource();
            assertThat(resolved).isSameAs(masterDataSource);
        } finally {
            DynamicDataSourceContextHolder.poll();
        }

        // 显式出栈后，继续恢复为租户 1001
        assertThat(routingDataSource.determineDataSource()).isSameAs(tenant1001DataSource);
    }

    @Test
    @DisplayName("测试 STRICT 严格模式下未找到租户独立物理库抛出异常")
    void testStrictModeThrowsExceptionWhenNotFound() {
        properties.getMultiTenancy().setMode(MultiTenancyMode.STRICT);
        TenantContextHolder.setTenantId("9999");

        assertThatThrownBy(() -> routingDataSource.determineDataSource())
                .isInstanceOf(TenantDataSourceNotFoundException.class)
                .hasMessageContaining("未找到租户 [9999] 对应的物理数据源 [tenant_9999]");
    }

    @Test
    @DisplayName("测试 FALLBACK_MASTER 模式下未找到租户独立库自动回退主库")
    void testFallbackMasterMode() {
        properties.getMultiTenancy().setMode(MultiTenancyMode.FALLBACK_MASTER);
        TenantContextHolder.setTenantId("9999");

        DataSource resolved = routingDataSource.determineDataSource();
        assertThat(resolved).isSameAs(masterDataSource);
    }

    @Test
    @DisplayName("测试 isIgnore=true 免租户标记时自动路由至主库")
    void testIgnoreTenantFlagRoutesToMaster() {
        TenantContextHolder.setTenantId("1001");
        TenantContextHolder.setIgnoreTenant(true);

        DataSource resolved = routingDataSource.determineDataSource();
        assertThat(resolved).isSameAs(masterDataSource);
    }

    @Test
    @DisplayName("测试自定义 TenantDataSourceProvider SPI 解析逻辑")
    void testCustomTenantDataSourceProvider() {
        TenantDataSourceProvider customProvider = tenantId -> "vip".equals(tenantId) ? "master" : "tenant_" + tenantId;
        AtengDynamicRoutingDataSource customRouting = new AtengDynamicRoutingDataSource(Collections.emptyList(), properties, customProvider);
        customRouting.addDataSource("master", masterDataSource);
        customRouting.addDataSource("tenant_888", tenant1001DataSource);

        TenantContextHolder.setTenantId("vip");
        assertThat(customRouting.determineDataSource()).isSameAs(masterDataSource);

        TenantContextHolder.setTenantId("888");
        assertThat(customRouting.determineDataSource()).isSameAs(tenant1001DataSource);
    }

    @Test
    @DisplayName("测试 STRICT 严格模式下自定义 SPI 返回 null 拒绝静默穿透并抛出异常")
    void testStrictModeThrowsExceptionWhenProviderReturnsNull() {
        properties.getMultiTenancy().setMode(MultiTenancyMode.STRICT);
        TenantDataSourceProvider nullReturningProvider = tenantId -> null;
        AtengDynamicRoutingDataSource strictRouting = new AtengDynamicRoutingDataSource(
                Collections.emptyList(), properties, nullReturningProvider);
        strictRouting.setPrimary("master");
        strictRouting.addDataSource("master", masterDataSource);

        TenantContextHolder.setTenantId("unregistered_tenant");

        assertThatThrownBy(strictRouting::determineDataSource)
                .isInstanceOf(TenantDataSourceNotFoundException.class)
                .hasMessageContaining("未找到租户 [unregistered_tenant]");
    }

    @Test
    @DisplayName("测试未设置 tenantId 时单独设置 isIgnoreTenant=true 依然正确路由至主库")
    void testIgnoreTenantFlagWithoutTenantIdRoutesToMaster() {
        TenantContextHolder.setIgnoreTenant(true);

        DataSource resolved = routingDataSource.determineDataSource();
        assertThat(resolved).isSameAs(masterDataSource);
    }

    @Test
    @DisplayName("测试 IGNORE 模式下即使存在租户数据源也强制路由至主库")
    void testIgnoreModeRoutesToMasterEvenWithMatchingTenantDataSource() {
        properties.getMultiTenancy().setMode(MultiTenancyMode.IGNORE);
        TenantContextHolder.setTenantId("1001");

        DataSource resolved = routingDataSource.determineDataSource();
        assertThat(resolved).isSameAs(masterDataSource);
    }
}
