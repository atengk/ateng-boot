package io.github.atengk.boot.job.snail.template;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.job.snail.context.SnailJobContext;
import io.github.atengk.boot.job.snail.context.SnailJobContextHolder;
import io.github.atengk.boot.job.snail.model.ShardingContext;
import org.apache.ibatis.cursor.Cursor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link SnailShardingBatchTemplate} 单元测试
 *
 * <p>覆盖游标步长拉取、分片过滤、受管多线程并发与 4D 上下文透传、MyBatis-Plus 流式游标降级与快速失败防卫，
 * 100% 离线脱网自洽运行。</p>
 *
 * @author Ateng
 * @since 2026-10-31
 */
class SnailShardingBatchTemplateTest {

    private SnailShardingBatchTemplate template;

    @BeforeEach
    void setUp() {
        template = new SnailShardingBatchTemplate(Executors.newFixedThreadPool(4), 5, 4);
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
        TenantContextHolder.clear();
        UserContextHolder.clear();
        SnailJobContextHolder.remove();
    }

    @Test
    @DisplayName("executeStep: 空数据直接返回 0 条")
    void testExecuteStepEmpty() {
        BatchExecutionResult result = template.<Long, Long>executeStep(
                (lastId, batchSize) -> Collections.emptyList(),
                item -> item,
                batch -> {}
        );
        assertThat(result.getTotalCount()).isZero();
        assertThat(result.getSuccessCount()).isZero();
        assertThat(result.getFailCount()).isZero();
        assertThat(result.getBatchCount()).isZero();
        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("executeStep: 正常游标多批次拉取与顺序消费")
    void testExecuteStepMultipleBatches() {
        List<Long> mockDb = List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L, 11L, 12L);
        List<Long> consumed = new ArrayList<>();

        BatchExecutionResult result = template.executeStep(
                0L,
                5,
                (lastId, batchSize) -> {
                    long from = (lastId != null) ? lastId : 0L;
                    return mockDb.stream().filter(id -> id > from).limit(batchSize).toList();
                },
                id -> id,
                consumed::addAll
        );

        assertThat(result.getTotalCount()).isEqualTo(12);
        assertThat(result.getSuccessCount()).isEqualTo(12);
        assertThat(result.getFailCount()).isZero();
        assertThat(result.getBatchCount()).isEqualTo(3); // 5 + 5 + 2
        assertThat(result.isSuccess()).isTrue();
        assertThat(consumed).containsExactlyElementsOf(mockDb);
    }

    @Test
    @DisplayName("executeStepForEach: 逐条消费游标记录")
    void testExecuteStepForEach() {
        List<String> mockDb = List.of("A", "B", "C", "D");
        List<String> visited = new ArrayList<>();

        BatchExecutionResult result = template.<String, Integer>executeStepForEach(
                null,
                2,
                (lastId, batchSize) -> {
                    int start = (lastId != null) ? lastId + 1 : 0;
                    if (start >= mockDb.size()) {
                        return Collections.emptyList();
                    }
                    return mockDb.subList(start, Math.min(start + batchSize, mockDb.size()));
                },
                mockDb::indexOf,
                visited::add
        );

        assertThat(result.getTotalCount()).isEqualTo(4);
        assertThat(visited).containsExactly("A", "B", "C", "D");
    }

    @Test
    @DisplayName("executeShardedStep: 结合 ShardingContext 分片过滤不漏单")
    void testExecuteShardedStep() {
        // 总数据 0 ~ 9，共 10 条
        List<Long> mockDb = List.of(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L);
        ShardingContext shard1Of3 = new ShardingContext(1, 3); // 匹配 1, 4, 7

        List<Long> matched = new ArrayList<>();
        BatchExecutionResult result = template.executeShardedStep(
                shard1Of3,
                -1L,
                4,
                (lastId, batchSize) -> mockDb.stream().filter(id -> id > lastId).limit(batchSize).toList(),
                id -> id,
                matched::addAll
        );

        assertThat(result.getTotalCount()).isEqualTo(3);
        assertThat(result.getSuccessCount()).isEqualTo(3);
        assertThat(matched).containsExactly(1L, 4L, 7L);
    }

