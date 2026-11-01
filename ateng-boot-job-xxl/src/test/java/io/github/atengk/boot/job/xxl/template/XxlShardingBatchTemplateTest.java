package io.github.atengk.boot.job.xxl.template;

import com.xxl.job.core.context.XxlJobContext;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.job.xxl.autoconfigure.AtengXxlJobAutoConfiguration;
import io.github.atengk.boot.job.xxl.model.ShardingContext;
import org.apache.ibatis.cursor.Cursor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 分片流式批处理模板 XxlShardingBatchTemplate 单元与并发端到端测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
@DisplayName("XxlShardingBatchTemplate 分片流式批处理模板测试")
class XxlShardingBatchTemplateTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(AtengXxlJobAutoConfiguration.class)
            .withPropertyValues("ateng.boot.job.xxl.admin-addresses=http://127.0.0.1:8080/xxl-job-admin");

    private XxlMyBatisStreamingTemplate template;

    @BeforeEach
    void setUp() {
        template = new XxlMyBatisStreamingTemplate();
    }

    @AfterEach
    void tearDown() {
        XxlJobContext.setXxlJobContext(null);
        TenantContextHolder.clear();
        UserContextHolder.clear();
        MDC.clear();
    }

    @Test
    @DisplayName("测试基础步长游标顺序消费：正确分批拉取并推进游标，直至全部消费完毕")
    void testStepPaginationSequential() {
        // 构造 2500 条数据 (id: 1~2500)
        List<MockRecord> database = new ArrayList<>();
        for (long i = 1; i <= 2500; i++) {
            database.add(new MockRecord(i, "order-" + i));
        }

        List<List<MockRecord>> receivedBatches = new ArrayList<>();

        BatchExecutionResult result = template.executeStep(
                0L,
                1000,
                (lastId, limit) -> {
                    long fromId = (lastId == null ? 0L : lastId);
                    return database.stream()
                            .filter(r -> r.id > fromId)
                            .limit(limit)
                            .toList();
                },
                r -> r.id,
                receivedBatches::add
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getTotalCount()).isEqualTo(2500);
        assertThat(result.getBatchCount()).isEqualTo(3);
        assertThat(receivedBatches).hasSize(3);
        assertThat(receivedBatches.get(0)).hasSize(1000);
        assertThat(receivedBatches.get(1)).hasSize(1000);
        assertThat(receivedBatches.get(2)).hasSize(500);
        assertThat(receivedBatches.get(2).get(499).id).isEqualTo(2500L);
    }

    @Test
    @DisplayName("测试空数据集与单批未满时的边界处理")
    void testStepPaginationEmptyAndPartial() {
        // 1. 空数据集
        BatchExecutionResult emptyResult = template.executeStep(
                0L,
                500,
                (lastId, limit) -> List.of(),
                r -> ((MockRecord) r).id,
                batch -> {}
        );
        assertThat(emptyResult.getTotalCount()).isEqualTo(0);
        assertThat(emptyResult.getBatchCount()).isEqualTo(0);

        // 2. 只有 150 条数据，小于批次大小 500
        List<MockRecord> smallList = List.of(new MockRecord(1L, "a"), new MockRecord(2L, "b"));
        BatchExecutionResult smallResult = template.executeStep(
                0L,
                500,
                (lastId, limit) -> lastId == 0L ? smallList : List.of(),
                r -> r.id,
                batch -> assertThat(batch).hasSize(2)
        );
        assertThat(smallResult.getTotalCount()).isEqualTo(2);
        assertThat(smallResult.getBatchCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("测试结合 ShardingContext 分片过滤：仅消费属于当前节点分片的数据，游标按底层全量前进")
    void testShardedStepFiltering() {
        // 构造 1000 条数据 (id 1~1000)
        List<MockRecord> database = new ArrayList<>();
        for (long i = 1; i <= 1000; i++) {
            database.add(new MockRecord(i, "item-" + i));
        }

        // 当前为 4 分片中的第 1 号分片 (id % 4 == 1)
        ShardingContext sharding = new ShardingContext(1, 4);
        List<Long> processedIds = new CopyOnWriteArrayList<>();

        BatchExecutionResult result = template.executeShardedStep(
                sharding,
                0L,
                200,
                (lastId, limit) -> {
                    long fromId = (lastId == null ? 0L : lastId);
                    return database.stream()
                            .filter(r -> r.id > fromId)
                            .limit(limit)
                            .toList();
                },
                r -> r.id,
                batch -> batch.forEach(r -> processedIds.add(r.id))
        );

        // 1000 条记录中模 4 余 1 的恰好有 250 条
        assertThat(result.getTotalCount()).isEqualTo(250);
        assertThat(processedIds).hasSize(250);
        assertThat(processedIds).allMatch(id -> id % 4 == 1);
        assertThat(processedIds.get(0)).isEqualTo(1L);
        assertThat(processedIds.get(processedIds.size() - 1)).isEqualTo(997L);
    }

    @Test
    @DisplayName("测试并行批处理与四维上下文透传：子线程 100% 继承 MDC、User、Tenant 与 XXL 日志句柄且终态绝对无泄漏")
    void testParallelBatchContextPropagationAndCleanup() {
        // 在父线程上编排四维上下文
        String parentTraceId = "trace-parallel-job-8888";
        String parentUser = "admin-operator";
        String parentTenantId = "tenant-sharding-999";
        String jobLogFileName = "2026-10-31/1001.log";

        MDC.put("traceId", parentTraceId);
        UserContextHolder.set(parentUser);
        TenantContextHolder.setTenantId(parentTenantId);
        XxlJobContext parentJobContext = new XxlJobContext(1001L, "param", 1L, System.currentTimeMillis(), jobLogFileName, 0, 1);
        XxlJobContext.setXxlJobContext(parentJobContext);

        // 构造 500 条数据，分 5 批处理
        List<MockRecord> database = new ArrayList<>();
        for (long i = 1; i <= 500; i++) {
            database.add(new MockRecord(i, "user-" + i));
        }

        ConcurrentHashMap<String, Boolean> contextVerification = new ConcurrentHashMap<>();

        BatchExecutionResult result = template.executeStepParallel(
                0L,
                100,
                4,
                (lastId, limit) -> {
                    long fromId = (lastId == null ? 0L : lastId);
                    return database.stream()
                            .filter(r -> r.id > fromId)
                            .limit(limit)
                            .toList();
                },
                r -> r.id,
                batch -> {
                    // 断言子线程严格持有父线程的四维上下文
                    assertThat(MDC.get("traceId")).isEqualTo(parentTraceId);
                    assertThat(UserContextHolder.get()).isEqualTo(parentUser);
                    assertThat(TenantContextHolder.getTenantId()).isEqualTo(parentTenantId);

                    XxlJobContext currentContext = XxlJobContext.getXxlJobContext();
                    assertThat(currentContext).isNotNull();
                    assertThat(currentContext.getJobId()).isEqualTo(1001L);
                    assertThat(currentContext.getJobParam()).isEqualTo("param");
                    assertThat(currentContext.getShardIndex()).isEqualTo(0);
                    assertThat(currentContext.getShardTotal()).isEqualTo(1);

                    contextVerification.put(Thread.currentThread().getName(), true);
                }
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getTotalCount()).isEqualTo(500);
        assertThat(result.getBatchCount()).isEqualTo(5);
        assertThat(contextVerification).isNotEmpty();

        // 终态严格断言父线程上下文无损
        assertThat(MDC.get("traceId")).isEqualTo(parentTraceId);
        assertThat(UserContextHolder.get()).isEqualTo(parentUser);
        assertThat(TenantContextHolder.getTenantId()).isEqualTo(parentTenantId);
        assertThat(XxlJobContext.getXxlJobContext()).isNotNull();
    }

    @Test
    @DisplayName("测试并行批处理背压保护：在途并发切片数严格受限在 maxConcurrency 内")
    void testParallelBackpressureLimit() {
        int maxConcurrency = 2;
        AtomicInteger activeThreads = new AtomicInteger(0);
        AtomicInteger maxObservedConcurrency = new AtomicInteger(0);

        List<MockRecord> database = new ArrayList<>();
        for (long i = 1; i <= 60; i++) {
            database.add(new MockRecord(i, "data-" + i));
        }

        BatchExecutionResult result = template.executeStepParallel(
                0L,
                10, // 6 个批次
                maxConcurrency,
                (lastId, limit) -> {
                    long fromId = (lastId == null ? 0L : lastId);
                    return database.stream()
                            .filter(r -> r.id > fromId)
                            .limit(limit)
                            .toList();
                },
                r -> r.id,
                batch -> {
                    int current = activeThreads.incrementAndGet();
                    maxObservedConcurrency.accumulateAndGet(current, Math::max);
                    try {
                        Thread.sleep(30); // 模拟耗时以检验并发背压
                    } catch (InterruptedException ignored) {
                    } finally {
                        activeThreads.decrementAndGet();
                    }
                }
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getTotalCount()).isEqualTo(60);
        assertThat(result.getBatchCount()).isEqualTo(6);
        // 最大瞬时并发度不能超出背压上限 maxConcurrency
        assertThat(maxObservedConcurrency.get()).isLessThanOrEqualTo(maxConcurrency);
    }

    @Test
    @DisplayName("测试 Fail-Fast 快速失败机制：某批次失败时快速熔断")
    void testFailFastAbort() {
        List<MockRecord> database = new ArrayList<>();
        for (long i = 1; i <= 30; i++) {
            database.add(new MockRecord(i, "rec-" + i));
        }

        assertThatThrownBy(() -> template.executeStep(
                0L,
                10,
                (lastId, limit) -> database.stream().filter(r -> r.id > (lastId == null ? 0L : lastId)).limit(limit).toList(),
                r -> r.id,
                batch -> {
                    if (batch.get(0).id > 10) {
                        throw new IllegalArgumentException("模拟第二批次处理失败");
                    }
                }
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("模拟第二批次处理失败");
    }

    @Test
    @DisplayName("测试流式 Cursor 接口联动：逐批消费且游标正常关闭")
    void testStreamingCursorIntegration() {
        List<MockRecord> items = List.of(
                new MockRecord(1L, "a"),
                new MockRecord(2L, "b"),
                new MockRecord(3L, "c"),
                new MockRecord(4L, "d"),
                new MockRecord(5L, "e")
        );

        AtomicBoolean cursorClosed = new AtomicBoolean(false);
        MockCursor<MockRecord> mockCursor = new MockCursor<>(items, () -> cursorClosed.set(true));

        List<MockRecord> consumed = new ArrayList<>();
        BatchExecutionResult result = template.executeStreaming(
                () -> mockCursor,
                2,
                consumed::addAll
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getTotalCount()).isEqualTo(5);
        assertThat(result.getBatchCount()).isEqualTo(3);
        assertThat(consumed).hasSize(5);
        assertThat(cursorClosed.get()).isTrue();
    }

    @Test
    @DisplayName("测试 Spring Boot 自动装配环境：XxlShardingBatchTemplate 正确注入为受管 Bean")
    void testAutoConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(XxlShardingBatchTemplate.class);
            XxlShardingBatchTemplate bean = context.getBean(XxlShardingBatchTemplate.class);
            assertThat(bean).isNotNull();
        });
    }

    @Test
    @DisplayName("测试有界失败明细收集与截断标记：超过 1000 条上限时截断以保护 JVM 堆内存")
    void testBoundedFailedItemsAndTruncationFlag() {
        template.setFailFast(false);

        // 构造 1500 条数据 (id 1~1500)
        List<MockRecord> database = new ArrayList<>();
        for (long i = 1; i <= 1500; i++) {
            database.add(new MockRecord(i, "order-" + i));
        }

        // 每批 500 条，共 3 批，全部模拟抛出异常
        BatchExecutionResult result = template.executeStep(
                0L,
                500,
                (lastId, limit) -> database.stream().filter(r -> r.id > (lastId == null ? 0L : lastId)).limit(limit).toList(),
                r -> r.id,
                batch -> {
                    throw new RuntimeException("模拟批量处理失败");
                }
        );

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getTotalCount()).isEqualTo(1500);
        assertThat(result.getFailCount()).isEqualTo(1500);
        assertThat(result.getSuccessCount()).isEqualTo(0);
        assertThat(result.getBatchCount()).isEqualTo(3);
        // 验证失败记录有界截断：最大保留 1000 条
        assertThat(result.getFailedItems()).hasSize(BatchExecutionResult.MAX_FAILED_ITEMS);
        assertThat(result.hasTruncatedFailures()).isTrue();
    }

    @Test
    @DisplayName("测试失败补偿回调触发与 FailedBatch 模型数据完整性")
    void testFailureHandlerCallbackDispatch() {
        template.setFailFast(false);

        // 构造 30 条数据，每批 10 条
        List<MockRecord> database = new ArrayList<>();
        for (long i = 1; i <= 30; i++) {
            database.add(new MockRecord(i, "item-" + i));
        }

        List<FailedBatch<MockRecord>> capturedFailedBatches = new ArrayList<>();

        BatchExecutionResult result = template.executeStep(
                0L,
                10,
                (lastId, limit) -> database.stream().filter(r -> r.id > (lastId == null ? 0L : lastId)).limit(limit).toList(),
                r -> r.id,
                batch -> {
                    // 第 2 批 (id 11~20) 模拟处理失败
                    if (batch.get(0).id == 11L) {
                        throw new IllegalStateException("第 2 批次业务异常");
                    }
                },
                capturedFailedBatches::add
        );

        assertThat(result.getTotalCount()).isEqualTo(30);
        assertThat(result.getSuccessCount()).isEqualTo(20);
        assertThat(result.getFailCount()).isEqualTo(10);
        assertThat(result.hasTruncatedFailures()).isFalse();
        assertThat(result.getFailedItems()).hasSize(10);

        // 验证失败补偿回调收到的 FailedBatch 详情
        assertThat(capturedFailedBatches).hasSize(1);
        FailedBatch<MockRecord> failedBatch = capturedFailedBatches.get(0);
        assertThat(failedBatch.getBatchIndex()).isEqualTo(2);
        assertThat(failedBatch.getBatchNumber()).isEqualTo(2);
        assertThat(failedBatch.getCursorId()).isEqualTo(10L);
        assertThat(failedBatch.getItems()).hasSize(10);
        assertThat(failedBatch.getItems().get(0).id).isEqualTo(11L);
        assertThat(failedBatch.getItems().get(9).id).isEqualTo(20L);
        assertThat(failedBatch.getCause()).isInstanceOf(IllegalStateException.class)
                .hasMessage("第 2 批次业务异常");
    }

    @Test
    @DisplayName("测试流式批处理实时进度打卡与完成百分比计算")
    void testProgressReportingAtStepInterval() {
        template.setProgressLogInterval(100);
        assertThat(template.getProgressLogInterval()).isEqualTo(100);

        List<MockRecord> database = new ArrayList<>();
        for (long i = 1; i <= 250; i++) {
            database.add(new MockRecord(i, "progress-" + i));
        }

        List<MockRecord> consumed = new ArrayList<>();
        BatchExecutionResult result = template.executeStep(
                0L,
                50,
                (lastId, limit) -> database.stream().filter(r -> r.id > (lastId == null ? 0L : lastId)).limit(limit).toList(),
                r -> r.id,
                consumed::addAll
        );

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getTotalCount()).isEqualTo(250);
        assertThat(consumed).hasSize(250);

        // 直接测试 reportProgress 输出逻辑（含预估总量百分比计算）
        template.reportProgress(250, 500L, 250, 0, 5, System.nanoTime() - 100_000_000L);
    }

    @Test
    @DisplayName("测试并发批处理下失败补偿回调与有界失败集合并发安全性")
    void testParallelExecutionFailureHandlerAndBoundedFailures() {
        template.setFailFast(false);

        // 构造 1200 条数据，每批 200 条，共 6 批
        List<MockRecord> database = new ArrayList<>();
        for (long i = 1; i <= 1200; i++) {
            database.add(new MockRecord(i, "parallel-fail-" + i));
        }

        CopyOnWriteArrayList<FailedBatch<MockRecord>> capturedBatches = new CopyOnWriteArrayList<>();

        BatchExecutionResult result = template.executeStepParallel(
                0L,
                200,
                4,
                (lastId, limit) -> database.stream().filter(r -> r.id > (lastId == null ? 0L : lastId)).limit(limit).toList(),
                r -> r.id,
                batch -> {
                    throw new RuntimeException("并发切片消费模拟失败");
                },
                capturedBatches::add
        );

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getTotalCount()).isEqualTo(1200);
        assertThat(result.getFailCount()).isEqualTo(1200);
        assertThat(result.getFailedItems()).hasSize(BatchExecutionResult.MAX_FAILED_ITEMS);
        assertThat(result.hasTruncatedFailures()).isTrue();
        assertThat(capturedBatches).hasSize(6);
    }

    @Test
    @DisplayName("测试分片流式批处理结合 ShardingContext 与失败补偿回调")
    void testShardedStepWithFailureHandler() {
        template.setFailFast(false);

        // 构造 100 条数据 (id 1~100)
        List<MockRecord> database = new ArrayList<>();
        for (long i = 1; i <= 100; i++) {
            database.add(new MockRecord(i, "sharded-item-" + i));
        }

        ShardingContext sharding = new ShardingContext(0, 2); // 偶数 ID
        List<FailedBatch<MockRecord>> capturedBatches = new ArrayList<>();

        BatchExecutionResult result = template.executeShardedStep(
                sharding,
                0L,
                20,
                (lastId, limit) -> database.stream().filter(r -> r.id > (lastId == null ? 0L : lastId)).limit(limit).toList(),
                r -> r.id,
                batch -> {
                    if (batch.get(0).id == 2L) {
                        throw new RuntimeException("分片第一批失败");
                    }
                },
                capturedBatches::add
        );

        // 100 条数据中属于分片 0 的有 50 条 (2, 4, 6... 100)
        assertThat(result.getTotalCount()).isEqualTo(50);
        assertThat(result.getFailCount()).isEqualTo(10);
        assertThat(result.getSuccessCount()).isEqualTo(40);
        assertThat(capturedBatches).hasSize(1);
        assertThat(capturedBatches.get(0).getItems()).hasSize(10);
        assertThat(capturedBatches.get(0).getItems().get(0).id).isEqualTo(2L);
    }

    static class MockRecord {
        final long id;
        final String name;

        MockRecord(long id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    static class MockCursor<T> implements Cursor<T> {
        private final List<T> items;
        private final Runnable onClose;
        private boolean open = true;

        MockCursor(List<T> items, Runnable onClose) {
            this.items = items;
            this.onClose = onClose;
        }

        @Override
        public boolean isOpen() {
            return open;
        }

        @Override
        public boolean isConsumed() {
            return !open;
        }

        @Override
        public int getCurrentIndex() {
            return 0;
        }

        @Override
        public void close() throws IOException {
            this.open = false;
            if (onClose != null) {
                onClose.run();
            }
        }

        @Override
        public Iterator<T> iterator() {
            return items.iterator();
        }
    }

}
