package io.github.atengk.boot.job.xxl.template;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.job.xxl.tenant.TenantListProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
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

}
