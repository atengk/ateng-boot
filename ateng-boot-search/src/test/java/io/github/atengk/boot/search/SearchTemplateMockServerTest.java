package io.github.atengk.boot.search;

import io.github.atengk.boot.search.driver.ElasticsearchClientDriver;
import io.github.atengk.boot.search.entity.UserDoc;
import io.github.atengk.boot.search.enums.SearchEngineType;
import io.github.atengk.boot.search.enums.SearchResultCode;
import io.github.atengk.boot.search.exception.SearchBulkException;
import io.github.atengk.boot.search.exception.SearchException;
import io.github.atengk.boot.search.exception.SearchVersionConflictException;
import io.github.atengk.boot.search.model.SearchBulkFailure;
import io.github.atengk.boot.search.model.SearchBulkResult;
import io.github.atengk.boot.search.model.SearchHit;
import io.github.atengk.boot.search.properties.AtengSearchProperties;
import io.github.atengk.boot.search.template.SearchTemplate;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * SearchTemplate 离线自洽 Mock 单元测试
 * <p>
 * 基于 MockWebServer 模拟 Elasticsearch 8.x HTTP 服务端交互，100% 离线运行。
 *
 * @author Ateng
 * @since 2026-10-25
 */
class SearchTemplateMockServerTest {

    private MockWebServer mockWebServer;
    private ElasticsearchClientDriver driver;
    private SearchTemplate searchTemplate;

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

    @Test
    @DisplayName("测试索引文档及雪花ID转String与时间格式化")
    void testIndexDocumentAndSerialization() throws Exception {
        // 准备 Mock 响应
        String mockResponseBody = """
                {
                  "_index": "user_idx",
                  "_id": "1849764512345678901",
                  "_version": 1,
                  "result": "created",
                  "_shards": {
                    "total": 1,
                    "successful": 1,
                    "failed": 0
                  },
                  "_seq_no": 0,
                  "_primary_term": 1
                }
                """;
        mockWebServer.enqueue(esResponse(201, mockResponseBody));

        // 构造实体（使用大于 53 位的雪花 ID 与指定时间）
        Long snowflakeId = 1849764512345678901L;
        UserDoc doc = UserDoc.builder()
                .id(snowflakeId)
                .username("ateng")
                .email("ateng@example.com")
                .age(18)
                .createTime(LocalDateTime.of(2026, 10, 25, 12, 0, 0))
                .build();

        String indexedId = searchTemplate.index(doc);

        assertThat(indexedId).isEqualTo("1849764512345678901");

        // 验证 MockWebServer 收到的 HTTP 请求体
        RecordedRequest recordedRequest = mockWebServer.takeRequest();
        assertThat(recordedRequest.getMethod()).isEqualTo("PUT");
        assertThat(recordedRequest.getPath()).isEqualTo("/user_idx/_doc/1849764512345678901");

        String requestBody = recordedRequest.getBody().readUtf8();
        // 关键断言：Long 类型雪花 ID 必须序列化为带引号的 String，防止前端精度截断
        assertThat(requestBody).contains("\"id\":\"1849764512345678901\"");
        // 关键断言：LocalDateTime 格式化为 yyyy-MM-dd HH:mm:ss
        assertThat(requestBody).contains("\"createTime\":\"2026-10-25 12:00:00\"");
    }

    @Test
    @DisplayName("测试根据ID获取文档及反序列化")
    void testGetByIdSuccess() throws Exception {
        String mockResponseBody = """
                {
                  "_index": "user_idx",
                  "_id": "1849764512345678901",
                  "_version": 1,
                  "_seq_no": 0,
                  "_primary_term": 1,
                  "found": true,
                  "_source": {
                    "id": "1849764512345678901",
                    "username": "ateng",
                    "email": "ateng@example.com",
                    "age": 18,
                    "createTime": "2026-10-25 12:00:00"
                  }
                }
                """;
        mockWebServer.enqueue(esResponse(200, mockResponseBody));

        UserDoc doc = searchTemplate.getById(UserDoc.class, "1849764512345678901");

        assertThat(doc).isNotNull();
        assertThat(doc.getId()).isEqualTo(1849764512345678901L);
        assertThat(doc.getUsername()).isEqualTo("ateng");
        assertThat(doc.getEmail()).isEqualTo("ateng@example.com");
        assertThat(doc.getAge()).isEqualTo(18);
        assertThat(doc.getCreateTime()).isEqualTo(LocalDateTime.of(2026, 10, 25, 12, 0, 0));

        RecordedRequest recordedRequest = mockWebServer.takeRequest();
        assertThat(recordedRequest.getMethod()).isEqualTo("GET");
        assertThat(recordedRequest.getPath()).isEqualTo("/user_idx/_doc/1849764512345678901");
    }

