package io.github.atengk.boot.job.xxl.template;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.job.xxl.tenant.TenantListProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 多租户遍历执行模板单测
 *
 * @author Ateng
 * @since 2026-10-31
 */
class TenantJobTemplateTest {

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        UserContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("测试空租户列表：安全跳过且不报错")
    void testEmptyTenantList() {
        TenantJobTemplate template = new TenantJobTemplate(() -> Collections.emptyList());
        BatchExecutionResult result = template.executeForEachTenant(tenantId -> {
        });

        assertThat(result.getTotalCount()).isEqualTo(0);
        assertThat(result.getSuccessCount()).isEqualTo(0);
        assertThat(result.getFailCount()).isEqualTo(0);
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("测试正常租户遍历：每个租户动作在独立租户沙箱中执行，且执行后现场无残留")
    void testNormalTenantIteration() {
        List<String> recordedTenants = new ArrayList<>();
        TenantListProvider provider = () -> Arrays.asList("tenant-100", "tenant-200", "tenant-300");
        TenantJobTemplate template = new TenantJobTemplate(provider);

        BatchExecutionResult result = template.executeForEachTenant(tenantId -> {
            // 断言执行期间当前线程租户已被安全绑定
            assertThat(TenantContextHolder.getTenantId()).isEqualTo(tenantId);
            recordedTenants.add(tenantId);
        });

        assertThat(recordedTenants).containsExactly("tenant-100", "tenant-200", "tenant-300");
        assertThat(result.getTotalCount()).isEqualTo(3);
        assertThat(result.getSuccessCount()).isEqualTo(3);
        assertThat(result.getFailCount()).isEqualTo(0);
        assertThat(result.isSuccess()).isTrue();

        // 断言执行结束后当前线程租户已彻底清理
        assertThat(TenantContextHolder.getTenantId()).isNull();
    }

    @Test
    @DisplayName("测试容错继续模式 (failFast=false)：个别租户异常不阻断后续租户执行")
    void testTolerantIterationWhenOneFails() {
        List<String> processedTenants = new ArrayList<>();
        TenantJobTemplate template = new TenantJobTemplate(null);
        List<String> tenants = Arrays.asList("t1", "t2-error", "t3");

        BatchExecutionResult result = template.executeForEachTenant(tenants, false, tenantId -> {
            if ("t2-error".equals(tenantId)) {
                throw new IllegalStateException("模拟租户2异常");
            }
            processedTenants.add(tenantId);
        });

        assertThat(processedTenants).containsExactly("t1", "t3");
        assertThat(result.getTotalCount()).isEqualTo(3);
        assertThat(result.getSuccessCount()).isEqualTo(2);
        assertThat(result.getFailCount()).isEqualTo(1);
        assertThat(result.isSuccess()).isFalse();
    }

    @Test
    @DisplayName("测试快速失败模式 (failFast=true)：任一租户异常立即中断并抛出 BusinessException")
    void testFailFastIteration() {
        AtomicInteger count = new AtomicInteger();
        TenantJobTemplate template = new TenantJobTemplate(null);
        List<String> tenants = Arrays.asList("t1", "t2-fail", "t3");

        assertThatThrownBy(() -> template.executeForEachTenant(tenants, true, tenantId -> {
            count.incrementAndGet();
            if ("t2-fail".equals(tenantId)) {
                throw new RuntimeException("数据库连接不可用");
            }
        })).isInstanceOf(BusinessException.class)
                .hasMessageContaining("t2-fail");

        // 断言 t3 未被执行
        assertThat(count.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("测试并发多租户遍历：吞吐量与沙箱隔离，子任务持有独立租户上下文并透传父线程四维快照")
    void testParallelTenantIterationThroughputAndContextIsolation() {
        String parentTraceId = "trace-parallel-tenant-888";
        String parentUser = "system-tenant-operator";
        MDC.put("traceId", parentTraceId);
        UserContextHolder.set(parentUser);

        List<String> tenants = Arrays.asList("tenant-A", "tenant-B", "tenant-C", "tenant-D", "tenant-E");
        ConcurrentHashMap<String, String> observedTenants = new ConcurrentHashMap<>();
        TenantJobTemplate template = new TenantJobTemplate(null);

        BatchExecutionResult result = template.executeForEachTenantParallel(tenants, 3, false, tenantId -> {
            // 断言子线程严格持有当前租户且隔离执行
            assertThat(TenantContextHolder.getTenantId()).isEqualTo(tenantId);
            // 断言父线程上下文正确透传至子线程
            assertThat(MDC.get("traceId")).isEqualTo(parentTraceId);
            assertThat(UserContextHolder.getExplicit()).isEqualTo(parentUser);
            observedTenants.put(tenantId, Thread.currentThread().getName());
        });

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getTotalCount()).isEqualTo(5);
        assertThat(result.getSuccessCount()).isEqualTo(5);
        assertThat(result.getFailCount()).isEqualTo(0);
        assertThat(observedTenants.keySet()).containsExactlyInAnyOrderElementsOf(tenants);

        // 断言父线程执行结束后租户上下文不被污染
        assertThat(TenantContextHolder.getTenantId()).isNull();
        assertThat(MDC.get("traceId")).isEqualTo(parentTraceId);
        assertThat(UserContextHolder.getExplicit()).isEqualTo(parentUser);
    }

    @Test
    @DisplayName("测试并发多租户背压保护：在途并发切片数严格受限在 maxConcurrency 内")
    void testParallelBackpressureThrottling() {
        int maxConcurrency = 2;
        AtomicInteger activeThreads = new AtomicInteger(0);
        AtomicInteger maxObservedConcurrency = new AtomicInteger(0);

        List<String> tenants = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            tenants.add("tenant-" + i);
        }

        TenantJobTemplate template = new TenantJobTemplate(null);
        BatchExecutionResult result = template.executeForEachTenantParallel(tenants, maxConcurrency, false, tenantId -> {
            int current = activeThreads.incrementAndGet();
            maxObservedConcurrency.accumulateAndGet(current, Math::max);
            try {
                Thread.sleep(25); // 模拟租户处理耗时
            } catch (InterruptedException ignored) {
            } finally {
                activeThreads.decrementAndGet();
            }
        });

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getTotalCount()).isEqualTo(10);
        assertThat(maxObservedConcurrency.get()).isLessThanOrEqualTo(maxConcurrency);
    }