    @Test
    @DisplayName("executeStepParallel: 并发批处理及四维上下文 100% 透传与清理")
    void testExecuteStepParallelWithContext() {
        // 设置父线程四维上下文
        MDC.put("traceId", "PARENT-TRACE-999");
        TenantContextHolder.setTenantId("TENANT-ALPHA");
        UserContextHolder.set("USER-SUPER");

        SnailJobContext snailContext = new SnailJobContext();
        snailContext.setJobId(888L);
        snailContext.appendWfContext("env", "staging");
        SnailJobContextHolder.set(snailContext);

        List<Long> mockDb = List.of(10L, 20L, 30L, 40L, 50L, 60L, 70L, 80L);
        List<Long> parallelConsumed = new CopyOnWriteArrayList<>();
        List<String> threadTraces = new CopyOnWriteArrayList<>();
        List<String> threadTenants = new CopyOnWriteArrayList<>();
        List<String> threadUsers = new CopyOnWriteArrayList<>();
        List<String> threadWfParams = new CopyOnWriteArrayList<>();

        BatchExecutionResult<Long> result = template.executeStepParallel(
                0L,
                3,
                2,
                (lastId, batchSize) -> mockDb.stream().filter(id -> id > lastId).limit(batchSize).toList(),
                id -> id,
                batch -> {
                    parallelConsumed.addAll(batch);
                    threadTraces.add(MDC.get("traceId"));
                    threadTenants.add(TenantContextHolder.getTenantId());
                    threadUsers.add(UserContextHolder.getExplicit());
                    if (SnailJobContextHolder.get() != null) {
                        threadWfParams.add(SnailJobContextHolder.get().getWfParam("env", String.class));
                    }
                }
        );

        assertThat(result.getTotalCount()).isEqualTo(8);
        assertThat(result.getSuccessCount()).isEqualTo(8);
        assertThat(parallelConsumed).containsExactlyInAnyOrderElementsOf(mockDb);

        // 验证子线程中四维上下文成功透传
        assertThat(threadTraces).allMatch("PARENT-TRACE-999"::equals);
        assertThat(threadTenants).allMatch("TENANT-ALPHA"::equals);
        assertThat(threadUsers).allMatch("USER-SUPER"::equals);
        assertThat(threadWfParams).allMatch("staging"::equals);

        // 验证父线程上下文不受影响
        assertThat(MDC.get("traceId")).isEqualTo("PARENT-TRACE-999");
        assertThat(TenantContextHolder.getTenantId()).isEqualTo("TENANT-ALPHA");
        assertThat(UserContextHolder.getExplicit()).isEqualTo("USER-SUPER");
    }

    @Test
    @DisplayName("executeStepParallel: failFast=true 遇异常中断抛出")
    void testExecuteStepParallelFailFast() {
        List<Long> mockDb = List.of(1L, 2L, 3L, 4L, 5L);

        assertThatThrownBy(() -> template.executeStepParallel(
                0L,
                2,
                2,
                true,
                (lastId, batchSize) -> mockDb.stream().filter(id -> id > lastId).limit(batchSize).toList(),
                id -> id,
                batch -> {
                    if (batch.contains(3L)) {
                        throw new RuntimeException("模拟批处理消费异常");
                    }
                }
        )).isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("executeStepParallel: failFast=false 容错继续处理后续切片")
    void testExecuteStepParallelTolerant() {
        List<Long> mockDb = List.of(1L, 2L, 3L, 4L, 5L);
        List<Long> successfullyConsumed = new CopyOnWriteArrayList<>();

        BatchExecutionResult<Long> result = template.executeStepParallel(
                0L,
                2,
                2,
                false,
                (lastId, batchSize) -> mockDb.stream().filter(id -> id > lastId).limit(batchSize).toList(),
                id -> id,
                batch -> {
                    if (batch.contains(3L)) {
                        throw new RuntimeException("模拟偶发批次消费异常");
                    }
                    successfullyConsumed.addAll(batch);
                }
        );

        assertThat(result.getTotalCount()).isEqualTo(5);
        assertThat(result.getFailCount()).isEqualTo(2); // 包含 3L 的批次有 2 条数据 (3L, 4L)
        assertThat(result.getSuccessCount()).isEqualTo(3); // (1L, 2L) 和 (5L)
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.hasFailures()).isTrue();
        assertThat(result.getFailedBatchCount()).isEqualTo(1);
        assertThat(result.failedBatchCount()).isEqualTo(1);
        assertThat(result.getFailedBatches()).hasSize(1);
        FailedBatch<Long> failedBatch = result.getFailedBatches().get(0);
        assertThat(failedBatch.cursorStart()).isEqualTo(3L);
        assertThat(failedBatch.cursorEnd()).isEqualTo(4L);
        assertThat(failedBatch.itemCount()).isEqualTo(2);
        assertThat(failedBatch.throwable()).isInstanceOf(RuntimeException.class);
        assertThat(successfullyConsumed).containsExactlyInAnyOrder(1L, 2L, 5L);
    }

