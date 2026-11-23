package io.github.atengk.boot.search.bulk;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.search.driver.ElasticsearchClientDriver;
import io.github.atengk.boot.search.entity.RoutedOrderDoc;
import io.github.atengk.boot.search.entity.UserDoc;
import io.github.atengk.boot.search.enums.SearchResultCode;
import io.github.atengk.boot.search.exception.SearchException;
import io.github.atengk.boot.search.model.SearchBulkFailureItem;
import io.github.atengk.boot.search.model.SearchBulkResult;
import io.github.atengk.boot.search.properties.AtengSearchProperties;
import io.github.atengk.boot.search.template.SearchTemplate;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SearchBulkProcessor 离线自洽 Mock 单元测试
 * <p>
 * 基于 MockWebServer 模拟高吞吐批量写入、三维触发阈值、背压防爆流控与优雅停机排空。
 *
 * @author Ateng
 * @since 2026-10-25
 */
class SearchBulkProcessorTest {

    private MockWebServer mockWebServer;
    private ElasticsearchClientDriver driver;
    private SearchTemplate searchTemplate;
    private SearchBulkProcessor processor;

    @BeforeEach
    void setUp() throws IOException {
        mockWebServer = new MockWebServer();
        mockWebServer.start();

        AtengSearchProperties properties = new AtengSearchProperties();
        properties.setUris(Collections.singletonList("http://" + mockWebServer.getHostName() + ":" + mockWebServer.getPort()));

        driver = new ElasticsearchClientDriver(properties);
        searchTemplate = new SearchTemplate(driver);
    }

    @AfterEach
    void tearDown() throws IOException {
        MDC.clear();
        TenantContextHolder.clear();

        if (processor != null) {
            processor.close();
            processor = null;
        }
        if (driver != null) {
            driver.close();
        }
        if (mockWebServer != null) {
            mockWebServer.shutdown();
        }
    }

    private MockResponse esResponse(int code, String body) {
        return new MockResponse()
                .setResponseCode(code)
                .setHeader("Content-Type", "application/json")
                .setHeader("X-Elastic-Product", "Elasticsearch")
                .setBody(body);
    }

    private String successBulkResponseBody() {
        return """
                {
                  "took": 10,
                  "errors": false,
                  "items": [
                    {
                      "index": {
                        "_index": "user_idx",
                        "_id": "1",
                        "status": 201,
                        "result": "created"
                      }
                    }
                  ]
                }
                """;
    }

    @Test
    @DisplayName("测试基础添加与手动强制 flush 同步刷盘")
    void testBasicAddAndFlush() throws Exception {
        mockWebServer.enqueue(esResponse(200, successBulkResponseBody()));

        processor = searchTemplate.newBulkProcessorBuilder()
                .batchSize(100)
                .flushInterval(Duration.ofDays(1)) // 禁用定时器触发
                .concurrentRequests(0) // 同步模式
                .build();

        UserDoc doc = UserDoc.builder().id(1001L).username("test_user").build();
        processor.add(doc);

        assertThat(processor.getQueueSize()).isEqualTo(1);
        assertThat(processor.getBufferBytes()).isGreaterThan(0L);

        // 手动触发排空
        processor.flush();

        assertThat(processor.getQueueSize()).isEqualTo(0);
        assertThat(processor.getBufferBytes()).isEqualTo(0L);

        RecordedRequest request = mockWebServer.takeRequest();
        assertThat(request.getPath()).isEqualTo("/_bulk");
        String body = request.getBody().readUtf8();
        assertThat(body).contains("\"_index\":\"user_idx\"");
        assertThat(body).contains("\"test_user\"");
    }

