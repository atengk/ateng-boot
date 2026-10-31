package io.github.atengk.boot.job.snail.template;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.job.snail.tenant.TenantListProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * {@link TenantJobTemplate} 单元测试
 *
 * <p>覆盖多租户沙箱隔离、现场恢复、SPI 动态解析与快速失败 / 容错继续机制，
 * 100% 离线自洽运行。</p>
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
    @DisplayName("executeForEachTenant: 空租户列表直接返回")
    void testEmptyTenants() {
        TenantJobTemplate template = new TenantJobTemplate(() -> Collections.emptyList());
        BatchExecutionResult result = template.executeForEachTenant(tenantId -> {});

        assertThat(result.getTotalCount()).isZero();
        assertThat(result.getSuccessCount()).isZero();
        assertThat(result.getFailCount()).isZero();
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("executeForEachTenant: 基于 TenantListProvider 顺序沙箱隔离执行与现场还原")
    void testSequentialTenantExecution() {
        TenantListProvider provider = () -> List.of("T1", "T2", "T3");
        TenantJobTemplate template = new TenantJobTemplate(provider);

        // 设置调用前的原始租户现场
        TenantContextHolder.setTenantId("ORIGINAL-TENANT");

        List<String> visitedTenants = new ArrayList<>();
        List<String> contextTenants = new ArrayList<>();

        BatchExecutionResult result = template.executeForEachTenant(tenantId -> {
            visitedTenants.add(tenantId);
            contextTenants.add(TenantContextHolder.getTenantId());
        });

        assertThat(result.getTotalCount()).isEqualTo(3);
        assertThat(result.getSuccessCount()).isEqualTo(3);
        assertThat(result.getFailCount()).isZero();
        assertThat(result.isSuccess()).isTrue();

        assertThat(visitedTenants).containsExactly("T1", "T2", "T3");
        // 验证执行体内严格处于各自沙箱租户中
        assertThat(contextTenants).containsExactly("T1", "T2", "T3");

        // 验证执行结束后外层现场完整恢复
        assertThat(TenantContextHolder.getTenantId()).isEqualTo("ORIGINAL-TENANT");
    }

    @Test
    @DisplayName("executeForEachTenant: failFast=false 容错继续执行所有租户")
    void testTolerantMode() {
        TenantJobTemplate template = new TenantJobTemplate();
        List<String> executed = new ArrayList<>();

        BatchExecutionResult result = template.executeForEachTenant(
                List.of("T1", "ERROR_TENANT", "T3"),
                false,
                tenantId -> {
                    executed.add(tenantId);
                    if ("ERROR_TENANT".equals(tenantId)) {
                        throw new RuntimeException("租户数据异常");
                    }
                }
        );

        assertThat(result.getTotalCount()).isEqualTo(3);
        assertThat(result.getSuccessCount()).isEqualTo(2);
        assertThat(result.getFailCount()).isEqualTo(1);
        assertThat(result.isSuccess()).isFalse();
        assertThat(executed).containsExactly("T1", "ERROR_TENANT", "T3");
    }

    @Test
    @DisplayName("executeForEachTenant: failFast=true 首个异常立即熔断中断")
    void testFailFastMode() {
        TenantJobTemplate template = new TenantJobTemplate();
        List<String> executed = new ArrayList<>();

        assertThatThrownBy(() -> template.executeForEachTenant(
                List.of("T1", "FAIL_TENANT", "T3"),
                true,
                tenantId -> {
                    executed.add(tenantId);
                    if ("FAIL_TENANT".equals(tenantId)) {
                        throw new IllegalStateException("支付配置未初始化");
                    }
                }
        )).isInstanceOf(BusinessException.class)
          .hasMessageContaining("FAIL_TENANT");

        assertThat(executed).containsExactly("T1", "FAIL_TENANT");
    }

    @Test
    @DisplayName("executeForEachTenant: 存在事务管理器时自动挂载 REQUIRES_NEW 事务沙箱")
    void testExecutionWithTransactionManager() {
        PlatformTransactionManager txManager = mock(PlatformTransactionManager.class);
        TransactionStatus status = mock(TransactionStatus.class);
        when(txManager.getTransaction(any())).thenReturn(status);

        @SuppressWarnings("unchecked")
        ObjectProvider<PlatformTransactionManager> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(txManager);

        TenantJobTemplate template = new TenantJobTemplate(null, provider);
        List<String> executed = new ArrayList<>();

        BatchExecutionResult result = template.executeForEachTenant(List.of("T1", "T2"), executed::add);

        assertThat(result.getTotalCount()).isEqualTo(2);
        assertThat(result.getSuccessCount()).isEqualTo(2);
        assertThat(executed).containsExactly("T1", "T2");

        ArgumentCaptor<TransactionDefinition> captor = ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(txManager, times(2)).getTransaction(captor.capture());
        assertThat(captor.getAllValues()).allMatch(def -> def.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        verify(txManager, times(2)).commit(status);
    }
}