    @Test
    @DisplayName("executeStep: failFast=false 收集 FailedBatch 详细诊断数据")
    void testExecuteStepCaptureFailedBatches() {
        List<Long> mockDb = List.of(1L, 2L, 3L, 4L, 5L);
        List<Long> consumed = new ArrayList<>();

        BatchExecutionResult<Long> result = template.executeStep(
                0L,
                2,
                false,
                (lastId, batchSize) -> mockDb.stream().filter(id -> id > lastId).limit(batchSize).toList(),
                id -> id,
                batch -> {
                    if (batch.contains(3L)) {
                        throw new RuntimeException("模拟顺序批次消费异常");
                    }
                    consumed.addAll(batch);
                }
        );

        assertThat(result.getTotalCount()).isEqualTo(5);
        assertThat(result.getSuccessCount()).isEqualTo(3);
        assertThat(result.getFailCount()).isEqualTo(2);
        assertThat(result.hasFailures()).isTrue();
        assertThat(result.getFailedBatchCount()).isEqualTo(1);
        assertThat(result.getFailedBatches()).hasSize(1);

        FailedBatch<Long> failed = result.getFailedBatches().get(0);
        assertThat(failed.cursorStart()).isEqualTo(3L);
        assertThat(failed.cursorEnd()).isEqualTo(4L);
        assertThat(failed.batchIndex()).isEqualTo(2);
        assertThat(failed.shardIndex()).isNull();
        assertThat(failed.itemCount()).isEqualTo(2);
        assertThat(failed.throwable()).isInstanceOf(RuntimeException.class).hasMessage("模拟顺序批次消费异常");
        assertThat(consumed).containsExactly(1L, 2L, 5L);
    }

    @Test
    @DisplayName("executeShardedStep: failFast=false 分片场景下收集 FailedBatch 且保留 shardIndex")
    void testExecuteShardedStepCaptureFailedBatches() {
        List<Long> mockDb = List.of(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L);
        ShardingContext shard1Of2 = new ShardingContext(1, 2); // 匹配 1, 3, 5, 7, 9

        BatchExecutionResult<Long> result = template.executeShardedStep(
                shard1Of2,
                -1L,
                2,
                false,
                (lastId, batchSize) -> mockDb.stream().filter(id -> id > lastId).limit(batchSize).toList(),
                id -> id,
                batch -> {
                    if (batch.contains(5L)) {
                        throw new RuntimeException("分片 1 批次失败");
                    }
                }
        );

        assertThat(result.getTotalCount()).isEqualTo(5);
        assertThat(result.hasFailures()).isTrue();
        assertThat(result.getFailedBatches()).hasSize(1);
        FailedBatch<Long> failed = result.getFailedBatches().get(0);
        assertThat(failed.shardIndex()).isEqualTo(1);
        assertThat(failed.cursorStart()).isEqualTo(5L);
        assertThat(failed.itemCount()).isEqualTo(1); // 4L 被分片过滤后本批次仅含 5L
    }

    @Test
    @DisplayName("executeStreaming: 游标分批降级流式消费")
    void testExecuteStreamingWithMockCursor() {
        List<String> items = List.of("R1", "R2", "R3", "R4", "R5");
        MockCursor<String> cursor = new MockCursor<>(items);

        SnailMyBatisStreamingTemplate streamingTemplate = new SnailMyBatisStreamingTemplate(Executors.newFixedThreadPool(4), 5, 4);
        List<String> consumed = new ArrayList<>();
        BatchExecutionResult result = streamingTemplate.executeStreaming(
                () -> cursor,
                2,
                consumed::addAll
        );

        assertThat(result.getTotalCount()).isEqualTo(5);
        assertThat(result.getSuccessCount()).isEqualTo(5);
        assertThat(result.getBatchCount()).isEqualTo(3); // 2 + 2 + 1
        assertThat(consumed).containsExactlyElementsOf(items);
        assertThat(cursor.isClosed()).isTrue(); // 验证确定性闭合
    }

    @Test
    @DisplayName("managedPoolEnabled=false 时禁用 Spring 回捞并在调用方线程顺滑执行")
    void testManagedPoolDisabledExecutesOnCallerThreadWithoutSpringFallback() {
        SnailShardingBatchTemplate callerTemplate = new SnailShardingBatchTemplate(null, 10, 2, true, false);
        String callerThread = Thread.currentThread().getName();
        java.util.Set<String> workerThreads = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

        BatchExecutionResult<Long> result = callerTemplate.executeStepParallel(
                0L,
                (lastId, size) -> lastId == 0L ? List.of(1L, 2L, 3L) : Collections.emptyList(),
                id -> id,
                chunk -> workerThreads.add(Thread.currentThread().getName())
        );

        assertThat(result.getTotalCount()).isEqualTo(3);
        assertThat(result.getSuccessCount()).isEqualTo(3);
        assertThat(workerThreads).containsExactly(callerThread);
    }