    @Test
    @DisplayName("测试文档条数达到阈值 (batchSize) 自动触发物理提交")
    void testBatchSizeThresholdTrigger() throws Exception {
        mockWebServer.enqueue(esResponse(200, successBulkResponseBody()));

        processor = searchTemplate.newBulkProcessorBuilder()
                .batchSize(3) // 达到 3 条触发
                .flushInterval(Duration.ofDays(1)) // 禁用时间触发
                .concurrentRequests(1)
                .build();

        processor.add(UserDoc.builder().id(1L).username("u1").build());
        processor.add(UserDoc.builder().id(2L).username("u2").build());

        // 仅有 2 条时未达到阈值，MockWebServer 不应收到请求
        assertThat(mockWebServer.getRequestCount()).isEqualTo(0);

        // 添加第 3 条，达到阈值触发 Flush
        processor.add(UserDoc.builder().id(3L).username("u3").build());

        RecordedRequest request = mockWebServer.takeRequest(3, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        String body = request.getBody().readUtf8();
        assertThat(body).contains("\"u1\"");
        assertThat(body).contains("\"u2\"");
        assertThat(body).contains("\"u3\"");
    }

    @Test
    @DisplayName("测试时间窗口到达 (flushInterval) 自动触发定时刷盘")
    void testFlushIntervalThresholdTrigger() throws Exception {
        mockWebServer.enqueue(esResponse(200, successBulkResponseBody()));

        processor = searchTemplate.newBulkProcessorBuilder()
                .batchSize(100) // 高条数阈值
                .flushInterval(Duration.ofMillis(200)) // 200ms 快速定时触发
                .concurrentRequests(1)
                .build();

        processor.add(UserDoc.builder().id(999L).username("timer_user").build());

        // 验证定时器在窗口到达后自动刷盘提交
        RecordedRequest request = mockWebServer.takeRequest(5, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getBody().readUtf8()).contains("\"timer_user\"");
    }

    @Test
    @DisplayName("测试内存缓冲容量阈值 (bufferSize) 自动触发刷盘")
    void testBufferSizeThresholdTrigger() throws Exception {
        mockWebServer.enqueue(esResponse(200, successBulkResponseBody()));
        mockWebServer.enqueue(esResponse(200, successBulkResponseBody()));

        processor = searchTemplate.newBulkProcessorBuilder()
                .batchSize(1000)
                .flushInterval(Duration.ofDays(1))
                .bufferSize(DataSize.ofBytes(150)) // 极小容量阈值
                .concurrentRequests(1)
                .build();

        // 连续添加文档使累积估算字节数突破 150 字节
        processor.add(UserDoc.builder().id(101L).username("large_payload_user_0000000001").build());
        processor.add(UserDoc.builder().id(102L).username("large_payload_user_0000000002").build());

        RecordedRequest request = mockWebServer.takeRequest(3, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getBody().readUtf8()).contains("large_payload_user_0000000001");
    }

    @Test
    @DisplayName("测试对称语义完备性：支持 Index、Update、Upsert 与 Delete")
    void testSymmetricDmlOperations() throws Exception {
        mockWebServer.enqueue(esResponse(200, successBulkResponseBody()));

        processor = searchTemplate.newBulkProcessorBuilder()
                .batchSize(10)
                .flushInterval(Duration.ofDays(1))
                .concurrentRequests(0)
                .build();

        // 1. Index
        processor.add(UserDoc.builder().id(1L).username("idx_user").build());

        // 2. Update
        processor.update(UserDoc.builder().id(2L).username("updated_user").build());

        // 3. Upsert
        processor.upsert(UserDoc.builder().id(3L).username("upsert_user").build());

        // 4. Delete
        processor.delete("4", UserDoc.class);

        processor.flush();

        RecordedRequest request = mockWebServer.takeRequest();
        String body = request.getBody().readUtf8();

        // 验证 NDJSON 中包含 index, update, delete 动作
        assertThat(body).contains("\"_id\":\"1\"").contains("\"_index\":\"user_idx\"");
        assertThat(body).contains("\"_id\":\"2\"").contains("\"updated_user\"");
        assertThat(body).contains("\"doc_as_upsert\":false");
        assertThat(body).contains("\"_id\":\"3\"").contains("\"upsert_user\"");
        assertThat(body).contains("\"doc_as_upsert\":true");
        assertThat(body).contains("\"delete\"").contains("\"_id\":\"4\"");
    }

    @Test
    @DisplayName("测试分片路由键 (@SearchRouting) 自动透传")
    void testRoutingKeyPropagation() throws Exception {
        mockWebServer.enqueue(esResponse(200, successBulkResponseBody()));

        processor = searchTemplate.newBulkProcessorBuilder()
                .batchSize(1)
                .concurrentRequests(0)
                .build();

        RoutedOrderDoc order = RoutedOrderDoc.builder()
                .orderId("ORD-888")
                .tenantId("tenant_shanghai")
                .amount(new BigDecimal("199.99"))
                .build();

        processor.add(order);

        RecordedRequest request = mockWebServer.takeRequest();
        String body = request.getBody().readUtf8();
        assertThat(body).contains("\"routing\":\"tenant_shanghai\"");
        assertThat(body).contains("\"_index\":\"routed_order_idx\"");
    }

    @Test
    @DisplayName("测试 BulkListener 成功与局部失败生命周期回调")
    void testBulkListenerCallbacks() throws Exception {
        String mockResponseBodyWithFailure = """
                {
                  "took": 12,
                  "errors": true,
                  "items": [
                    {
                      "index": {
                        "_index": "user_idx",
                        "_id": "1",
                        "status": 201,
                        "result": "created"
                      }
                    },
                    {
                      "index": {
                        "_index": "user_idx",
                        "_id": "2",
                        "status": 400,
                        "error": {
                          "type": "mapper_parsing_exception",
                          "reason": "failed to parse field"
                        }
                      }
                    }
                  ]
                }
                """;
        mockWebServer.enqueue(esResponse(200, mockResponseBodyWithFailure));

        AtomicInteger beforeCount = new AtomicInteger(0);
        AtomicInteger afterCount = new AtomicInteger(0);
        AtomicBoolean hasFailure = new AtomicBoolean(false);

        BulkListener testListener = new BulkListener() {
            @Override
            public void beforeBulk(long executionId, int numberOfActions) {
                beforeCount.addAndGet(numberOfActions);
            }

            @Override
            public void afterBulk(long executionId, int numberOfActions, SearchBulkResult result) {
                afterCount.addAndGet(numberOfActions);
                if (result.hasFailures()) {
                    hasFailure.set(true);
                }
            }
        };

        processor = searchTemplate.newBulkProcessorBuilder()
                .batchSize(2)
                .concurrentRequests(0)
                .listener(testListener)
                .build();

        processor.add(UserDoc.builder().id(1L).username("u1").build());
        processor.add(UserDoc.builder().id(2L).username("u2").build());

        assertThat(beforeCount.get()).isEqualTo(2);
        assertThat(afterCount.get()).isEqualTo(2);
        assertThat(hasFailure.get()).isTrue();
    }

    @Test
    @DisplayName("测试容器优雅停机 (awaitClose / stop) 强制排空所有残留文档")
    void testGracefulShutdownDrainRemainingDocs() throws Exception {
        mockWebServer.enqueue(esResponse(200, successBulkResponseBody()));

        processor = searchTemplate.newBulkProcessorBuilder()
                .batchSize(100) // 高阈值不触发
                .flushInterval(Duration.ofDays(1)) // 禁用定时器
                .concurrentRequests(0)
                .build();

        processor.add(UserDoc.builder().id(777L).username("shutdown_user").build());
        assertThat(mockWebServer.getRequestCount()).isEqualTo(0);

        // 模拟 Spring SmartLifecycle stop 回调触发优雅停机
        CountDownLatch callbackLatch = new CountDownLatch(1);
        processor.stop(callbackLatch::countDown);

        assertThat(callbackLatch.await(3, TimeUnit.SECONDS)).isTrue();
        assertThat(processor.isRunning()).isFalse();

        // 停机阶段必须强制 Flush 残留数据
        RecordedRequest request = mockWebServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getBody().readUtf8()).contains("\"shutdown_user\"");

        // 关闭后拒绝新数据写入 Fail-Fast
        assertThatThrownBy(() -> processor.add(UserDoc.builder().id(888L).username("reject").build()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("closed");
    }

    @Test
    @DisplayName("测试多线程高并发连续写入零丢件自洽")
    void testConcurrentHighThroughputIngestion() throws Exception {
        // 使用 Dispatcher 动态响应所有批量请求
        mockWebServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                return esResponse(200, successBulkResponseBody());
            }
        });