    @Test
    @DisplayName("测试并发多租户容错模式 (failFast=false)：失败租户记录至 failedItems 且其余租户正常完成")
    void testParallelErrorCollectionWhenFailFastFalse() {
        List<String> tenants = Arrays.asList("t-ok1", "t-fail1", "t-ok2", "t-fail2", "t-ok3");
        CopyOnWriteArrayList<String> succeededTenants = new CopyOnWriteArrayList<>();

        TenantJobTemplate template = new TenantJobTemplate(null);
        BatchExecutionResult result = template.executeForEachTenantParallel(tenants, 3, false, tenantId -> {
            if (tenantId.startsWith("t-fail")) {
                throw new RuntimeException("模拟租户异常: " + tenantId);
            }
            succeededTenants.add(tenantId);
        });

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getTotalCount()).isEqualTo(5);
        assertThat(result.getSuccessCount()).isEqualTo(3);
        assertThat(result.getFailCount()).isEqualTo(2);
        assertThat(succeededTenants).containsExactlyInAnyOrder("t-ok1", "t-ok2", "t-ok3");
        assertThat(result.getFailedItems()).containsExactlyInAnyOrder("t-fail1", "t-fail2");
    }

    @Test
    @DisplayName("测试并发多租户快速失败模式 (failFast=true)：任一租户报错抛出 BusinessException 并熔断")
    void testParallelFailFastAbortsSubsequentTenants() {
        List<String> tenants = Arrays.asList("t-first", "t-crash", "t-subsequent");
        TenantJobTemplate template = new TenantJobTemplate(null);

        assertThatThrownBy(() -> template.executeForEachTenantParallel(tenants, 1, true, tenantId -> {
            if ("t-crash".equals(tenantId)) {
                throw new IllegalStateException("租户发生不可恢复异常");
            }
        })).isInstanceOf(BusinessException.class)
                .hasMessageContaining("租户发生不可恢复异常");
    }

    @Test
    @DisplayName("测试通过 TenantListProvider 动态解析租户并并发执行")
    void testParallelDynamicResolutionViaTenantListProvider() {
        TenantListProvider provider = () -> Arrays.asList("dyn-tenant-1", "dyn-tenant-2", "dyn-tenant-3");
        TenantJobTemplate template = new TenantJobTemplate(provider);

        List<String> executed = new CopyOnWriteArrayList<>();
        BatchExecutionResult result = template.executeForEachTenantParallel(executed::add);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getTotalCount()).isEqualTo(3);
        assertThat(executed).containsExactlyInAnyOrder("dyn-tenant-1", "dyn-tenant-2", "dyn-tenant-3");
    }

    @Test
    @DisplayName("测试并发空租户列表处理：安全返回空结果且不触发线程调度")
    void testParallelEmptyTenantList() {
        TenantJobTemplate template = new TenantJobTemplate(null);
        BatchExecutionResult result = template.executeForEachTenantParallel(Collections.emptyList(), 4, false, t -> {
        });

        assertThat(result.getTotalCount()).isEqualTo(0);
        assertThat(result.isSuccess()).isTrue();
    }

}