    @Test
    @DisplayName("BatchFailureHandler SPI: 全局默认兜底与显式回调覆写互不重复")
    void testBatchFailureHandlerPrecedenceAndNoDuplicateInvocation() {
        List<FailedBatch<Long>> globalCaptured = new CopyOnWriteArrayList<>();
        List<FailedBatch<Long>> explicitCaptured = new CopyOnWriteArrayList<>();
        BatchFailureHandler<Long> globalHandler = globalCaptured::add;

        java.util.concurrent.ExecutorService testExecutor = Executors.newFixedThreadPool(2);
        try {
            SnailShardingBatchTemplate managedTemplate = new SnailShardingBatchTemplate(
                    testExecutor, 2, 2, false, false, 100, 500, globalHandler);

            List<Long> mockDb = List.of(1L, 2L, 3L, 4L);

            // 1. 未传显式回调：由全局默认 SPI 兜底触发
            BatchExecutionResult<Long> fallbackResult = managedTemplate.executeStep(
                    0L,
                    2,
                    false,
                    (lastId, size) -> mockDb.stream().filter(id -> id > lastId).limit(size).toList(),
                    id -> id,
                    chunk -> {
                        if (chunk.contains(1L)) {
                            throw new IllegalStateException("global fallback error");
                        }
                    }
            );
            assertThat(fallbackResult.getFailCount()).isEqualTo(2);
            assertThat(globalCaptured).hasSize(1);
            assertThat(globalCaptured.get(0).cursorStart()).isEqualTo(1L);

            // 2. 传入显式回调：显式回调优先触发，全局默认 SPI 不重复触发
            BatchExecutionResult<Long> overrideResult = managedTemplate.executeStep(
                    0L,
                    2,
                    false,
                    (lastId, size) -> mockDb.stream().filter(id -> id > lastId).limit(size).toList(),
                    id -> id,
                    chunk -> {
                        if (chunk.contains(3L)) {
                            throw new IllegalStateException("explicit override error");
                        }
                    },
                    explicitCaptured::add
            );
            assertThat(overrideResult.getFailCount()).isEqualTo(2);
            assertThat(explicitCaptured).hasSize(1);
            assertThat(explicitCaptured.get(0).cursorStart()).isEqualTo(3L);
            assertThat(globalCaptured).hasSize(1);
        } finally {
            testExecutor.shutdownNow();
        }
    }