    @Test
    @DisplayName("测试根据ID获取完整命中文档模型及OCC凭据")
    void testGetHitByIdSuccess() throws Exception {
        String mockResponseBody = """
                {
                  "_index": "user_idx",
                  "_id": "1849764512345678901",
                  "_version": 3,
                  "_seq_no": 8,
                  "_primary_term": 2,
                  "found": true,
                  "_routing": "route_dept_1",
                  "_source": {
                    "id": "1849764512345678901",
                    "username": "ateng",
                    "email": "ateng@example.com",
                    "age": 18,
                    "createTime": "2026-10-25 12:00:00"
                  }
                }
                """;
        mockWebServer.enqueue(esResponse(200, mockResponseBody));

        SearchHit<UserDoc> hit = searchTemplate.getHitById(UserDoc.class, "1849764512345678901");

        assertThat(hit).isNotNull();
        assertThat(hit.getId()).isEqualTo("1849764512345678901");
        assertThat(hit.getIndex()).isEqualTo("user_idx");
        assertThat(hit.getSeqNo()).isEqualTo(8L);
        assertThat(hit.getPrimaryTerm()).isEqualTo(2L);
        assertThat(hit.getVersion()).isEqualTo(3L);
        assertThat(hit.getRouting()).isEqualTo("route_dept_1");
        assertThat(hit.getContent()).isNotNull();
        assertThat(hit.getContent().getUsername()).isEqualTo("ateng");
        assertThat(hit.getSource()).isSameAs(hit.getContent());
    }

    @Test
    @DisplayName("测试根据ID查询不存在的文档保底返回 null")
    void testGetByIdNotFound() {
        String mockResponseBody = """
                {
                  "_index": "user_idx",
                  "_id": "999999",
                  "found": false
                }
                """;
        mockWebServer.enqueue(esResponse(404, mockResponseBody));

        UserDoc doc = searchTemplate.getById(UserDoc.class, "999999");
        assertThat(doc).isNull();
    }

    @Test
    @DisplayName("测试基于 Map 的局部更新 (Partial Update)")
    void testPartialUpdateWithMap() throws Exception {
        String mockResponseBody = """
                {
                  "_index": "user_idx",
                  "_id": "1849764512345678901",
                  "_version": 2,
                  "result": "updated",
                  "_shards": {
                    "total": 1,
                    "successful": 1,
                    "failed": 0
                  },
                  "_seq_no": 1,
                  "_primary_term": 1
                }
                """;
        mockWebServer.enqueue(esResponse(200, mockResponseBody));

        Map<String, Object> partialDoc = new HashMap<>();
        partialDoc.put("email", "updated@example.com");
        partialDoc.put("age", 25);

        boolean updated = searchTemplate.update("user_idx", "1849764512345678901", partialDoc);
        assertThat(updated).isTrue();

        RecordedRequest recordedRequest = mockWebServer.takeRequest();
        assertThat(recordedRequest.getMethod()).isEqualTo("POST");
        assertThat(recordedRequest.getPath()).isEqualTo("/user_idx/_update/1849764512345678901");

        String body = recordedRequest.getBody().readUtf8();
        // 仅序列化 doc 包含的局部变更字段，避免全量覆写
        assertThat(body).contains("\"doc\":{");
        assertThat(body).contains("\"email\":\"updated@example.com\"");
        assertThat(body).contains("\"age\":25");
        assertThat(body).doesNotContain("\"doc_as_upsert\":true");
    }

