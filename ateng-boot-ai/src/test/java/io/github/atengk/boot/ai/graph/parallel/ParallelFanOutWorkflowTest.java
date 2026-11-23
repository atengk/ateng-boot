package io.github.atengk.boot.ai.graph.parallel;

import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import io.github.atengk.boot.ai.graph.StateSnapshotVo;
import io.github.atengk.boot.ai.graph.WorkflowStatus;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 并行扇出-汇聚认知状态图 ParallelFanOutWorkflow 单元测试
 *
 * @author Ateng
 * @since 2026-11-22
 */
@DisplayName("ParallelFanOutWorkflow 并行扇出认知图单元测试")
class ParallelFanOutWorkflowTest {

    @AfterEach
    void tearDown() {
        MDC.clear();
        UserContextHolder.clear();
        TenantContextHolder.clear();
    }

    private ThreadPoolTaskExecutor createManagedExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(8);
        executor.setThreadNamePrefix("parallel-test-");
        executor.initialize();
        return executor;
    }

    @Test
    @DisplayName("多路分支真实并发执行并 100% 透传四维上下文（MDC/User/TenantId/IgnoreTenant），汇聚产出终态结果")
    void shouldExecuteBranchesConcurrentlyPropagateContextAndAggregate() {
        ThreadPoolTaskExecutor executor = createManagedExecutor();
        try {
            CountDownLatch barrier = new CountDownLatch(3);
            Map<String, String> capturedContexts = new ConcurrentHashMap<>();

            ParallelFanOutWorkflow<ParallelState> workflow = new ParallelFanOutWorkflow<>(
                    "concurrent-context-flow", ParallelState::new, BranchFailureStrategy.FAIL_FAST, executor) {
                @Override
                protected List<ParallelBranch<ParallelState>> registerBranches() {
                    return List.of(
                            ParallelBranch.of("risk", "风控核验", state -> waitAndCapture("risk", "LOW_RISK", barrier, capturedContexts)),
                            ParallelBranch.of("inventory", "库存锁定", state -> waitAndCapture("inventory", "STOCK_RESERVED", barrier, capturedContexts)),
                            ParallelBranch.of("credit", "授信评估", state -> waitAndCapture("credit", 50000, barrier, capturedContexts))
                    );
                }

                @Override
                protected Object aggregate(ParallelState state, Map<String, Object> branchResults) {
                    return "汇聚结论=" + branchResults.get("risk") + "|" + branchResults.get("inventory") + "|" + branchResults.get("credit");
                }
            };

            MDC.put("traceId", "trace-parallel-999");
            UserContextHolder.setUserId("user-88");
            TenantContextHolder.setTenantId("tenant-vip");

            StateSnapshotVo snapshot = workflow.start(new ParallelState());

            assertThat(snapshot.status()).isEqualTo(WorkflowStatus.COMPLETED);
            assertThat(snapshot.state().get(ParallelState.FINAL_OUTPUT.name()))
                    .isEqualTo("汇聚结论=LOW_RISK|STOCK_RESERVED|50000");

            assertThat(capturedContexts).containsEntry("risk", "trace-parallel-999|user-88|tenant-vip|false")
                    .containsEntry("inventory", "trace-parallel-999|user-88|tenant-vip|false")
                    .containsEntry("credit", "trace-parallel-999|user-88|tenant-vip|false");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("FAIL_FAST 策略下分支超时立即熔断并物理取消慢分支，保留 TimeoutException 因果链")
    void shouldFailFastOnBranchTimeoutAndCancelSiblings() {
        ThreadPoolTaskExecutor executor = createManagedExecutor();
        try {
            AtomicBoolean slowBranchInterrupted = new AtomicBoolean(false);
            CountDownLatch slowStarted = new CountDownLatch(1);

            ParallelFanOutWorkflow<ParallelState> timeoutWorkflow = new ParallelFanOutWorkflow<>(
                    "fail-fast-timeout", ParallelState::new, BranchFailureStrategy.FAIL_FAST, executor) {
                @Override
                protected List<ParallelBranch<ParallelState>> registerBranches() {
                    return List.of(
                            ParallelBranch.of("fastTimeoutBranch", Duration.ofMillis(40), state -> {
                                Thread.sleep(1000);
                                return "never";
                            }),
                            ParallelBranch.of("slowSibling", Duration.ofSeconds(5), state -> {
                                slowStarted.countDown();
                                try {
                                    Thread.sleep(2000);
                                } catch (InterruptedException e) {
                                    slowBranchInterrupted.set(true);
                                    Thread.currentThread().interrupt();
                                }
                                return "sibling";
                            })
                    );
                }

                @Override
                protected Object aggregate(ParallelState state, Map<String, Object> branchResults) {
                    return "should-not-reach";
                }
            };

            long startNs = System.nanoTime();
            assertThatThrownBy(() -> timeoutWorkflow.start(new ParallelState()))
                    .isInstanceOf(AiException.class)
                    .satisfies(e -> assertThat(((AiException) e).getResultCode()).isEqualTo(AiResultCode.AI_TIMEOUT))
                    .hasCauseInstanceOf(TimeoutException.class);
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs);
            assertThat(elapsedMs).isLessThan(1200L);
            if (slowStarted.getCount() == 0) {
                // 等待中断标志短暂传播
                long deadline = System.currentTimeMillis() + 300;
                while (!slowBranchInterrupted.get() && System.currentTimeMillis() < deadline) {
                    Thread.sleep(10);
                }
                assertThat(slowBranchInterrupted.get()).isTrue();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("FAIL_FAST 策略下分支异常立即抛出语义化 AiException 且保留原始 cause")
    void shouldFailFastOnBranchExceptionPreservingCause() {
        ThreadPoolTaskExecutor executor = createManagedExecutor();
        try {
            ParallelFanOutWorkflow<ParallelState> errorWorkflow = new ParallelFanOutWorkflow<>(
                    "fail-fast-error", ParallelState::new, BranchFailureStrategy.FAIL_FAST, executor) {
                @Override
                protected List<ParallelBranch<ParallelState>> registerBranches() {
                    return List.of(
                            ParallelBranch.of("boom", state -> {
                                throw new IllegalStateException("下游风控拒绝连接");
                            }),
                            ParallelBranch.of("healthy", state -> {
                                Thread.sleep(1500);
                                return "ok";
                            })
                    );
                }

                @Override
                protected Object aggregate(ParallelState state, Map<String, Object> branchResults) {
                    return "unused";
                }
            };

            long startNs = System.nanoTime();
            assertThatThrownBy(() -> errorWorkflow.start(new ParallelState()))
                    .isInstanceOf(AiException.class)
                    .satisfies(e -> assertThat(((AiException) e).getResultCode()).isEqualTo(AiResultCode.AI_GRAPH_EXECUTION_ERROR))
                    .hasCauseInstanceOf(IllegalStateException.class);
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNs)).isLessThan(1000L);
        } finally {
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("BEST_EFFORT 策略下记录结构化 branchErrors 并汇聚其余成功分支产出")
    @SuppressWarnings("unchecked")
    void shouldDegradeGracefullyUnderBestEffortAndRecordBranchErrors() {
        ThreadPoolTaskExecutor executor = createManagedExecutor();
        try {
            ParallelFanOutWorkflow<ParallelState> bestEffortWorkflow = new ParallelFanOutWorkflow<>(
                    "best-effort-flow", ParallelState::new, BranchFailureStrategy.BEST_EFFORT, executor) {
                @Override
                protected List<ParallelBranch<ParallelState>> registerBranches() {
                    return List.of(
                            ParallelBranch.of("okBranch", Duration.ofSeconds(2), state -> "APPROVED"),
                            ParallelBranch.of("timeoutBranch", Duration.ofMillis(30), state -> {
                                Thread.sleep(500);
                                return "LATE";
                            }),
                            ParallelBranch.of("errorBranch", Duration.ofSeconds(2), state -> {
                                throw new IllegalArgumentException("额度服务异常");
                            })
                    );
                }

                @Override
                protected Object aggregate(ParallelState state, Map<String, Object> branchResults) {
                    Map<String, ParallelState.BranchError> errors = state.getBranchErrors();
                    return "ok=" + branchResults.get("okBranch") + ",degradedCount=" + errors.size();
                }
            };

            StateSnapshotVo snapshot = bestEffortWorkflow.start(new ParallelState());
            assertThat(snapshot.status()).isEqualTo(WorkflowStatus.COMPLETED);
            assertThat(snapshot.state().get(ParallelState.FINAL_OUTPUT.name()))
                    .isEqualTo("ok=APPROVED,degradedCount=2");

            Map<String, Object> rawErrors = (Map<String, Object>) snapshot.state().get(ParallelState.BRANCH_ERRORS.name());
            assertThat(rawErrors).containsKeys("timeoutBranch", "errorBranch");
        } finally {
            executor.shutdown();
        }
    }

    @Test
    @DisplayName("并发分支获得隔离状态快照副本互不污染，且非正超时阈值 Fail-Fast 拦截")
    void shouldIsolateStatePerBranchAndRejectNonPositiveTimeout() {
        assertThatThrownBy(() -> ParallelBranch.<ParallelState>builder()
                .name("badTimeout")
                .timeout(Duration.ZERO)
                .handler(s -> "ok")
                .build())
                .isInstanceOf(IllegalArgumentException.class);

        ThreadPoolTaskExecutor executor = createManagedExecutor();
        try {
            CountDownLatch barrier = new CountDownLatch(2);
            Map<String, Object> observedByReader = new ConcurrentHashMap<>();
            ParallelFanOutWorkflow<ParallelState> isolatedWorkflow = new ParallelFanOutWorkflow<>(
                    "isolation-flow",
                    ParallelState::new,
                    BranchFailureStrategy.FAIL_FAST,
                    executor) {
                @Override
                protected List<ParallelBranch<ParallelState>> registerBranches() {
                    return List.of(
                            ParallelBranch.of("mutator", s -> {
                                s.setFinalOutput("MUTATED_BY_SIBLING");
                                barrier.countDown();
                                barrier.await(2, TimeUnit.SECONDS);
                                return "DONE_MUTATE";
                            }),
                            ParallelBranch.of("reader", s -> {
                                barrier.countDown();
                                barrier.await(2, TimeUnit.SECONDS);
                                Thread.sleep(20);
                                observedByReader.put("val", s.getFinalOutput());
                                return "DONE_READ";
                            })
                    );
                }

                @Override
                protected Object aggregate(ParallelState state, Map<String, Object> branchResults) {
                    return branchResults.get("mutator") + "+" + branchResults.get("reader");
                }
            };

            ParallelState init = new ParallelState();
            init.setFinalOutput("INITIAL_VAL");
            StateSnapshotVo snapshot = isolatedWorkflow.start(init);
            assertThat(snapshot.status()).isEqualTo(WorkflowStatus.COMPLETED);
            assertThat(observedByReader.get("val")).isEqualTo("INITIAL_VAL");
        } finally {
            executor.shutdown();
        }
    }

    private static Object waitAndCapture(String branch,
                                         Object result,
                                         CountDownLatch barrier,
                                         Map<String, String> capturedContexts) throws InterruptedException {
        capturedContexts.put(branch,
                MDC.get("traceId") + "|"
                        + UserContextHolder.getUserId() + "|"
                        + TenantContextHolder.getTenantId() + "|"
                        + TenantContextHolder.isIgnoreTenant());
        barrier.countDown();
        if (!barrier.await(3, TimeUnit.SECONDS)) {
            throw new IllegalStateException("并行屏障超时，未真实并发执行");
        }
        return result;
    }
}
