package io.github.atengk.boot.job.xxl.template;

import io.github.atengk.boot.job.xxl.autoconfigure.AtengXxlJobAutoConfiguration;
import io.github.atengk.boot.job.xxl.model.ShardingContext;
import io.github.atengk.boot.mybatisplus.util.StreamingQueryTemplate;
import org.apache.ibatis.cursor.Cursor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BatchFailureHandler 函数式 SPI、自动装配与两级回调仲裁集成测试
 *
 * @author Ateng
 * @since 2026-11-19
 */
@DisplayName("BatchFailureHandler SPI 与两级仲裁集成测试")
class BatchFailureHandlerIntegrationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(AtengXxlJobAutoConfiguration.class))
            .withUserConfiguration(GlobalFailureHandlerConfig.class);

    @Test
    @DisplayName("测试 BatchFailureHandler 函数式契约与 Consumer<FailedBatch<T>> 兼容委托")
    void testFunctionalContractAndConsumerCompatibility() {
        CopyOnWriteArrayList<FailedBatch<MockItem>> received = new CopyOnWriteArrayList<>();
        BatchFailureHandler<MockItem> handler = received::add;
        Consumer<FailedBatch<MockItem>> asConsumer = handler;

        FailedBatch<MockItem> failedBatch = new FailedBatch<>(
                List.of(new MockItem(1L, "a")), 3, 10L, new IllegalStateException("boom"));
        asConsumer.accept(failedBatch);

        assertThat(received).hasSize(1);
        assertThat(received.get(0).getBatchIndex()).isEqualTo(3);
        assertThat(received.get(0).getCursorId()).isEqualTo(10L);
        assertThat(received.get(0).getItems()).hasSize(1);
        assertThat(received.get(0).getCause()).hasMessage("boom");
    }

    @Test
    @DisplayName("测试自动装配全局 BatchFailureHandler Bean：未显式传入回调时兜底接收所有失败切片")
    void testGlobalBeanFallbackWhenExplicitHandlerOmitted() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            XxlShardingBatchTemplate baseTemplate = context.getBean(XxlShardingBatchTemplate.class);
            GlobalFailureHandlerConfig.RecordingGlobalHandler globalHandler =
                    context.getBean(GlobalFailureHandlerConfig.RecordingGlobalHandler.class);
            globalHandler.reset();

            XxlShardingBatchTemplate nonFailFast = baseTemplate.withFailFast(false);
            List<MockItem> data = List.of(
                    new MockItem(1L, "A"),
                    new MockItem(2L, "B"),
                    new MockItem(3L, "C"),
                    new MockItem(4L, "D")
            );

            // 1. 顺序步长批处理未传 failureHandler -> 命中全局默认 BatchFailureHandler
            BatchExecutionResult stepResult = nonFailFast.executeStep(
                    0L,
                    2,
                    (lastId, limit) -> data.stream().filter(i -> i.id() > lastId).limit(limit).toList(),
                    MockItem::id,
                    chunk -> {
                        if (chunk.get(0).id() == 3L) {
                            throw new IllegalStateException("step failure");
                        }
                    }
            );
            assertThat(stepResult.getFailCount()).isEqualTo(2);
            assertThat(globalHandler.getCaptured()).hasSize(1);
            assertThat(globalHandler.getCaptured().get(0).getBatchIndex()).isEqualTo(2);
            assertThat(globalHandler.getCaptured().get(0).getCursorId()).isEqualTo(2L);
            assertThat(globalHandler.getCaptured().get(0).getCause()).hasMessage("step failure");

            // 2. MyBatis 流式游标顺序/并发传入 null failureHandler -> 均命中全局默认 BatchFailureHandler
            if (baseTemplate instanceof XxlMyBatisStreamingTemplate streamingTemplate) {
                globalHandler.reset();
                XxlMyBatisStreamingTemplate streamingNonFailFast = streamingTemplate.withFailFast(false);
                streamingNonFailFast.executeStreaming(
                        () -> cursorOf(data),
                        2,
                        chunk -> {
                            if (chunk.get(0).id() == 1L) {
                                throw new IllegalStateException("streaming failure");
                            }
                        },
                        null
                );
                assertThat(globalHandler.getCaptured()).hasSize(1);
                assertThat(globalHandler.getCaptured().get(0).getCause()).hasMessage("streaming failure");

                globalHandler.reset();
                streamingNonFailFast.executeStreamingParallel(
                        () -> cursorOf(data),
                        2,
                        2,
                        chunk -> {
                            if (chunk.get(0).id() == 3L) {
                                throw new IllegalStateException("parallel streaming failure");
                            }
                        }
                );
                assertThat(globalHandler.getCaptured()).hasSize(1);
                assertThat(globalHandler.getCaptured().get(0).getCause()).hasMessage("parallel streaming failure");
            }
        });
    }

    @Test
    @DisplayName("测试就近显式回调优先仲裁：显式传入回调时仅执行显式回调，全局默认 Bean 绝不重复调用")
    void testExplicitHandlerOverridesGlobalBeanWithoutDoubleInvocation() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            XxlShardingBatchTemplate baseTemplate = context.getBean(XxlShardingBatchTemplate.class);
            GlobalFailureHandlerConfig.RecordingGlobalHandler globalHandler =
                    context.getBean(GlobalFailureHandlerConfig.RecordingGlobalHandler.class);
            globalHandler.reset();

            XxlShardingBatchTemplate nonFailFast = baseTemplate.withFailFast(false);
            List<MockItem> data = List.of(new MockItem(1L, "A"), new MockItem(2L, "B"));
            AtomicInteger explicitCalls = new AtomicInteger();

            BatchFailureHandler<MockItem> explicitHandler = failed -> explicitCalls.incrementAndGet();

            // 显式回调：顺序、并发及流式均不得重复触发 globalHandler
            nonFailFast.executeStep(
                    0L,
                    2,
                    (lastId, limit) -> data.stream().filter(i -> i.id() > lastId).limit(limit).toList(),
                    MockItem::id,
                    chunk -> {
                        throw new RuntimeException("explicit step fail");
                    },
                    explicitHandler
            );
            nonFailFast.executeStepParallel(
                    0L,
                    2,
                    2,
                    (lastId, limit) -> data.stream().filter(i -> i.id() > lastId).limit(limit).toList(),
                    MockItem::id,
                    chunk -> {
                        throw new RuntimeException("explicit parallel fail");
                    },
                    explicitHandler
            );

            assertThat(explicitCalls.get()).isEqualTo(2);
            assertThat(globalHandler.getCaptured()).isEmpty();
        });
    }

    @Test
    @DisplayName("测试无 MyBatis-Plus 时自动装配回退的 XxlShardingBatchTemplate 同样自动注入全局 BatchFailureHandler")
    void testStandaloneShardingTemplateAutoConfiguredWithGlobalFailureHandler() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(AtengXxlJobAutoConfiguration.class))
                .withUserConfiguration(GlobalFailureHandlerConfig.class)
                .withClassLoader(new FilteredClassLoader(StreamingQueryTemplate.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(XxlShardingBatchTemplate.class);
                    XxlShardingBatchTemplate bean = context.getBean(XxlShardingBatchTemplate.class);
                    assertThat(bean).isExactlyInstanceOf(XxlShardingBatchTemplate.class);
                    assertThat(bean.getDefaultFailureHandler())
                            .isSameAs(context.getBean(GlobalFailureHandlerConfig.RecordingGlobalHandler.class));
                });
    }

    @Test
    @DisplayName("测试高并发交错调用 withFailFast/withProgressLogInterval 派生视图：相互完全隔离且不污染单例原配置")
    void testConcurrentDerivedViewsIsolationWithoutMutatingSingleton() {
        ThreadPoolTaskExecutor callerPool = new ThreadPoolTaskExecutor();
        callerPool.setCorePoolSize(8);
        callerPool.setMaxPoolSize(8);
        callerPool.setThreadNamePrefix("derived-view-caller-");
        callerPool.initialize();

        try {
            BatchFailureHandler<MockItem> globalHandler = failedBatch -> {
            };
            XxlMyBatisStreamingTemplate singleton = new XxlMyBatisStreamingTemplate(
                    callerPool, 50, 4, 200, null, globalHandler);

            assertThat(singleton.isFailFast()).isTrue();
            assertThat(singleton.getProgressLogInterval()).isEqualTo(200);

            // 链式协变派生：保留类型 XxlMyBatisStreamingTemplate 与全局回调/零间隔关闭语义
            XxlMyBatisStreamingTemplate chained = singleton.withFailFast(false).withProgressLogInterval(0);
            assertThat(chained).isNotSameAs(singleton);
            assertThat(chained.isFailFast()).isFalse();
            assertThat(chained.getProgressLogInterval()).isEqualTo(0);
            assertThat(chained.getDefaultFailureHandler()).isSameAs(globalHandler);
            // 原单例保持纯净未受任何篡改
            assertThat(singleton.isFailFast()).isTrue();
            assertThat(singleton.getProgressLogInterval()).isEqualTo(200);

            List<MockItem> data = List.of(
                    new MockItem(1L, "A"),
                    new MockItem(2L, "B"),
                    new MockItem(3L, "C"),
                    new MockItem(4L, "D")
            );

            int threads = 8;
            CountDownLatch startGate = new CountDownLatch(1);
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            AtomicInteger nonFailFastSuccessRuns = new AtomicInteger();
            AtomicInteger failFastThrowsRuns = new AtomicInteger();

            for (int i = 0; i < threads; i++) {
                boolean useFailFast = (i % 2 == 0);
                int interval = useFailFast ? 500 : 1;
                futures.add(CompletableFuture.runAsync(() -> {
                    try {
                        startGate.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }

                    XxlMyBatisStreamingTemplate view = singleton
                            .withFailFast(useFailFast)
                            .withProgressLogInterval(interval);
                    assertThat(view.isFailFast()).isEqualTo(useFailFast);
                    assertThat(view.getProgressLogInterval()).isEqualTo(interval);

                    if (useFailFast) {
                        assertThatThrownBy(() -> view.executeStep(
                                0L,
                                2,
                                (lastId, limit) -> data.stream().filter(it -> it.id() > lastId).limit(limit).toList(),
                                MockItem::id,
                                chunk -> {
                                    throw new IllegalStateException("fail-fast concurrent trigger");
                                }
                        )).isInstanceOf(IllegalStateException.class);
                        failFastThrowsRuns.incrementAndGet();
                    } else {
                        BatchExecutionResult res = view.executeStep(
                                0L,
                                2,
                                (lastId, limit) -> data.stream().filter(it -> it.id() > lastId).limit(limit).toList(),
                                MockItem::id,
                                chunk -> {
                                    if (chunk.get(0).id() == 1L) {
                                        throw new IllegalStateException("partial failure");
                                    }
                                }
                        );
                        assertThat(res.getSuccessCount()).isEqualTo(2);
                        assertThat(res.getFailCount()).isEqualTo(2);
                        nonFailFastSuccessRuns.incrementAndGet();
                    }
                }, callerPool));
            }

            startGate.countDown();
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            assertThat(failFastThrowsRuns.get()).isEqualTo(4);
            assertThat(nonFailFastSuccessRuns.get()).isEqualTo(4);
            assertThat(singleton.isFailFast()).isTrue();
            assertThat(singleton.getProgressLogInterval()).isEqualTo(200);
        } finally {
            callerPool.shutdown();
        }
    }

    @Test
    @DisplayName("测试 ReentrantLock 高并发失败收集精度与 MAX_FAILED_ITEMS=1000 有界截断标记")
    void testConcurrentBoundedFailureCollectionAccuracyWithReentrantLock() {
        ThreadPoolTaskExecutor workerPool = new ThreadPoolTaskExecutor();
        workerPool.setCorePoolSize(8);
        workerPool.setMaxPoolSize(8);
        workerPool.setThreadNamePrefix("batch-lock-stress-");
        workerPool.initialize();

        try {
            CopyOnWriteArrayList<FailedBatch<Object>> globalCaptured = new CopyOnWriteArrayList<>();
            XxlMyBatisStreamingTemplate template = new XxlMyBatisStreamingTemplate(
                    workerPool, 20, 8, 500, null, globalCaptured::add)
                    .withFailFast(false);

            // 1. 小于上限场景：50 批 * 10 条 = 500 条全失败，验证零丢条且未触发截断标记
            List<MockItem> exactData = new ArrayList<>();
            for (long i = 1; i <= 500; i++) {
                exactData.add(new MockItem(i, "exact-" + i));
            }
            BatchExecutionResult exactResult = template.executeStepParallel(
                    0L,
                    10,
                    8,
                    (lastId, limit) -> exactData.stream().filter(it -> it.id() > lastId).limit(limit).toList(),
                    MockItem::id,
                    chunk -> {
                        throw new RuntimeException("stress exact fail");
                    }
            );
            assertThat(exactResult.getFailCount()).isEqualTo(500);
            assertThat(exactResult.getFailedItems()).hasSize(500);
            assertThat(exactResult.hasTruncatedFailures()).isFalse();
            assertThat(globalCaptured).hasSize(50);

            // 2. 超过上限场景：并发游标流式 25 批 * 60 条 = 1500 条全失败，严格截断为 1000 且 truncatedFailures=true
            globalCaptured.clear();
            List<MockItem> overLimitData = new ArrayList<>();
            for (long i = 1; i <= 1500; i++) {
                overLimitData.add(new MockItem(i, "over-" + i));
            }
            BatchExecutionResult streamingOverResult = template.executeStreamingParallel(
                    () -> cursorOf(overLimitData),
                    60,
                    8,
                    chunk -> {
                        throw new RuntimeException("stress streaming overflow");
                    }
            );
            assertThat(streamingOverResult.getFailCount()).isEqualTo(1500);
            assertThat(streamingOverResult.getFailedItems()).hasSize(BatchExecutionResult.MAX_FAILED_ITEMS);
            assertThat(streamingOverResult.hasTruncatedFailures()).isTrue();
            assertThat(globalCaptured).hasSize(25);

            // 3. 分片并发溢出场景：验证 executeShardedStepParallel 同样无锁 Pinning 且精确截断
            globalCaptured.clear();
            AtomicBoolean sawWrongShard = new AtomicBoolean(false);
            BatchExecutionResult shardedOverResult = template.executeShardedStepParallel(
                    new ShardingContext(0, 1),
                    0L,
                    60,
                    8,
                    (lastId, limit) -> overLimitData.stream().filter(it -> it.id() > lastId).limit(limit).toList(),
                    MockItem::id,
                    chunk -> {
                        if (chunk.isEmpty()) {
                            sawWrongShard.set(true);
                        }
                        throw new RuntimeException("stress sharded overflow");
                    }
            );
            assertThat(sawWrongShard.get()).isFalse();
            assertThat(shardedOverResult.getFailCount()).isEqualTo(1500);
            assertThat(shardedOverResult.getFailedItems()).hasSize(BatchExecutionResult.MAX_FAILED_ITEMS);
            assertThat(shardedOverResult.hasTruncatedFailures()).isTrue();
            assertThat(globalCaptured).hasSize(25);
        } finally {
            workerPool.shutdown();
        }
    }

    private static <T> Cursor<T> cursorOf(List<T> items) {
        Iterator<T> it = items.iterator();
        return new Cursor<>() {
            @Override
            public boolean isOpen() {
                return true;
            }

            @Override
            public boolean isConsumed() {
                return !it.hasNext();
            }

            @Override
            public int getCurrentIndex() {
                return 0;
            }

            @Override
            public void close() {
            }

            @Override
            public Iterator<T> iterator() {
                return it;
            }
        };
    }

    record MockItem(long id, String name) {
    }

    @Configuration(proxyBeanMethods = false)
    static class GlobalFailureHandlerConfig {

        @Bean
        public RecordingGlobalHandler recordingGlobalHandler() {
            return new RecordingGlobalHandler();
        }

        static class RecordingGlobalHandler implements BatchFailureHandler<Object> {
            private final CopyOnWriteArrayList<FailedBatch<Object>> captured = new CopyOnWriteArrayList<>();

            @Override
            public void onFailure(FailedBatch<Object> failedBatch) {
                captured.add(failedBatch);
            }

            public List<FailedBatch<Object>> getCaptured() {
                return captured;
            }

            public void reset() {
                captured.clear();
            }
        }
    }
}