    @Test
    @DisplayName("测试局部更新带 docAsUpsert=true")
    void testPartialUpdateWithDocAsUpsert() throws Exception {
        String mockResponseBody = """
                {
                  "_index": "user_idx",
                  "_id": "1849764512345678901",
                  "_version": 1,
                  "result": "created",
                  "_shards": {
                    "total": 1,
                    "successful": 1,
                    "failed": 0
                  },
                  "_seq_no": 2,
                  "_primary_term": 1
                }
                """;
        mockWebServer.enqueue(esResponse(200, mockResponseBody));

        Map<String, Object> partialDoc = Map.of("username", "upsert_user", "age", 30);

        boolean updated = searchTemplate.update(UserDoc.class, "1849764512345678901", partialDoc, true);
        assertThat(updated).isTrue();

        RecordedRequest recordedRequest = mockWebServer.takeRequest();
        assertThat(recordedRequest.getMethod()).isEqualTo("POST");
        assertThat(recordedRequest.getPath()).isEqualTo("/user_idx/_update/1849764512345678901");

        String body = recordedRequest.getBody().readUtf8();
        assertThat(body).contains("\"doc_as_upsert\":true");
    }

    @Test
    @DisplayName("测试基于实体的局部更新自动提取注解元数据")
    void testPartialUpdateWithEntity() throws Exception {
        String mockResponseBody = """
                {
                  "_index": "user_idx",
                  "_id": "1849764512345678901",
                  "_version": 3,
                  "result": "updated",
                  "_shards": {
                    "total": 1,
                    "successful": 1,
                    "failed": 0
                  },
                  "_seq_no": 3,
                  "_primary_term": 1
                }
                """;
        mockWebServer.enqueue(esResponse(200, mockResponseBody));

        UserDoc partialDoc = UserDoc.builder()
                .id(1849764512345678901L)
                .email("entity_updated@example.com")
                .build();

        boolean updated = searchTemplate.update(partialDoc);
        assertThat(updated).isTrue();

        RecordedRequest recordedRequest = mockWebServer.takeRequest();
        assertThat(recordedRequest.getMethod()).isEqualTo("POST");
        assertThat(recordedRequest.getPath()).isEqualTo("/user_idx/_update/1849764512345678901");
    }

    @Test
    @DisplayName("测试带乐观并发控制 (OCC) 的条件更新")
    void testPartialUpdateWithOCC() throws Exception {
        String mockResponseBody = """
                {
                  "_index": "user_idx",
                  "_id": "1849764512345678901",
                  "_version": 4,
                  "result": "updated",
                  "_shards": {
                    "total": 1,
                    "successful": 1,
                    "failed": 0
                  },
                  "_seq_no": 9,
                  "_primary_term": 2
                }
                """;
        mockWebServer.enqueue(esResponse(200, mockResponseBody));

        Map<String, Object> partialDoc = Map.of("email", "occ@example.com");

        boolean updated = searchTemplate.update("user_idx", "1849764512345678901", partialDoc, false, 8L, 2L);
        assertThat(updated).isTrue();

        RecordedRequest recordedRequest = mockWebServer.takeRequest();
        assertThat(recordedRequest.getMethod()).isEqualTo("POST");
        // 关键断言：URL Query 参数包含 if_seq_no 与 if_primary_term
        assertThat(recordedRequest.getPath()).contains("if_seq_no=8");
        assertThat(recordedRequest.getPath()).contains("if_primary_term=2");
    }

    @Test
    @DisplayName("测试带乐观并发控制 (OCC) 的条件全量索引")
    void testIndexWithOCC() throws Exception {
        String mockResponseBody = """
                {
                  "_index": "user_idx",
                  "_id": "1849764512345678901",
                  "_version": 5,
                  "result": "updated",
                  "_shards": {
                    "total": 1,
                    "successful": 1,
                    "failed": 0
                  },
                  "_seq_no": 10,
                  "_primary_term": 2
                }
                """;
        mockWebServer.enqueue(esResponse(200, mockResponseBody));

        UserDoc doc = UserDoc.builder()
                .id(1849764512345678901L)
                .username("occ_user")
                .build();

        String indexedId = searchTemplate.index(doc, 9L, 2L);
        assertThat(indexedId).isEqualTo("1849764512345678901");

        RecordedRequest recordedRequest = mockWebServer.takeRequest();
        assertThat(recordedRequest.getMethod()).isEqualTo("PUT");
        assertThat(recordedRequest.getPath()).contains("if_seq_no=9");
        assertThat(recordedRequest.getPath()).contains("if_primary_term=2");
    }