    @Test
    @DisplayName("不可变派生视图: withFailFast / withProgressLogInterval / withMaxConcurrency 不污染原单例")
    void testImmutableDerivedViewsIsolation() {
        SnailShardingBatchTemplate base = new SnailShardingBatchTemplate(
                Runnable::run, 10, 4, true, false, 5000, 500, null);

        SnailShardingBatchTemplate tolerantView = base.withFailFast(false);
        SnailShardingBatchTemplate customIntervalView = tolerantView.withProgressLogInterval(2);
        SnailShardingBatchTemplate customConcurrencyView = customIntervalView.withMaxConcurrency(1);

        assertThat(base.isFailFast()).isTrue();
        assertThat(base.getProgressLogInterval()).isEqualTo(5000);
        assertThat(base.getDefaultMaxConcurrency()).isEqualTo(4);

        assertThat(tolerantView.isFailFast()).isFalse();
        assertThat(customIntervalView.getProgressLogInterval()).isEqualTo(2);
        assertThat(customConcurrencyView.getDefaultMaxConcurrency()).isEqualTo(1);
        assertThat(customConcurrencyView.isFailFast()).isFalse();

        // 原单例保持 failFast=true，派生视图保持 failFast=false
        List<Long> mockDb = List.of(1L, 2L, 3L);
        BatchExecutionResult<Long> viewResult = customConcurrencyView.executeStep(
                0L,
                1,
                (lastId, size) -> mockDb.stream().filter(id -> id > lastId).limit(size).toList(),
                id -> id,
                chunk -> {
                    if (chunk.contains(2L)) {
                        throw new RuntimeException("view fail-open");
                    }
                }
        );
        assertThat(viewResult.getFailCount()).isEqualTo(1);
        assertThat(viewResult.getSuccessCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("有界失败明细上限保护: MAX_FAILED_ITEMS = 500 截断防 OOM")
    void testBoundedFailedItemsTruncationAt500() {
        assertThat(BatchExecutionResult.MAX_FAILED_ITEMS).isEqualTo(500);

        List<Long> ids = new ArrayList<>(520);
        for (long i = 1; i <= 520; i++) {
            ids.add(i);
        }

        SnailShardingBatchTemplate boundedTemplate = template.withFailFast(false);
        BatchExecutionResult<Long> result = boundedTemplate.executeStepParallel(
                0L,
                1,
                4,
                false,
                (lastId, size) -> {
                    long from = lastId != null ? lastId : 0L;
                    return ids.stream().filter(id -> id > from).limit(size).toList();
                },
                id -> id,
                chunk -> {
                    throw new IllegalStateException("mass failure storm");
                }
        );

        assertThat(result.getTotalCount()).isEqualTo(520);
        assertThat(result.getFailCount()).isEqualTo(520);
        assertThat(result.getFailedBatchCount()).isEqualTo(500);
        assertThat(result.getFailedBatches()).hasSize(500);
        assertThat(result.isTruncatedFailures()).isTrue();
    }

    @Test
    @DisplayName("SnailMyBatisStreamingTemplate: 结构化 FailedBatch 返回、派生视图与 @Deprecated 兼容")
    void testStreamingTemplateStructuredFailuresAndDerivedViews() throws Exception {
        SnailMyBatisStreamingTemplate streaming = new SnailMyBatisStreamingTemplate(Runnable::run, 2, 2, false, false);
        SnailMyBatisStreamingTemplate derived = streaming
                .withFailFast(false)
                .withProgressLogInterval(0)
                .withMaxConcurrency(1);

        assertThat(derived).isInstanceOf(SnailMyBatisStreamingTemplate.class);
        assertThat(SnailMyBatisStreamingTemplate.class
                .getMethod("setStreamingQueryTemplate", io.github.atengk.boot.mybatisplus.util.StreamingQueryTemplate.class)
                .isAnnotationPresent(Deprecated.class)).isTrue();

        List<String> rows = List.of("A", "B", "C", "D");
        List<FailedBatch<Integer>> captured = new CopyOnWriteArrayList<>();

        BatchExecutionResult<Integer> result = derived.executeStreamingParallel(
                () -> new MockCursor<>(rows),
                2,
                1,
                batch -> {
                    if (batch.contains("C")) {
                        throw new IllegalStateException("streaming chunk error");
                    }
                },
                captured::add
        );

        assertThat(result.getTotalCount()).isEqualTo(4);
        assertThat(result.getSuccessCount()).isEqualTo(2);
        assertThat(result.getFailCount()).isEqualTo(2);
        assertThat(result.getFailedBatches()).hasSize(1);
        assertThat(result.getFailedBatches().get(0).getCause())
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("streaming chunk error");
        assertThat(captured).hasSize(1);
    }

    @Test
    @DisplayName("并发提交异常: RejectedExecutionException 发生时信号量许可与主链失败感知正常")
    void testParallelSubmitFailureReleasesSemaphorePermit() {
        AtomicInteger attempts = new AtomicInteger(0);
        Executor rejectingOnFirst = task -> {
            if (attempts.incrementAndGet() == 1) {
                throw new java.util.concurrent.RejectedExecutionException("simulated queue full");
            }
            task.run();
        };

        SnailShardingBatchTemplate rejectingTemplate = new SnailShardingBatchTemplate(
                rejectingOnFirst, 2, 1, false, true, 100, 500, null);
        List<Long> mockDb = List.of(1L, 2L);

        assertThatThrownBy(() -> rejectingTemplate.executeStepParallel(
                0L,
                1,
                1,
                (lastId, size) -> mockDb.stream().filter(id -> id > lastId).limit(size).toList(),
                id -> id,
                chunk -> {
                }
        )).isInstanceOf(java.util.concurrent.RejectedExecutionException.class);
    }

    /**
     * 纯内存 Mock MyBatis Cursor 实现
     */
    static class MockCursor<T> implements Cursor<T> {
        private final List<T> list;
        private boolean closed = false;

        public MockCursor(List<T> list) {
            this.list = list;
        }

        @Override
        public boolean isOpen() {
            return !closed;
        }

        @Override
        public boolean isConsumed() {
            return closed;
        }

        @Override
        public int getCurrentIndex() {
            return 0;
        }

        @Override
        public Iterator<T> iterator() {
            return list.iterator();
        }

        @Override
        public void close() {
            this.closed = true;
        }

        public boolean isClosed() {
            return closed;
        }
    }
}
