package io.github.atengk.boot.search.bulk;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.search.driver.ElasticsearchClientDriver;
import io.github.atengk.boot.search.entity.RoutedOrderDoc;
import io.github.atengk.boot.search.entity.UserDoc;
import io.github.atengk.boot.search.model.SearchBulkResult;
import io.github.atengk.boot.search.properties.AtengSearchProperties;
import io.github.atengk.boot.search.template.SearchTemplate;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
        TenantContextHolder.setTenantId((String) null);
        TenantContextHolder.setIgnoreTenant(false);

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
        RecordedRequest request = mockWebServer.takeRequest(2, TimeUnit.SECONDS);
        assertThat(request).isNotNull();
        assertThat(request.getBody().readUtf8()).contains("\"timer_user\"");
    }

    @Test
    @DisplayName("测试内存缓冲容量阈值 (bufferSize) 自动触发刷盘")
    void testBufferSizeThresholdTrigger() throws Exception {
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

        ExecutorService threadPool = Executors.newFixedThreadPool(threadCount);
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
}