    @Test
    @DisplayName("测试乐观锁版本冲突精确转译为 SearchVersionConflictException")
    void testVersionConflictExceptionTranslation() {
        String conflictResponseBody = """
                {
                  "error": {
                    "root_cause": [
                      {
                        "type": "version_conflict_engine_exception",
                        "reason": "[1849764512345678901]: version conflict, required seqNo [8], primary term [2]. current document has seqNo [9] and primary term [2]",
                        "index_uuid": "abc",
                        "shard": "0",
                        "index": "user_idx"
                      }
                    ],
                    "type": "version_conflict_engine_exception",
                    "reason": "[1849764512345678901]: version conflict, required seqNo [8], primary term [2]. current document has seqNo [9] and primary term [2]",
                    "index_uuid": "abc",
                    "shard": "0",
                    "index": "user_idx"
                  },
                  "status": 409
                }
                """;
        mockWebServer.enqueue(esResponse(409, conflictResponseBody));

        Map<String, Object> partialDoc = Map.of("email", "occ_conflict@example.com");

        assertThatThrownBy(() -> searchTemplate.update("user_idx", "1849764512345678901", partialDoc, false, 8L, 2L))
                .isInstanceOf(SearchVersionConflictException.class)
                .hasMessageContaining("version conflict")
                .satisfies(ex -> {
                    SearchVersionConflictException conflictEx = (SearchVersionConflictException) ex;
                    assertThat(conflictEx.getExpectedSeqNo()).isEqualTo(8L);
                    assertThat(conflictEx.getExpectedPrimaryTerm()).isEqualTo(2L);
                    assertThat(conflictEx.getCause()).isNotNull();
                });
    }

    @Test
    @DisplayName("测试根据ID删除文档成功")
    void testDeleteByIdSuccess() throws Exception {
        String mockResponseBody = """
                {
                  "_index": "user_idx",
                  "_id": "1849764512345678901",
                  "_version": 2,
                  "result": "deleted",
                  "_shards": {
                    "total": 1,
                    "successful": 1,
                    "failed": 0
                  },
                  "_seq_no": 1,
                  "_primary_term": 1
                }
                """;
        mockWebServer.enqueue(esResponse(200, mockResponseBody));

        boolean deleted = searchTemplate.deleteById(UserDoc.class, "1849764512345678901");
        assertThat(deleted).isTrue();

        RecordedRequest recordedRequest = mockWebServer.takeRequest();
        assertThat(recordedRequest.getMethod()).isEqualTo("DELETE");
        assertThat(recordedRequest.getPath()).isEqualTo("/user_idx/_doc/1849764512345678901");
    }

    @Test
    @DisplayName("测试根据ID删除不存在的文档返回 false")
    void testDeleteByIdNotFound() {
        String mockResponseBody = """
                {
                  "_index": "user_idx",
                  "_id": "999999",
                  "_version": 1,
                  "result": "not_found",
                  "_shards": {
                    "total": 1,
                    "successful": 1,
                    "failed": 0
                  },
                  "_seq_no": 2,
                  "_primary_term": 1
                }
                """;
        mockWebServer.enqueue(esResponse(404, mockResponseBody));

        boolean deleted = searchTemplate.deleteById(UserDoc.class, "999999");
        assertThat(deleted).isFalse();
    }

    @Test
    @DisplayName("测试 MDC 链路追踪 X-Trace-Id 自动透传")
    void testTraceIdPropagation() throws Exception {
        mockWebServer.enqueue(esResponse(200, """
                {
                  "_index": "user_idx",
                  "_id": "1",
                  "_version": 1,
                  "found": false
                }
                """));

        // 设置 MDC traceId
        MDC.put("traceId", "ateng-trace-99999");

        searchTemplate.getById(UserDoc.class, "1");

        RecordedRequest request = mockWebServer.takeRequest();
        assertThat(request.getHeader("X-Trace-Id")).isEqualTo("ateng-trace-99999");
    }

    @Test
    @DisplayName("测试底层500错误受控转译为 SearchException 并保留因果链")
    void testExceptionTranslation() {
        String errorResponseBody = """
                {
                  "error": {
                    "root_cause": [
                      {
                        "type": "circuit_breaking_exception",
                        "reason": "[parent] Data too large"
                      }
                    ],
                    "type": "circuit_breaking_exception",
                    "reason": "[parent] Data too large"
                  },
                  "status": 500
                }
                """;
        mockWebServer.enqueue(esResponse(500, errorResponseBody));

        assertThatThrownBy(() -> searchTemplate.getById(UserDoc.class, "1"))
                .isInstanceOf(SearchException.class)
                .hasCauseInstanceOf(Exception.class);
    }