        int threadCount = 10;
        int docsPerThread = 50;
        int totalDocs = threadCount * docsPerThread;

        processor = searchTemplate.newBulkProcessorBuilder()
                .batchSize(25)
                .flushInterval(Duration.ofMillis(100))
                .concurrentRequests(4)
                .maxQueueCapacity(1000)
                .build();

        ThreadPoolTaskExecutor threadPool = new ThreadPoolTaskExecutor();
        threadPool.setCorePoolSize(threadCount);
        threadPool.setMaxPoolSize(threadCount);
        threadPool.setThreadNamePrefix("bulk-test-producer-");
        threadPool.initialize();
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int threadIdx = i;
            threadPool.execute(() -> {
                try {
                    startLatch.await();
                    List<UserDoc> docs = new ArrayList<>();
                    for (int j = 0; j < docsPerThread; j++) {
                        long id = threadIdx * 1000L + j;
                        docs.add(UserDoc.builder().id(id).username("user_" + id).build());
                    }
                    processor.addAll(docs);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    doneLatch.countDown();
                }
            });
        }

        // 同时放行所有线程
        startLatch.countDown();
        assertThat(doneLatch.await(5, TimeUnit.SECONDS)).isTrue();
        threadPool.shutdown();

        // 优雅停机排空所有数据
        boolean clean = processor.awaitClose(Duration.ofSeconds(10));
        assertThat(clean).isTrue();
        assertThat(processor.getQueueSize()).isEqualTo(0);
        assertThat(processor.getBufferBytes()).isEqualTo(0L);
        assertThat(mockWebServer.getRequestCount()).isGreaterThanOrEqualTo(totalDocs / 25);
    }

    @Test
    @DisplayName("测试分层防卫与前置卫语句快速失败")
    void testDefenseInDepthFailFast() {
        processor = searchTemplate.bulkProcessor();

        // 1. null 文档入参快速失败
        assertThatThrownBy(() -> processor.add((UserDoc) null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("document must not be null");

        // 2. update 缺失 ID 快速失败
        assertThatThrownBy(() -> processor.update(UserDoc.builder().username("no_id").build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Document ID must not be null or blank");

        // 3. upsert 缺失 ID 快速失败
        assertThatThrownBy(() -> processor.upsert(UserDoc.builder().username("no_id").build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Document ID must not be null or blank");

        // 4. delete 缺失 index 或 id 快速失败
        assertThatThrownBy(() -> processor.delete("", "1"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> processor.delete("user_idx", ""))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("测试 SmartLifecycle 停机时序阶段必须为最高优先级 DEFAULT_PHASE 保证优先排空")
    void testSmartLifecyclePhase() {
        processor = searchTemplate.bulkProcessor();
        assertThat(processor.getPhase()).isEqualTo(org.springframework.context.SmartLifecycle.DEFAULT_PHASE);
        assertThat(processor.getPhase()).isGreaterThan(0);
    }

    @Test
    @DisplayName("测试批次提取受 buffer-size 容量上限截断约束")
    void testPollBatchBoundedByBufferSize() throws Exception {
        for (int i = 0; i < 4; i++) {
            mockWebServer.enqueue(esResponse(200, successBulkResponseBody()));
        }

        processor = searchTemplate.newBulkProcessorBuilder()
                .batchSize(1000)
                .flushInterval(Duration.ofDays(1))
                .bufferSize(DataSize.ofBytes(150))
                .concurrentRequests(1)
                .build();

        for (int i = 1; i <= 4; i++) {
            processor.add(UserDoc.builder().id((long) i).username("user_batch_limit_" + i).build());
        }

        processor.flush();

        assertThat(mockWebServer.getRequestCount()).isGreaterThanOrEqualTo(2);
    }

    @Test
    @DisplayName("测试 Spring ApplicationContext 关闭时触发 SmartLifecycle 级联优雅停机与安全排空")
    void testApplicationContextCloseFlushesPendingBuffer() throws Exception {
        mockWebServer.enqueue(esResponse(200, successBulkResponseBody()));

        try (org.springframework.context.annotation.AnnotationConfigApplicationContext context =
                     new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.registerBean("searchTemplate", SearchTemplate.class, () -> searchTemplate);
            context.registerBean("searchBulkProcessor", SearchBulkProcessor.class, () ->
                    searchTemplate.newBulkProcessorBuilder()
                            .batchSize(1000)
                            .flushInterval(Duration.ofDays(1))
                            .bufferSize(DataSize.ofMegabytes(10))
                            .build()
            );
            context.refresh();

            SearchBulkProcessor springProcessor = context.getBean(SearchBulkProcessor.class);
            springProcessor.add(UserDoc.builder().id(999L).username("spring_shutdown_user").build());
            assertThat(springProcessor.getQueueSize()).isEqualTo(1);

            // 关闭 Spring 应用上下文，触发 SmartLifecycle.stop()
            context.close();

            RecordedRequest request = mockWebServer.takeRequest(3, TimeUnit.SECONDS);
            assertThat(request).isNotNull();
            assertThat(request.getBody().readUtf8()).contains("spring_shutdown_user");
            assertThat(springProcessor.getQueueSize()).isEqualTo(0);
        }
    }

    @Test
    @DisplayName("测试对称 delete 路由重载准确透传分片 routing")
    void testDeleteWithRoutingPropagation() throws Exception {
        mockWebServer.enqueue(esResponse(200, successBulkResponseBody()));

        processor = searchTemplate.newBulkProcessorBuilder()
                .batchSize(10)
                .flushInterval(Duration.ofDays(1))
                .concurrentRequests(0)
                .build();

        processor.delete("ORD-1", "route-sh", RoutedOrderDoc.class);
        processor.delete("custom_idx", "ORD-2", "route-bj");
        processor.flush();

        RecordedRequest req = mockWebServer.takeRequest();
        String body = req.getBody().readUtf8();
        assertThat(body).contains("\"_index\":\"routed_order_idx\"").contains("\"_id\":\"ORD-1\"").contains("\"routing\":\"route-sh\"");
        assertThat(body).contains("\"_index\":\"custom_idx\"").contains("\"_id\":\"ORD-2\"").contains("\"routing\":\"route-bj\"");
    }

    @Test
    @DisplayName("测试多线程混合租户与不同 traceId 并发入队不串租户且刷盘透传 X-Trace-Id 并洁净还原")
    void testConcurrentMixedTenantAndTraceIdIsolation() throws Exception {
        List<RecordedRequest> capturedRequests = new CopyOnWriteArrayList<>();
        mockWebServer.setDispatcher(new Dispatcher() {
            @Override
            public MockResponse dispatch(RecordedRequest request) {
                if (request.getPath() != null && request.getPath().contains("/_mget")) {
                    return esResponse(200, "{\"docs\":[]}");
                }
                capturedRequests.add(request);
                return esResponse(200, successBulkResponseBody());
            }
        });

        AtomicBoolean callbackCleanAfter = new AtomicBoolean(true);
        processor = searchTemplate.newBulkProcessorBuilder()
                .batchSize(100)
                .flushInterval(Duration.ofMillis(80))
                .concurrentRequests(2)
                .listener(new BulkListener() {
                    @Override
                    public void afterBulk(long executionId, int numberOfActions, SearchBulkResult result) {
                        callbackCleanAfter.compareAndSet(true, String.valueOf(executionId) != null);
                    }
                })
                .build();

        ThreadPoolTaskExecutor threadPool = new ThreadPoolTaskExecutor();
        threadPool.setCorePoolSize(2);
        threadPool.setMaxPoolSize(2);
        threadPool.setThreadNamePrefix("mixed-tenant-producer-");
        threadPool.initialize();
        CountDownLatch latch = new CountDownLatch(2);
        AtomicReference<Throwable> unexpectedError = new AtomicReference<>();

        threadPool.execute(() -> {
            try {
                MDC.put("traceId", "trace-tenant-a");
                TenantContextHolder.runWithTenant("tenant_a", () -> {
                    processor.add(UserDoc.builder().id(101L).username("alice").build());
                    processor.addAction(SearchBulkAction.builder()
                            .type(SearchBulkAction.ActionType.INDEX)
                            .index("user_idx")
                            .id("102")
                            .document(UserDoc.builder().id(102L).username("alice-direct").build())
                            .build());
                });
            } catch (Throwable t) {
                unexpectedError.set(t);
            } finally {
                MDC.clear();
                latch.countDown();
            }
        });

        threadPool.execute(() -> {
            try {
                MDC.put("traceId", "trace-tenant-b");
                TenantContextHolder.runWithTenant("tenant_b", () -> {
                    processor.add(UserDoc.builder().id(201L).username("bob").build());
                    // 跨租户篡改在 addAction 入队时刻即立即 Fail-Fast
                    assertThatThrownBy(() -> processor.addAction(SearchBulkAction.builder()
                            .type(SearchBulkAction.ActionType.INDEX)
                            .index("user_idx")
                            .id("202")
                            .document(Map.of("username", "hacker", "tenant_id", "tenant_a"))
                            .build()))
                            .isInstanceOf(SearchException.class)
                            .extracting(e -> ((SearchException) e).getResultCode())
                            .isEqualTo(SearchResultCode.SEARCH_TENANT_ACCESS_DENIED);
                });
            } catch (Throwable t) {
                unexpectedError.set(t);
            } finally {
                MDC.clear();
                latch.countDown();
            }
        });

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        threadPool.shutdown();
        assertThat(unexpectedError.get()).isNull();

        boolean drained = processor.awaitClose(Duration.ofSeconds(10));
        assertThat(drained).isTrue();
        assertThat(processor.getQueueSize()).isEqualTo(0);
        assertThat(processor.getBufferBytes()).isEqualTo(0L);
        assertThat(MDC.get("traceId")).isNull();
        assertThat(TenantContextHolder.getTenantId()).isNull();

        String allBodies = capturedRequests.stream()
                .map(r -> r.getBody().readUtf8())
                .reduce("", String::concat);
        assertThat(allBodies).contains("\"id\":\"101\"").contains("\"tenant_id\":\"tenant_a\"");
        assertThat(allBodies).contains("\"id\":\"102\"").contains("\"username\":\"alice-direct\"");
        assertThat(allBodies).contains("\"id\":\"201\"").contains("\"tenant_id\":\"tenant_b\"");
        assertThat(allBodies).doesNotContain("hacker");
        assertThat(capturedRequests)
                .anyMatch(r -> "trace-tenant-a".equals(r.getHeader("X-Trace-Id"))
                        || "trace-tenant-b".equals(r.getHeader("X-Trace-Id")));
    }

    @Test
    @DisplayName("测试瞬态异常指数退避重试自愈、重试耗尽 onBatchFailure 兜底及 400 致命错不重试")
    void testTransientRetryExponentialBackoffAndDeadLetterOnExhausted() throws Exception {
        // 1. 503 -> 429 -> 200 自愈，不触发死信
        mockWebServer.enqueue(esResponse(503, "{\"error\":\"busy\"}"));
        mockWebServer.enqueue(esResponse(429, "{\"error\":\"rate_limited\"}"));
        mockWebServer.enqueue(esResponse(200, successBulkResponseBody()));

        List<Long> batchFailExecutions = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> lastBatchError = new AtomicReference<>();
        AtomicInteger failedActionCount = new AtomicInteger();
        AtomicInteger listenerErrorCount = new AtomicInteger();

        BulkDeadLetterHandler customDlq = new BulkDeadLetterHandler() {
            @Override
            public void onBatchFailure(long executionId, List<SearchBulkAction> actions, Throwable failure) {
                batchFailExecutions.add(executionId);
                failedActionCount.set(actions != null ? actions.size() : 0);
                lastBatchError.set(failure);
            }
        };

        processor = searchTemplate.newBulkProcessorBuilder()
                .batchSize(1)
                .flushInterval(Duration.ofDays(1))
                .concurrentRequests(0)
                .maxRetries(2)
                .retryBackoff(Duration.ofMillis(30))
                .listener(new BulkListener() {
                    @Override
                    public void afterBulk(long executionId, int numberOfActions, Throwable failure) {
                        listenerErrorCount.incrementAndGet();
                    }
                })
                .deadLetterHandler(customDlq)
                .build();

        processor.add(UserDoc.builder().id(1L).username("retry_recover").build());
        assertThat(mockWebServer.getRequestCount()).isEqualTo(3);
        assertThat(batchFailExecutions).isEmpty();
        assertThat(listenerErrorCount.get()).isEqualTo(0);

        // 2. 网络 IOException 断开 x1 -> 200 自愈
        mockWebServer.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START));
        mockWebServer.enqueue(esResponse(200, successBulkResponseBody()));
        processor.add(UserDoc.builder().id(2L).username("io_recover").build());
        assertThat(mockWebServer.getRequestCount()).isEqualTo(5);
        assertThat(batchFailExecutions).isEmpty();

        // 3. 503 x3 耗尽 maxRetries=2 -> 触发 BulkListener + BulkDeadLetterHandler.onBatchFailure
        mockWebServer.enqueue(esResponse(503, "{\"error\":\"down_1\"}"));
        mockWebServer.enqueue(esResponse(503, "{\"error\":\"down_2\"}"));
        mockWebServer.enqueue(esResponse(503, "{\"error\":\"down_3\"}"));
        processor.add(UserDoc.builder().id(3L).username("exhausted").build());
        assertThat(mockWebServer.getRequestCount()).isEqualTo(8);
        assertThat(listenerErrorCount.get()).isEqualTo(1);
        assertThat(batchFailExecutions).hasSize(1);
        assertThat(failedActionCount.get()).isEqualTo(1);
        assertThat(lastBatchError.get()).isInstanceOf(SearchException.class);

        // 4. 400 Bad Request 致命错误绝不重试 -> 仅发起 1 次 HTTP 请求，直接进入死信
        mockWebServer.enqueue(esResponse(400, "{\"error\":{\"type\":\"illegal_argument_exception\",\"reason\":\"bad bulk\"}}"));
        int beforeFatal = mockWebServer.getRequestCount();
        processor.add(UserDoc.builder().id(4L).username("fatal_400").build());
        assertThat(mockWebServer.getRequestCount()).isEqualTo(beforeFatal + 1);
        assertThat(batchFailExecutions).hasSize(2);
    }

    @Test
    @DisplayName("测试 HTTP 200 局部文档失败不重试且精确配对原始 Action 触发 onItemFailure 并恢复条目快照")
    void testPartialItemFailureDoesNotRetryAndTriggersDeadLetterItemWithActionAndTrace() throws Exception {
        String partialErrorBody = """
                {
                  "took": 5,
                  "errors": true,
                  "items": [
                    { "index": { "_index": "user_idx", "_id": "1", "status": 201, "result": "created" } },
                    { "index": { "_index": "user_idx", "_id": "2", "status": 400, "error": { "type": "mapper_parsing_exception", "reason": "bad field type" } } }
                  ]
                }
                """;
        mockWebServer.enqueue(esResponse(200, "{\"docs\":[]}"));
        mockWebServer.enqueue(esResponse(200, partialErrorBody));

        List<SearchBulkFailureItem> deadItems = new CopyOnWriteArrayList<>();
        AtomicReference<String> callbackTrace = new AtomicReference<>();
        AtomicReference<String> callbackTenant = new AtomicReference<>();

        processor = searchTemplate.newBulkProcessorBuilder()
                .batchSize(10)
                .flushInterval(Duration.ofDays(1))
                .concurrentRequests(0)
                .maxRetries(2)
                .retryBackoff(Duration.ofMillis(20))
                .deadLetterHandler(new BulkDeadLetterHandler() {
                    @Override
                    public void onItemFailure(long executionId, SearchBulkFailureItem item) {
                        deadItems.add(item);
                        callbackTrace.set(MDC.get("traceId"));
                        callbackTenant.set(TenantContextHolder.getTenantId());
                    }
                })
                .build();

        MDC.put("traceId", "trace-item-dlq");
        TenantContextHolder.runWithTenant("tenant_dlq", () -> {
            processor.add(UserDoc.builder().id(1L).username("ok_user").build());
            processor.add(UserDoc.builder().id(2L).username("bad_user").build());
        });
        MDC.clear();

        // 从无租户、无 traceId 的上下文调用 flush，验证 item 级上下文还原
        processor.flush();

        assertThat(mockWebServer.getRequestCount()).isEqualTo(2);
        assertThat(deadItems).hasSize(1);
        SearchBulkFailureItem dlqItem = deadItems.get(0);
        assertThat(dlqItem.getAction()).isNotNull();
        assertThat(dlqItem.getAction().getId()).isEqualTo("2");
        assertThat(dlqItem.getFailure().getStatus()).isEqualTo(400);
        assertThat(dlqItem.getFailure().getErrorType()).isEqualTo("mapper_parsing_exception");
        assertThat(callbackTrace.get()).isEqualTo("trace-item-dlq");
        assertThat(callbackTenant.get()).isEqualTo("tenant_dlq");
        assertThat(MDC.get("traceId")).isNull();
        assertThat(TenantContextHolder.getTenantId()).isNull();
    }

    @Test
    @DisplayName("测试手动 flush 等待后台异步在途批次及死信回调全部完成后返回且支持回调内重入")
    void testFlushWaitsForInFlightAsyncBatchAndDeadLetterCallbackWithoutSelfDeadlock() throws Exception {
        // Mget (strict ownership off or default): UserDoc 无租户字段，直接使用 400 致命错误并延迟 150ms 触发异步窗口
        mockWebServer.enqueue(esResponse(400, "{\"error\":{\"type\":\"illegal_argument_exception\",\"reason\":\"simulated fatal bulk failure\"}}")
                .setBodyDelay(150, TimeUnit.MILLISECONDS));

        CountDownLatch workerEnteredBeforeBulk = new CountDownLatch(1);
        AtomicInteger batchFailureCount = new AtomicInteger(0);
        AtomicInteger afterBulkCount = new AtomicInteger(0);
        AtomicReference<SearchBulkProcessor> processorRef = new AtomicReference<>();

        processor = searchTemplate.newBulkProcessorBuilder()
                .batchSize(1)
                .flushInterval(Duration.ofDays(1))
                .concurrentRequests(1)
                .maxRetries(0)
                .listener(new BulkListener() {
                    @Override
                    public void beforeBulk(long executionId, int numberOfActions) {
                        workerEnteredBeforeBulk.countDown();
                    }

                    @Override
                    public void afterBulk(long executionId, int numberOfActions, Throwable failure) {
                        afterBulkCount.incrementAndGet();
                        // 验证同一执行线程在回调内重入调用 flush() 不会自锁
                        processorRef.get().flush();
                    }
                })
                .deadLetterHandler(new BulkDeadLetterHandler() {
                    @Override
                    public void onBatchFailure(long executionId, List<SearchBulkAction> actions, Throwable failure) {
                        try {
                            Thread.sleep(60);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        batchFailureCount.incrementAndGet();
                    }
                })
                .build();
        processorRef.set(processor);

        processor.add(UserDoc.builder().id(99L).username("async_race").build());
        assertThat(workerEnteredBeforeBulk.await(5, TimeUnit.SECONDS)).isTrue();

        // 此时队列已被异步线程抽空，flush() 必须等待在途批次及死信回调完成后再返回
        processor.flush();

        assertThat(afterBulkCount.get()).isEqualTo(1);
        assertThat(batchFailureCount.get()).isEqualTo(1);
        assertThat(processor.getQueueSize()).isZero();
    }
}
