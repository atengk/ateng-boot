package io.github.atengk.boot.sample.datasource;

import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.baomidou.dynamic.datasource.creator.DataSourceProperty;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.util.CryptoUtil;
import io.github.atengk.boot.datasource.enums.BatchExecutionPolicy;
import io.github.atengk.boot.datasource.template.BatchExecutionResult;
import io.github.atengk.boot.datasource.template.DynamicDatasourceTemplate;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    private DynamicDatasourceTemplate dynamicDatasourceTemplate;

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
    @DisplayName("批量巡检容错隔离: 单库异常时 FAIL_SAFE 隔离失败继续遍历其余库")
    void testBatchTraversalWithFailureIsolation() {
        BatchExecutionResult result = dynamicDatasourceTemplate.forEachDataSource(
                BatchExecutionPolicy.FAIL_SAFE,
                dsKey -> {
                    if ("tenant_1001".equals(dsKey)) {
                        throw new IllegalStateException("模拟租户 1001 巡检异常");
                    }
                }
        );
        assertThat(result.hasFailures()).isTrue();
        assertThat(result.getFailureCount()).isEqualTo(1);
        assertThat(result.getSuccessCount()).isGreaterThanOrEqualTo(3);
        assertThat(result.getFailures().get(0).getTarget()).isEqualTo("tenant_1001");
        assertThat(result.getFailures().get(0).getCause().getMessage()).contains("模拟租户 1001 巡检异常");
    }

    @Test
    @DisplayName("批量巡检快速失败: FAIL_FAST 策略在遇到首个异常时熔断中断")
    void testBatchTraversalWithFailFast() {
        assertThatThrownBy(() ->
                dynamicDatasourceTemplate.forEachDataSource(
                        BatchExecutionPolicy.FAIL_FAST,
                        dsKey -> {
                            throw new IllegalArgumentException("快速失败中断");
                        }
                )
        ).hasMessageContaining("快速失败中断");
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

    @Test
    @DisplayName("零明文凭据安全与生命周期: 传入 enc:sm4: 密文密码完成真实连接池初始化与密码瞬态还原")
    void testDynamicDataSourceLifecycleWithSm4Password() {
        String dynamicDsKey = "tenant_sm4_enc_test";
        String jdbcUrl = "jdbc:h2:mem:tenant_sm4_enc_db;DB_CLOSE_DELAY=-1;MODE=MySQL";

        // 生成 SM4 密文密码 (空密码密文)
        String rawPass = "";
        byte[] sm4Key = CryptoUtil.generateKey(128);
        String sm4Ciphertext = CryptoUtil.encryptSm4Ecb(rawPass, sm4Key);
        String base64Sm4Key = CryptoUtil.encodeBase64(sm4Key);
        String sm4Encrypted = "enc:sm4:" + base64Sm4Key + ":" + sm4Ciphertext;

        // 1. 动态注册并初始化带密文密码的数据源
        lifecycleScenarioService.registerDynamicDataSource(dynamicDsKey, jdbcUrl, "sa", sm4Encrypted);

        DynamicRoutingDataSource dynamicRoutingDataSource = (DynamicRoutingDataSource) dataSource;
        assertThat(dynamicRoutingDataSource.getDataSources()).containsKey(dynamicDsKey);

        // 2. 验证能够正常执行 SQL 业务
        lifecycleScenarioService.createOrderInDynamicDataSource(dynamicDsKey, "ORD-SM4-001");

        // 3. 优雅排空注销
        lifecycleScenarioService.unregisterDynamicDataSource(dynamicDsKey, true);
        assertThat(dynamicRoutingDataSource.getDataSources()).doesNotContainKey(dynamicDsKey);
    }
}