    @Test
    @DisplayName("测试底层逃生舱 execute 回调")
    void testEscapeHatchExecute() {
        mockWebServer.enqueue(esResponse(200, """
                {
                  "_index": "user_idx",
                  "_id": "1",
                  "found": true,
                  "_source": {
                    "id": "1",
                    "username": "escape"
                  }
                }
                """));

        String username = searchTemplate.execute(client -> {
            var res = client.get(g -> g.index("user_idx").id("1"), UserDoc.class);
            return res.source() != null ? res.source().getUsername() : null;
        });

        assertThat(username).isEqualTo("escape");
    }

    // ==================== 批量操作测试 (Bulk Operations Tests) ====================

    @Test
    @DisplayName("测试批量索引全量成功及NDJSON序列化")
    void testIndexBatchSuccess() throws Exception {
        String mockResponseBody = """
                {
                  "took": 15,
                  "errors": false,
                  "items": [
                    {
                      "index": {
                        "_index": "user_idx",
                        "_id": "1849764512345678901",
                        "_version": 1,
                        "result": "created",
                        "_shards": { "total": 1, "successful": 1, "failed": 0 },
                        "_seq_no": 0,
                        "_primary_term": 1,
                        "status": 201
                      }
                    },
                    {
                      "index": {
                        "_index": "user_idx",
                        "_id": "1849764512345678902",
                        "_version": 1,
                        "result": "created",
                        "_shards": { "total": 1, "successful": 1, "failed": 0 },
                        "_seq_no": 1,
                        "_primary_term": 1,
                        "status": 201
                      }
                    }
                  ]
                }
                """;
        mockWebServer.enqueue(esResponse(200, mockResponseBody));

        UserDoc doc1 = UserDoc.builder()
                .id(1849764512345678901L)
                .username("ateng1")
                .email("ateng1@example.com")
                .age(20)
                .createTime(LocalDateTime.of(2026, 10, 25, 12, 0, 0))
                .build();
        UserDoc doc2 = UserDoc.builder()
                .id(1849764512345678902L)
                .username("ateng2")
                .email("ateng2@example.com")
                .age(22)
                .createTime(LocalDateTime.of(2026, 10, 25, 12, 0, 0))
                .build();

        SearchBulkResult result = searchTemplate.indexBatch(List.of(doc1, doc2));

        assertThat(result).isNotNull();
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.hasFailures()).isFalse();
        assertThat(result.getSuccessCount()).isEqualTo(2);
        assertThat(result.getFailureCount()).isEqualTo(0);
        assertThat(result.getTook()).isEqualTo(15L);
        assertThat(result.getFailures()).isEmpty();

        RecordedRequest recordedRequest = mockWebServer.takeRequest();
        assertThat(recordedRequest.getMethod()).isEqualTo("POST");
        assertThat(recordedRequest.getPath()).isEqualTo("/_bulk");

