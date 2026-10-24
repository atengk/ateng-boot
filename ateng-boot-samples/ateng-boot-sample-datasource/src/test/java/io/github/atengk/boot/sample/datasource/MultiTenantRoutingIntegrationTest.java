package io.github.atengk.boot.sample.datasource;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.datasource.template.DynamicDatasourceTemplate;
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

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
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
}
