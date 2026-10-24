package io.github.atengk.boot.datasource.template;

import com.baomidou.dynamic.datasource.toolkit.DynamicDataSourceContextHolder;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.datasource.enums.BatchExecutionPolicy;
import io.github.atengk.boot.datasource.enums.MultiTenancyMode;
import io.github.atengk.boot.datasource.exception.TenantDataSourceNotFoundException;
import io.github.atengk.boot.datasource.properties.DatasourceProperties;
import io.github.atengk.boot.datasource.routing.AtengDynamicRoutingDataSource;
import io.github.atengk.boot.datasource.tenant.DefaultTenantDataSourceProvider;
import io.github.atengk.boot.datasource.tenant.TenantDataSourceProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

/**
 * 编程式切源对称门面 (DynamicDatasourceTemplate) 与全库批处理容错沙箱单元测试
 *
 * @author Ateng
 * @since 2026-10-24
 */
class DynamicDatasourceTemplateTest {

    private DataSource masterDataSource;
    private DataSource tenant1001DataSource;
    private DataSource tenant1002DataSource;

    private DatasourceProperties properties;
    private TenantDataSourceProvider provider;
    private AtengDynamicRoutingDataSource routingDataSource;
    private DynamicDatasourceTemplate template;

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

        template = new DynamicDatasourceTemplate(routingDataSource, properties, provider);
    }

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        DynamicDataSourceContextHolder.clear();
    }

    @Test
    @DisplayName("测试基础编程式切源执行与上下文恢复")
    void testCallInDataSourceAndRunInDataSource() {
        assertThat(template.getCurrentDataSourceKey()).isNull();

        String result = template.callInDataSource("tenant_1001", () -> {
            assertThat(template.getCurrentDataSourceKey()).isEqualTo("tenant_1001");
            return "success_1001";
        });

        assertThat(result).isEqualTo("success_1001");
        assertThat(template.getCurrentDataSourceKey()).isNull();

        AtomicBoolean executed = new AtomicBoolean(false);
        template.runInDataSource("tenant_1002", () -> {
            assertThat(template.getCurrentDataSourceKey()).isEqualTo("tenant_1002");
            executed.set(true);
        });

        assertThat(executed.get()).isTrue();
        assertThat(template.getCurrentDataSourceKey()).isNull();
    }

    @Test
    @DisplayName("测试嵌套编程式切源出入栈 LIFO 对称恢复")
    void testNestedStackSwitchingAndDeterministicRestoration() {
        assertThat(template.getCurrentDataSourceKey()).isNull();

        template.runInDataSource("master", () -> {
            assertThat(template.getCurrentDataSourceKey()).isEqualTo("master");

            template.runInDataSource("tenant_1001", () -> {
                assertThat(template.getCurrentDataSourceKey()).isEqualTo("tenant_1001");

                template.runInDataSource("tenant_1002", () -> {
                    assertThat(template.getCurrentDataSourceKey()).isEqualTo("tenant_1002");
                });

                // 内层 tenant_1002 退出后，中间层严格还原为 tenant_1001
                assertThat(template.getCurrentDataSourceKey()).isEqualTo("tenant_1001");
            });

            // 中层 tenant_1001 退出后，外层严格还原为 master
            assertThat(template.getCurrentDataSourceKey()).isEqualTo("master");
        });

        // 最外层退出后彻底清空
        assertThat(template.getCurrentDataSourceKey()).isNull();
    }

    @Test
    @DisplayName("测试执行异常时上下文在 finally 块中绝对可靠恢复，杜绝脏读泄漏")
    void testExceptionRestorationInCallInDataSource() {
        template.runInDataSource("master", () -> {
            assertThatThrownBy(() -> template.callInDataSource("tenant_1001", () -> {
                assertThat(template.getCurrentDataSourceKey()).isEqualTo("tenant_1001");
                throw new IllegalStateException("模拟业务操作异常");
            })).isInstanceOf(IllegalStateException.class).hasMessage("模拟业务操作异常");

            // 发生异常后，仍然可靠还原为上一级 master
            assertThat(template.getCurrentDataSourceKey()).isEqualTo("master");
        });

        assertThat(template.getCurrentDataSourceKey()).isNull();
    }

    @Test
    @DisplayName("测试指定租户切源执行 (runInTenantDataSource & callInTenantDataSource)")
    void testCallInTenantDataSource() {
        assertThat(TenantContextHolder.getTenantId()).isNull();
        assertThat(template.getCurrentDataSourceKey()).isNull();

        String result = template.callInTenantDataSource("1001", () -> {
            assertThat(TenantContextHolder.getTenantId()).isEqualTo("1001");
            assertThat(template.getCurrentDataSourceKey()).isEqualTo("tenant_1001");
            return "tenant_1001_result";
        });

        assertThat(result).isEqualTo("tenant_1001_result");
        assertThat(TenantContextHolder.getTenantId()).isNull();
        assertThat(template.getCurrentDataSourceKey()).isNull();

        AtomicBoolean executed = new AtomicBoolean(false);
        template.runInTenantDataSource("1002", () -> {
            assertThat(TenantContextHolder.getTenantId()).isEqualTo("1002");
            assertThat(template.getCurrentDataSourceKey()).isEqualTo("tenant_1002");
            executed.set(true);
        });

        assertThat(executed.get()).isTrue();
        assertThat(TenantContextHolder.getTenantId()).isNull();
        assertThat(template.getCurrentDataSourceKey()).isNull();
    }

    @Test
    @DisplayName("测试严格模式 (STRICT) 下未注册租户切源快速失败并抛出 TenantDataSourceNotFoundException")
    void testCallInTenantDataSource_StrictNotFound() {
        properties.getMultiTenancy().setMode(MultiTenancyMode.STRICT);

        assertThatThrownBy(() -> template.callInTenantDataSource("9999", () -> "should_not_reach"))
                .isInstanceOf(TenantDataSourceNotFoundException.class)
                .hasMessageContaining("未找到租户 [9999] 对应的物理数据源 [tenant_9999]");

        assertThat(TenantContextHolder.getTenantId()).isNull();
        assertThat(template.getCurrentDataSourceKey()).isNull();
    }

    @Test
    @DisplayName("测试回退模式 (FALLBACK_MASTER) 下未注册租户平滑路由至主数据源并保留租户上下文")
    void testCallInTenantDataSource_FallbackMaster() {
        properties.getMultiTenancy().setMode(MultiTenancyMode.FALLBACK_MASTER);

        String result = template.callInTenantDataSource("9999", () -> {
            assertThat(TenantContextHolder.getTenantId()).isEqualTo("9999");
            assertThat(template.getCurrentDataSourceKey()).isEqualTo("master");
            return "fallback_result";
        });

        assertThat(result).isEqualTo("fallback_result");
        assertThat(TenantContextHolder.getTenantId()).isNull();
        assertThat(template.getCurrentDataSourceKey()).isNull();
    }

    @Test
    @DisplayName("测试元数据与数据源存在性查询接口")
    void testQueryMethods() {
        Set<String> keys = template.getDataSourceKeys();
        assertThat(keys).containsExactlyInAnyOrder("master", "tenant_1001", "tenant_1002");

        assertThat(template.containsDataSource("master")).isTrue();
        assertThat(template.containsDataSource("tenant_1001")).isTrue();
        assertThat(template.containsDataSource("unknown")).isFalse();
        assertThat(template.containsDataSource(null)).isFalse();
    }

    @Test
    @DisplayName("测试全数据源批量遍历 (forEachDataSource) 在 FAIL_SAFE 策略下的聚合容错")
    void testForEachDataSource_FailSafe() {
        List<String> visited = new ArrayList<>();

        BatchExecutionResult result = template.forEachDataSource(dsKey -> {
            visited.add(dsKey);
            if ("tenant_1001".equals(dsKey)) {
                throw new RuntimeException("数据源 tenant_1001 访问超时");
            }
        });

        assertThat(visited).contains("master", "tenant_1001", "tenant_1002");
        assertThat(result.getTotalCount()).isEqualTo(3);
        assertThat(result.getSuccessCount()).isEqualTo(2);
        assertThat(result.getFailureCount()).isEqualTo(1);
        assertThat(result.hasFailures()).isTrue();
        assertThat(result.isAllSuccess()).isFalse();
        assertThat(result.getFailedTargets()).containsExactly("tenant_1001");
        assertThat(result.getFailures().get(0).getErrorMessage()).isEqualTo("数据源 tenant_1001 访问超时");
        assertThat(result.getFailures().get(0).getCause()).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("测试全数据源批量遍历 (forEachDataSource) 在 FAIL_FAST 策略下遇错立即中断")
    void testForEachDataSource_FailFast() {
        List<String> visited = new ArrayList<>();

        // 自定义有序数据源遍历
        List<String> order = List.of("master", "tenant_1001", "tenant_1002");

        assertThatThrownBy(() -> template.forEachDataSource(order, BatchExecutionPolicy.FAIL_FAST, dsKey -> {
            visited.add(dsKey);
            if ("tenant_1001".equals(dsKey)) {
                throw new IllegalStateException("FAIL_FAST 阻断异常");
            }
        })).isInstanceOf(IllegalStateException.class).hasMessage("FAIL_FAST 阻断异常");

        assertThat(visited).containsExactly("master", "tenant_1001");
        assertThat(template.getCurrentDataSourceKey()).isNull();
    }

    @Test
    @DisplayName("测试全租户批量遍历 (forEachTenant) 自动探测与 FAIL_SAFE 容错隔离")
    void testForEachTenant_AutoDiscoveryAndFailSafe() {
        Map<String, String> tenantContextMap = new LinkedHashMap<>();

        BatchExecutionResult result = template.forEachTenant((tenantId, dsKey) -> {
            // 验证执行沙箱内租户与数据源上下文均已就绪
            assertThat(TenantContextHolder.getTenantId()).isEqualTo(tenantId);
            assertThat(template.getCurrentDataSourceKey()).isEqualTo(dsKey);

            tenantContextMap.put(tenantId, dsKey);

            if ("1001".equals(tenantId)) {
                throw new IllegalArgumentException("租户 1001 账单异常");
            }
        });

        assertThat(tenantContextMap).containsEntry("1001", "tenant_1001");
        assertThat(tenantContextMap).containsEntry("1002", "tenant_1002");
        assertThat(result.getTotalCount()).isEqualTo(2);
        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getFailureCount()).isEqualTo(1);
        assertThat(result.getFailedTargets()).containsExactly("1001");

        // 验证执行完毕后上下文绝对纯净
        assertThat(TenantContextHolder.getTenantId()).isNull();
        assertThat(template.getCurrentDataSourceKey()).isNull();
    }

    @Test
    @DisplayName("测试全租户批量遍历 (forEachTenant) 在 FAIL_FAST 策略下遇错立即中断")
    void testForEachTenant_FailFast() {
        List<String> tenantIds = List.of("1001", "1002");
        List<String> visited = new ArrayList<>();

        assertThatThrownBy(() -> template.forEachTenant(tenantIds, BatchExecutionPolicy.FAIL_FAST, (tenantId, dsKey) -> {
            visited.add(tenantId);
            if ("1001".equals(tenantId)) {
                throw new RuntimeException("租户 1001 崩溃");
            }
        })).isInstanceOf(RuntimeException.class).hasMessage("租户 1001 崩溃");

        assertThat(visited).containsExactly("1001");
        assertThat(TenantContextHolder.getTenantId()).isNull();
        assertThat(template.getCurrentDataSourceKey()).isNull();
    }
}