        String body = recordedRequest.getBody().readUtf8();
        assertThat(body).contains("\"_index\":\"user_idx\"");
        assertThat(body).contains("\"_id\":\"1849764512345678901\"");
        assertThat(body).contains("\"_id\":\"1849764512345678902\"");
        // 关键断言：Jackson 统一序列化规则生效（雪花主键 String、时间标准格式）
        assertThat(body).contains("\"createTime\":\"2026-10-25 12:00:00\"");
    }

    @Test
    @DisplayName("测试批量索引指定自定义索引名覆盖实体注解")
    void testIndexBatchCustomIndex() throws Exception {
        String mockResponseBody = """
                {
                  "took": 10,
                  "errors": false,
                  "items": [
                    {
                      "index": {
                        "_index": "custom_user_idx",
                        "_id": "1849764512345678901",
                        "_version": 1,
                        "result": "created",
                        "_shards": { "total": 1, "successful": 1, "failed": 0 },
                        "_seq_no": 0,
                        "_primary_term": 1,
                        "status": 201
                      }
                    }
                  ]
                }
                """;
        mockWebServer.enqueue(esResponse(200, mockResponseBody));

        UserDoc doc = UserDoc.builder().id(1849764512345678901L).username("custom").build();
        SearchBulkResult result = searchTemplate.indexBatch("custom_user_idx", List.of(doc));

        assertThat(result.isSuccess()).isTrue();
        RecordedRequest recordedRequest = mockWebServer.takeRequest();
        assertThat(recordedRequest.getBody().readUtf8()).contains("\"_index\":\"custom_user_idx\"");
    }

    @Test
    @DisplayName("测试严格模式批量写入局部失败立即抛出 SearchBulkException")
    void testIndexBatchStrictPartialFailure() {
        String mockResponseBody = """
                {
                  "took": 25,
                  "errors": true,
                  "items": [
                    {
                      "index": {
                        "_index": "user_idx",
                        "_id": "1849764512345678901",
                        "_version": 1,
                        "result": "created",
                        "_shards": { "total": 1, "successful": 1, "failed": 0 },
                        "_seq_no": 0,
                        "_primary_term": 1,
                        "status": 201
                      }
                    },
                    {
                      "index": {
                        "_index": "user_idx",
                        "_id": "1849764512345678902",
                        "status": 400,
                        "error": {
                          "type": "mapper_parsing_exception",
                          "reason": "failed to parse field [age] of type [long] in document with id '1849764512345678902'"
                        }
                      }
                    }
                  ]
                }
                """;
        mockWebServer.enqueue(esResponse(200, mockResponseBody));

        UserDoc doc1 = UserDoc.builder().id(1849764512345678901L).username("ateng1").build();
        UserDoc doc2 = UserDoc.builder().id(1849764512345678902L).username("ateng2").build();

        assertThatThrownBy(() -> searchTemplate.indexBatch(List.of(doc1, doc2)))
                .isInstanceOf(SearchBulkException.class)
                .satisfies(ex -> {
                    SearchBulkException bulkEx = (SearchBulkException) ex;
                    assertThat(bulkEx.getResultCode()).isEqualTo(SearchResultCode.SEARCH_BULK_PARTIAL_ERROR);
                    assertThat(bulkEx.getSuccessCount()).isEqualTo(1);
                    assertThat(bulkEx.getFailureCount()).isEqualTo(1);
                    assertThat(bulkEx.getFailures()).hasSize(1);
                    SearchBulkFailure failure = bulkEx.getFailures().get(0);
                    assertThat(failure.getId()).isEqualTo("1849764512345678902");
                    assertThat(failure.getIndex()).isEqualTo("user_idx");
                    assertThat(failure.getStatus()).isEqualTo(400);
                    assertThat(failure.getErrorType()).isEqualTo("mapper_parsing_exception");
                    assertThat(failure.getErrorReason()).contains("failed to parse field [age]");
                    assertThat(bulkEx.getMessage()).contains("Bulk operation failed for 1 item(s)");
                    assertThat(bulkEx.getMessage()).contains("1849764512345678902");
                });
    }

    @Test
    @DisplayName("测试宽容模式批量写入局部失败保底返回 SearchBulkResult")
    void testIndexBatchLenientPartialFailure() {
        String mockResponseBody = """
                {
                  "took": 25,
                  "errors": true,
                  "items": [
                    {
                      "index": {
                        "_index": "user_idx",
                        "_id": "1849764512345678901",
                        "_version": 1,
                        "result": "created",
                        "_shards": { "total": 1, "successful": 1, "failed": 0 },
                        "_seq_no": 0,
                        "_primary_term": 1,
                        "status": 201
                      }
                    },
                    {
                      "index": {
                        "_index": "user_idx",
                        "_id": "1849764512345678902",
                        "status": 409,
                        "error": {
                          "type": "version_conflict_engine_exception",
                          "reason": "version conflict on document"
                        }
                      }
                    }
                  ]
                }
                """;
        mockWebServer.enqueue(esResponse(200, mockResponseBody));

        UserDoc doc1 = UserDoc.builder().id(1849764512345678901L).username("ateng1").build();
        UserDoc doc2 = UserDoc.builder().id(1849764512345678902L).username("ateng2").build();

        SearchBulkResult result = searchTemplate.indexBatchLenient(List.of(doc1, doc2));

        assertThat(result).isNotNull();
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.hasFailures()).isTrue();
        assertThat(result.getSuccessCount()).isEqualTo(1);
        assertThat(result.getFailureCount()).isEqualTo(1);
        assertThat(result.getFailures()).hasSize(1);
        SearchBulkFailure failure = result.getFailures().get(0);
        assertThat(failure.getId()).isEqualTo("1849764512345678902");
        assertThat(failure.getStatus()).isEqualTo(409);
        assertThat(failure.getErrorType()).isEqualTo("version_conflict_engine_exception");
        assertThat(failure.getErrorReason()).isEqualTo("version conflict on document");
    }

    @Test
    @DisplayName("测试空集合与null防卫快速保底返回且不发起远程请求")
    void testIndexBatchEmptyDefensiveReturn() {
        SearchBulkResult res1 = searchTemplate.indexBatch(null);
        assertThat(res1.isSuccess()).isTrue();
        assertThat(res1.getSuccessCount()).isEqualTo(0);
        assertThat(res1.getFailures()).isEmpty();

        SearchBulkResult res2 = searchTemplate.indexBatch(Collections.emptyList());
        assertThat(res2.isSuccess()).isTrue();
        assertThat(res2.getSuccessCount()).isEqualTo(0);

        SearchBulkResult res3 = searchTemplate.indexBatchLenient(null);
        assertThat(res3.isSuccess()).isTrue();

        SearchBulkResult res4 = searchTemplate.indexBatchLenient(Collections.emptyList());
        assertThat(res4.isSuccess()).isTrue();

        assertThat(mockWebServer.getRequestCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("测试批量局部更新 updateBatch 成功")
    void testUpdateBatchSuccess() throws Exception {
        String mockResponseBody = """
                {
                  "took": 20,
                  "errors": false,
                  "items": [
                    {
                      "update": {
                        "_index": "user_idx",
                        "_id": "1849764512345678901",
                        "_version": 2,
                        "result": "updated",
                        "_shards": { "total": 1, "successful": 1, "failed": 0 },
                        "_seq_no": 5,
                        "_primary_term": 1,
                        "status": 200
                      }
                    }
                  ]
                }
                """;
        mockWebServer.enqueue(esResponse(200, mockResponseBody));

        UserDoc partial = UserDoc.builder().id(1849764512345678901L).email("bulk_update@example.com").build();
        SearchBulkResult result = searchTemplate.updateBatch(List.of(partial));

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getSuccessCount()).isEqualTo(1);

        RecordedRequest request = mockWebServer.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/_bulk");
        String body = request.getBody().readUtf8();
        assertThat(body).contains("\"update\":{");
        assertThat(body).contains("\"_index\":\"user_idx\"");
        assertThat(body).contains("\"_id\":\"1849764512345678901\"");
        assertThat(body).contains("\"doc\":{");
        assertThat(body).contains("\"email\":\"bulk_update@example.com\"");
    }

    @Test
    @DisplayName("测试批量删除 deleteBatch 成功与严格失败")
    void testDeleteBatch() throws Exception {
        String mockResponseBody = """
                {
                  "took": 12,
                  "errors": false,
                  "items": [
                    {
                      "delete": {
                        "_index": "user_idx",
                        "_id": "1849764512345678901",
                        "_version": 3,
                        "result": "deleted",
                        "_shards": { "total": 1, "successful": 1, "failed": 0 },
                        "_seq_no": 6,
                        "_primary_term": 1,
                        "status": 200
                      }
                    }
                  ]
                }
                """;
        mockWebServer.enqueue(esResponse(200, mockResponseBody));

        SearchBulkResult result = searchTemplate.deleteBatch(UserDoc.class, List.of("1849764512345678901"));
        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getSuccessCount()).isEqualTo(1);

        RecordedRequest request = mockWebServer.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/user_idx/_bulk");
        String deleteBody = request.getBody().readUtf8();
        assertThat(deleteBody).contains("\"delete\":{");
        assertThat(deleteBody).contains("\"_index\":\"user_idx\"");
        assertThat(deleteBody).contains("\"_id\":\"1849764512345678901\"");
    }

    @Test
    @DisplayName("测试驱动类型及原生客户端获取")
    void testEngineTypeAndNativeClient() {
        assertThat(driver.getEngineType()).isEqualTo(SearchEngineType.ELASTICSEARCH);
        assertThat(driver.getElasticsearchClient()).isNotNull();
        assertThat(driver.getNativeClient(co.elastic.clients.elasticsearch.ElasticsearchClient.class)).isNotNull();
        assertThat(driver.getNativeClient(org.elasticsearch.client.RestClient.class)).isNotNull();
    }
}
