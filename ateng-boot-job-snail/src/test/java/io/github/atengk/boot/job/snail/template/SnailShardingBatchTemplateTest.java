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

        BatchExecutionResult result = template.executeStepParallel(
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

        BatchExecutionResult result = template.executeStepParallel(
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
        assertThat(successfullyConsumed).containsExactlyInAnyOrder(1L, 2L, 5L);
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
