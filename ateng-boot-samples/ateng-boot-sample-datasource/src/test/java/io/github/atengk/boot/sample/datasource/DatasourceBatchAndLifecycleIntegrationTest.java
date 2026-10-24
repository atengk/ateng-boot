package io.github.atengk.boot.sample.datasource;

import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.datasource.template.BatchExecutionResult;
import io.github.atengk.boot.sample.datasource.dto.OrderCreateRequest;
import io.github.atengk.boot.sample.datasource.service.DatasourceBatchScenarioService;
import io.github.atengk.boot.sample.datasource.service.DatasourceLifecycleScenarioService;
import io.github.atengk.boot.sample.datasource.service.OrderService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 批量容错巡检与动态数据源生命周期端到端集成测试
 *
 * @author Ateng
 * @since 2026-10-24
 */
@SpringBootTest
class DatasourceBatchAndLifecycleIntegrationTest {

    @Autowired
    private DatasourceBatchScenarioService batchScenarioService;

    @Autowired
    private DatasourceLifecycleScenarioService lifecycleScenarioService;

    @Autowired
    private OrderService orderService;

    @Autowired
    private DataSource dataSource;

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
    }

    @Test
    @DisplayName("批量巡检容错: forEachDataSource 与 forEachTenant 遍历统计所有库订单数")
    void testBatchTraversalAndCount() {
        // 在租户 1001 中创建 1 笔订单
        TenantContextHolder.setTenantId("1001");
        orderService.createTenantOrder(OrderCreateRequest.builder()
                .orderNo("ORD-BATCH-1001")
                .productName("Batch Item")
                .amount(new BigDecimal("10.00"))
                .build());

        // 1. 全库巡检
        ConcurrentMap<String, Long> dsCounts = new ConcurrentHashMap<>();
        BatchExecutionResult dsResult = batchScenarioService.countOrdersPerDataSource(dsCounts);
        assertThat(dsResult).isNotNull();
        assertThat(dsResult.getSuccessCount()).isGreaterThanOrEqualTo(4);
        assertThat(dsCounts).containsKey("master");
        assertThat(dsCounts).containsKey("tenant_1001");
        assertThat(dsCounts.get("tenant_1001")).isGreaterThanOrEqualTo(1L);

        // 2. 租户库巡检
        ConcurrentMap<String, Long> tenantCounts = new ConcurrentHashMap<>();
        BatchExecutionResult tenantResult = batchScenarioService.countOrdersPerTenant(tenantCounts);
        assertThat(tenantResult).isNotNull();
        assertThat(tenantResult.getSuccessCount()).isGreaterThanOrEqualTo(2);
        assertThat(tenantCounts).containsKey("1001");
        assertThat(tenantCounts.get("1001")).isGreaterThanOrEqualTo(1L);
    }

    @Test
    @DisplayName("动态生命周期: 运行时动态挂载新数据源、建表操作并在测试后优雅排空注销")
    void testDynamicDataSourceLifecycle() {
        String dynamicDsKey = "tenant_dynamic_test";
        String jdbcUrl = "jdbc:h2:mem:tenant_dynamic_db;DB_CLOSE_DELAY=-1;MODE=MySQL";

        // 1. 动态挂载新数据源
        lifecycleScenarioService.registerDynamicDataSource(dynamicDsKey, jdbcUrl, "sa", "");

        DynamicRoutingDataSource dynamicRoutingDataSource = (DynamicRoutingDataSource) dataSource;
        assertThat(dynamicRoutingDataSource.getDataSources()).containsKey(dynamicDsKey);

        // 2. 在动态数据源中创建订单
        String dynamicOrderNo = "ORD-DYN-001";
        lifecycleScenarioService.createOrderInDynamicDataSource(dynamicDsKey, dynamicOrderNo);

        // 3. 优雅排空并注销数据源
        lifecycleScenarioService.unregisterDynamicDataSource(dynamicDsKey, true);
        assertThat(dynamicRoutingDataSource.getDataSources()).doesNotContainKey(dynamicDsKey);
    }
}
