package io.github.atengk.boot.sample.datasource;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.datasource.enums.MultiTenancyMode;
import io.github.atengk.boot.datasource.exception.TenantDataSourceNotFoundException;
import io.github.atengk.boot.datasource.properties.DatasourceProperties;
import io.github.atengk.boot.datasource.template.DynamicDatasourceTemplate;
import io.github.atengk.boot.sample.datasource.config.CustomTenantDataSourceProvider;
import io.github.atengk.boot.sample.datasource.dto.OrderCreateRequest;
import io.github.atengk.boot.sample.datasource.service.OrderService;
import io.github.atengk.boot.sample.datasource.vo.OrderResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 多租户物理分库路由端到端集成测试
 *
 * @author Ateng
 * @since 2026-10-24
 */
@SpringBootTest
class MultiTenantRoutingIntegrationTest {

    @Autowired
    private OrderService orderService;

    @Autowired
    private DynamicDatasourceTemplate dynamicDatasourceTemplate;

    @Autowired
    private DatasourceProperties datasourceProperties;

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        datasourceProperties.getMultiTenancy().setMode(MultiTenancyMode.FALLBACK_MASTER);
    }

    @Test
    @DisplayName("租户物理分库隔离: 租户1001与1002数据严格落入各自物理库，互不污染")
    void testTenantDataIsolation() {
        // 1. 租户 1001 创建订单
        TenantContextHolder.setTenantId("1001");
        OrderCreateRequest req1 = OrderCreateRequest.builder()
                .orderNo("ORD-1001-A")
                .productName("SaaS Product A")
                .amount(new BigDecimal("199.00"))
                .build();
        OrderResponse res1 = orderService.createTenantOrder(req1);
        assertThat(res1).isNotNull();
        assertThat(res1.getOrderNo()).isEqualTo("ORD-1001-A");

        // 2. 租户 1001 查询订单存在
        OrderResponse query1 = orderService.getTenantOrder("ORD-1001-A");
        assertThat(query1).isNotNull();
        assertThat(query1.getProductName()).isEqualTo("SaaS Product A");

        // 3. 切换到租户 1002 查询，由于物理分库隔离，租户 1002 查不到该订单
        TenantContextHolder.setTenantId("1002");
        OrderResponse queryInTenant2 = orderService.getTenantOrder("ORD-1001-A");
        assertThat(queryInTenant2).isNull();

        // 4. 租户 1002 创建自己的订单
        OrderCreateRequest req2 = OrderCreateRequest.builder()
                .orderNo("ORD-1002-B")
                .productName("SaaS Product B")
                .amount(new BigDecimal("299.00"))
                .build();
        OrderResponse res2 = orderService.createTenantOrder(req2);
        assertThat(res2).isNotNull();

        // 5. 验证租户 1002 存在，切回 1001 查不到 1002 的订单
        assertThat(orderService.getTenantOrder("ORD-1002-B")).isNotNull();
        TenantContextHolder.setTenantId("1001");
        assertThat(orderService.getTenantOrder("ORD-1002-B")).isNull();
    }

    @Test
    @DisplayName("租户回退主库机制: FALLBACK_MASTER 模式下未分配独立物理库的租户平滑回退主库")
    void testTenantFallbackToMaster() {
        // 设置一个不存在独立数据源的租户
        TenantContextHolder.setTenantId("9999");

        // 在 FALLBACK_MASTER 模式下，允许回退到默认 master 库查询
        OrderResponse normalQuery = orderService.getTenantOrder("NON-EXISTENT");
        assertThat(normalQuery).isNull();
    }

    @Test
    @DisplayName("租户严格分库机制: STRICT 模式下未分配独立物理库的租户刚性拦截并抛出 TenantDataSourceNotFoundException")
    void testTenantStrictRoutingThrowsWhenTenantNotFound() {
        datasourceProperties.getMultiTenancy().setMode(MultiTenancyMode.STRICT);
        TenantContextHolder.setTenantId("9999");

        assertThatThrownBy(() -> orderService.getTenantOrder("NON-EXISTENT"))
                .hasRootCauseInstanceOf(TenantDataSourceNotFoundException.class)
                .hasMessageContaining("未找到租户 [9999]");
    }

    @Test
    @DisplayName("租户忽略机制: IGNORE 模式下无论上下文是否存在租户，均强制路由回 master 主库")
    void testTenantIgnoreModeRoutesToMaster() {
        datasourceProperties.getMultiTenancy().setMode(MultiTenancyMode.IGNORE);
        TenantContextHolder.setTenantId("1001");

        // 在 IGNORE 模式下，即便上下文设置了 1001，查询路由也直接定向至 master
        OrderResponse response = orderService.getTenantOrder("ORD-NON-EXIST-IN-MASTER");
        assertThat(response).isNull();
    }

    @Test
    @DisplayName("免租户穿透机制: setIgnoreTenant(true) 时即使设置了租户标识也直接穿透至主库")
    void testIgnoreTenantFlagBypassesTenantRouting() {
        TenantContextHolder.setTenantId("1001");
        TenantContextHolder.setIgnoreTenant(true);

        OrderResponse response = orderService.getTenantOrder("ORD-IGNORE-TENANT-TEST");
        assertThat(response).isNull();
    }

    @Test
    @DisplayName("自定义租户解析 SPI: CustomTenantDataSourceProvider 自定义映射解析")
    void testCustomTenantDataSourceProvider() {
        CustomTenantDataSourceProvider provider = new CustomTenantDataSourceProvider();
        assertThat(provider.resolveDataSourceKey("VIP_1001")).isEqualTo("tenant_1001");
        assertThat(provider.resolveDataSourceKey("VIP_1002")).isEqualTo("tenant_1002");
        assertThat(provider.resolveDataSourceKey("DEFAULT_OTHER")).isEqualTo("tenant_DEFAULT_OTHER");
    }
}
