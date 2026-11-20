package io.github.atengk.boot.job.snail.template;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.job.snail.context.SnailJobContext;
import io.github.atengk.boot.job.snail.context.SnailJobContextHolder;
import io.github.atengk.boot.job.snail.tenant.TenantListProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

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
        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
        SnailJobContextHolder.remove();
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

    @Test
    @DisplayName("executeForEachTenantWithResult: 返回结构化 BatchExecutionResult<String> 并触发全局/显式回调")
    void testExecuteForEachTenantWithResult() {
        List<FailedBatch<String>> globalFailures = new CopyOnWriteArrayList<>();
        List<FailedBatch<String>> explicitFailures = new CopyOnWriteArrayList<>();

        TenantJobTemplate template = new TenantJobTemplate(
                () -> List.of("T-A", "T-BAD", "T-B"),
                null,
                null,
                4,
                false,
                500,
                (BatchFailureHandler<String>) globalFailures::add
        );

        BatchExecutionResult<String> res1 = template.executeForEachTenantWithResult(false, tenantId -> {
            if ("T-BAD".equals(tenantId)) {
                throw new IllegalStateException("bill check failed");
            }
        });
        assertThat(res1.getTotalCount()).isEqualTo(3);
        assertThat(res1.getSuccessCount()).isEqualTo(2);
        assertThat(res1.getFailCount()).isEqualTo(1);
        assertThat(res1.getFailedBatches()).hasSize(1);
        assertThat(res1.getFailedBatches().get(0).cursorStart()).isEqualTo("T-BAD");
        assertThat(globalFailures).hasSize(1);

        BatchExecutionResult<String> res2 = template.executeForEachTenantWithResult(
                List.of("T-X", "T-ERR"),
                false,
                tenantId -> {
                    if ("T-ERR".equals(tenantId)) {
                        throw new IllegalStateException("explicit failed");
                    }
                },
                explicitFailures::add
        );
        assertThat(res2.getFailCount()).isEqualTo(1);
        assertThat(explicitFailures).hasSize(1);
        assertThat(globalFailures).hasSize(1); // 显式回调优先且全局不重复触发
    }

    @Test
    @DisplayName("executeForEachTenantParallel: Semaphore 背压限流与四维及 SnailJobContext 完整透传")
    void testExecuteForEachTenantParallelBackpressureAndContext() {
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(6);
        pool.setMaxPoolSize(6);
        pool.setThreadNamePrefix("tenant-parallel-test-");
        pool.initialize();

        try {
            TenantJobTemplate template = new TenantJobTemplate(
                    () -> List.of("T1", "T2", "T3", "T4", "T5", "T6"),
                    null,
                    pool,
                    2,
                    false,
                    500,
                    null
            );

            MDC.put("traceId", "trace-tenant-parallel");
            UserContextHolder.set("operator-parallel");
            SnailJobContext jobContext = new SnailJobContext();
            jobContext.setJobId(777L);
            jobContext.appendWfContext("shard", "0/2");
            SnailJobContextHolder.set(jobContext);

            AtomicInteger inFlight = new AtomicInteger(0);
            AtomicInteger peak = new AtomicInteger(0);
            List<String> observed = new CopyOnWriteArrayList<>();

            BatchExecutionResult<String> result = template.executeForEachTenantParallel(
                    2,
                    false,
                    tenantId -> {
                        int active = inFlight.incrementAndGet();
                        peak.accumulateAndGet(active, Math::max);
                        try {
                            Thread.sleep(25);
                            observed.add(tenantId + "|" + TenantContextHolder.getTenantId()
                                    + "|" + MDC.get("traceId")
                                    + "|" + UserContextHolder.getExplicit()
                                    + "|" + (SnailJobContextHolder.get() != null ? SnailJobContextHolder.get().getJobId() : "null"));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            inFlight.decrementAndGet();
                        }
                    }
            );

            assertThat(result.isSuccess()).isTrue();
            assertThat(result.getSuccessCount()).isEqualTo(6);
            assertThat(peak.get()).isLessThanOrEqualTo(2);
            assertThat(observed).hasSize(6);
            for (String row : observed) {
                String[] parts = row.split("\\|");
                assertThat(parts[1]).isEqualTo(parts[0]);
                assertThat(parts[2]).isEqualTo("trace-tenant-parallel");
                assertThat(parts[3]).isEqualTo("operator-parallel");
                assertThat(parts[4]).isEqualTo("777");
            }
        } finally {
            pool.shutdown();
        }
    }

    @Test
    @DisplayName("executeForEachTenantParallel: failFast=true 快速失败熔断与 failFast=false 容错收集")
    void testExecuteForEachTenantParallelFailFastAndTolerant() {
        ThreadPoolTaskExecutor pool = new ThreadPoolTaskExecutor();
        pool.setCorePoolSize(2);
        pool.setMaxPoolSize(2);
        pool.initialize();

        try {
            TenantJobTemplate template = new TenantJobTemplate(null, null, pool, 2, false, 500, null);
            List<FailedBatch<String>> captured = new CopyOnWriteArrayList<>();

            BatchExecutionResult<String> tolerant = template.executeForEachTenantParallel(
                    List.of("T1", "T2-FAIL", "T3"),
                    2,
                    false,
                    tenantId -> {
                        if ("T2-FAIL".equals(tenantId)) {
                            throw new IllegalStateException("parallel fail");
                        }
                    },
                    captured::add
            );
            assertThat(tolerant.getTotalCount()).isEqualTo(3);
            assertThat(tolerant.getSuccessCount()).isEqualTo(2);
            assertThat(tolerant.getFailCount()).isEqualTo(1);
            assertThat(tolerant.getFailedBatches()).hasSize(1);
            assertThat(captured).hasSize(1);

            assertThatThrownBy(() -> template.executeForEachTenantParallel(
                    List.of("T1", "T2-FAST-FAIL", "T3"),
                    2,
                    true,
                    tenantId -> {
                        if ("T2-FAST-FAIL".equals(tenantId)) {
                            throw new IllegalStateException("fast abort");
                        }
                    }
            )).isInstanceOf(BusinessException.class)
                    .hasMessageContaining("fast abort");
        } finally {
            pool.shutdown();
        }
    }
}
