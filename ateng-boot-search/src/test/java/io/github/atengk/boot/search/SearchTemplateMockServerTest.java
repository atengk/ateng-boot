package io.github.atengk.boot.search;

import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.search.driver.ElasticsearchClientDriver;
import io.github.atengk.boot.search.entity.DynamicLogDoc;
import io.github.atengk.boot.search.entity.IgnoredTenantDoc;
import io.github.atengk.boot.search.entity.RoutedOrderDoc;
import io.github.atengk.boot.search.entity.SearchItemDoc;
import io.github.atengk.boot.search.entity.UserDoc;
import io.github.atengk.boot.search.enums.SearchEngineType;
import io.github.atengk.boot.search.enums.SearchResultCode;
import io.github.atengk.boot.search.exception.SearchBulkException;
import io.github.atengk.boot.search.exception.SearchException;
import io.github.atengk.boot.search.exception.SearchPaginationExceededException;
import io.github.atengk.boot.search.exception.SearchVersionConflictException;
import io.github.atengk.boot.search.model.OccVersion;
import io.github.atengk.boot.search.model.SearchBulkFailure;
import io.github.atengk.boot.search.model.SearchBulkResult;
import io.github.atengk.boot.search.model.SearchHit;
import io.github.atengk.boot.search.model.SearchPage;
import io.github.atengk.boot.search.model.SearchScrollResult;
import io.github.atengk.boot.search.properties.AtengSearchProperties;
import io.github.atengk.boot.search.query.SearchQuery;
import io.github.atengk.boot.search.query.SearchQueryBuilder;
import io.github.atengk.boot.search.template.SearchTemplate;
import io.github.atengk.boot.search.util.SearchDataScopeHelper;
import io.github.atengk.boot.search.util.SearchMetadataHelper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
        TenantContextHolder.setTenantId((String) null);
        TenantContextHolder.setIgnoreTenant(false);
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

    @Test
    @DisplayName("测试 @SearchIndex 动态 SpEL 时序索引推导解析")
    void testDynamicIndexSpelResolution() {
        String expectedSuffix = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
        String expectedIndex = "log_" + expectedSuffix;
        String actualIndex = SearchMetadataHelper.resolveIndexName(DynamicLogDoc.class);
        assertThat(actualIndex).isEqualTo(expectedIndex);
    }

    @Test
    @DisplayName("测试写入文档时自动提取 @SearchRouting 并注入请求参数")
    void testRoutingExtractionOnIndex() throws Exception {
        String mockResponseBody = """
                {
                  "_index": "routed_order_idx",
                  "_id": "order_1001",
                  "_version": 1,
                  "result": "created",
                  "_shards": { "total": 1, "successful": 1, "failed": 0 },
                  "_seq_no": 0,
                  "_primary_term": 1
                }
                """;
        mockWebServer.enqueue(esResponse(201, mockResponseBody));

        RoutedOrderDoc doc = RoutedOrderDoc.builder()
                .orderId("order_1001")
                .tenantId("tenant_east")
                .amount(new BigDecimal("99.90"))
                .description("Sample routed order")
                .build();

        String indexedId = searchTemplate.index(doc);
        assertThat(indexedId).isEqualTo("order_1001");

        RecordedRequest request = mockWebServer.takeRequest();
        assertThat(request.getMethod()).isEqualTo("PUT");
        assertThat(request.getPath()).isEqualTo("/routed_order_idx/_doc/order_1001?routing=tenant_east");
    }

    @Test
    @DisplayName("测试更新文档时自动提取 @SearchRouting 并注入请求参数")
    void testRoutingExtractionOnUpdate() throws Exception {
        String mockResponseBody = """
                {
                  "_index": "routed_order_idx",
                  "_id": "order_1001",
                  "_version": 2,
                  "result": "updated",
                  "_shards": { "total": 1, "successful": 1, "failed": 0 },
                  "_seq_no": 1,
                  "_primary_term": 1
                }
                """;
        mockWebServer.enqueue(esResponse(200, mockResponseBody));

        RoutedOrderDoc doc = RoutedOrderDoc.builder()
                .orderId("order_1001")
                .tenantId("tenant_east")
                .description("Updated description")
                .build();

        boolean updated = searchTemplate.update(doc, false);
        assertThat(updated).isTrue();

        RecordedRequest request = mockWebServer.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/routed_order_idx/_update/order_1001?routing=tenant_east");
    }

    @Test
    @DisplayName("测试批量操作中自动提取并填充 @SearchRouting 路由键")
    void testRoutingExtractionOnBulkIndexAndUpdate() throws Exception {
        String mockResponseBody = """
                {
                  "took": 15,
                  "errors": false,
                  "items": [
                    {
                      "index": {
                        "_index": "routed_order_idx",
                        "_id": "order_1001",
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

        RoutedOrderDoc doc = RoutedOrderDoc.builder()
                .orderId("order_1001")
                .tenantId("tenant_east")
                .amount(new BigDecimal("100.00"))
                .build();

        SearchBulkResult indexResult = searchTemplate.indexBatch(List.of(doc));
        assertThat(indexResult.isSuccess()).isTrue();

        RecordedRequest bulkIndexReq = mockWebServer.takeRequest();
        String bulkIndexBody = bulkIndexReq.getBody().readUtf8();
        assertThat(bulkIndexBody).contains("\"routing\":\"tenant_east\"");
        assertThat(bulkIndexBody).contains("\"_id\":\"order_1001\"");

        // 测试批量 update
        String mockUpdateBody = """
                {
                  "took": 10,
                  "errors": false,
                  "items": [
                    {
                      "update": {
                        "_index": "routed_order_idx",
                        "_id": "order_1001",
                        "_version": 2,
                        "result": "updated",
                        "_shards": { "total": 1, "successful": 1, "failed": 0 },
                        "_seq_no": 1,
                        "_primary_term": 1,
                        "status": 200
                      }
                    }
                  ]
                }
                """;
        mockWebServer.enqueue(esResponse(200, mockUpdateBody));

        SearchBulkResult updateResult = searchTemplate.updateBatch(List.of(doc));
        assertThat(updateResult.isSuccess()).isTrue();

        RecordedRequest bulkUpdateReq = mockWebServer.takeRequest();
        String bulkUpdateBody = bulkUpdateReq.getBody().readUtf8();
        assertThat(bulkUpdateBody).contains("\"routing\":\"tenant_east\"");
        assertThat(bulkUpdateBody).contains("\"_id\":\"order_1001\"");
    }

    @Test
    @DisplayName("测试当实体标注 @SearchRouting 时未传 routing 调用 getById/getHitById 触发防御性前置阻断")
    void testRoutingRequiredGuardOnGetAndGetHit() {
        assertThatThrownBy(() -> searchTemplate.getById(RoutedOrderDoc.class, "order_1001"))
                .isInstanceOf(SearchException.class)
                .satisfies(ex -> {
                    SearchException se = (SearchException) ex;
                    assertThat(se.getResultCode()).isEqualTo(SearchResultCode.SEARCH_ROUTING_REQUIRED);
                    assertThat(se.getMessage()).contains("Routing key is required");
                });

        assertThatThrownBy(() -> searchTemplate.getHitById(RoutedOrderDoc.class, "order_1001"))
                .isInstanceOf(SearchException.class)
                .satisfies(ex -> {
                    SearchException se = (SearchException) ex;
                    assertThat(se.getResultCode()).isEqualTo(SearchResultCode.SEARCH_ROUTING_REQUIRED);
                });

        assertThat(mockWebServer.getRequestCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("测试当实体标注 @SearchRouting 时未传 routing 调用 deleteById 触发防御性前置阻断")
    void testRoutingRequiredGuardOnDelete() {
        assertThatThrownBy(() -> searchTemplate.deleteById(RoutedOrderDoc.class, "order_1001"))
                .isInstanceOf(SearchException.class)
                .satisfies(ex -> {
                    SearchException se = (SearchException) ex;
                    assertThat(se.getResultCode()).isEqualTo(SearchResultCode.SEARCH_ROUTING_REQUIRED);
                    assertThat(se.getMessage()).contains("Routing key is required");
                });

        assertThat(mockWebServer.getRequestCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("测试提供 routing 参数的 getById 和 deleteById 正常请求")
    void testRoutedGetByIdAndDeleteByIdSuccess() throws Exception {
        // 1. getById 响应
        String mockGetBody = """
                {
                  "_index": "routed_order_idx",
                  "_id": "order_1001",
                  "_version": 1,
                  "_seq_no": 0,
                  "_primary_term": 1,
                  "found": true,
                  "_routing": "tenant_east",
                  "_source": {
                    "orderId": "order_1001",
                    "tenantId": "tenant_east",
                    "amount": 99.90,
                    "description": "Sample routed order"
                  }
                }
                """;
        mockWebServer.enqueue(esResponse(200, mockGetBody));

        RoutedOrderDoc foundDoc = searchTemplate.getById(RoutedOrderDoc.class, "order_1001", "tenant_east");
        assertThat(foundDoc).isNotNull();
        assertThat(foundDoc.getOrderId()).isEqualTo("order_1001");
        assertThat(foundDoc.getTenantId()).isEqualTo("tenant_east");

        RecordedRequest getReq = mockWebServer.takeRequest();
        assertThat(getReq.getMethod()).isEqualTo("GET");
        assertThat(getReq.getPath()).isEqualTo("/routed_order_idx/_doc/order_1001?routing=tenant_east");

        // 2. deleteById 响应
        String mockDeleteBody = """
                {
                  "_index": "routed_order_idx",
                  "_id": "order_1001",
                  "_version": 2,
                  "result": "deleted",
                  "_shards": { "total": 1, "successful": 1, "failed": 0 },
                  "_seq_no": 1,
                  "_primary_term": 1
                }
                """;
        mockWebServer.enqueue(esResponse(200, mockDeleteBody));

        boolean deleted = searchTemplate.deleteById(RoutedOrderDoc.class, "order_1001", "tenant_east");
        assertThat(deleted).isTrue();

        RecordedRequest deleteReq = mockWebServer.takeRequest();
        assertThat(deleteReq.getMethod()).isEqualTo("DELETE");
        assertThat(deleteReq.getPath()).isEqualTo("/routed_order_idx/_doc/order_1001?routing=tenant_east");
    }

    @Test
    @DisplayName("测试 SearchIndexOperations 索引 DDL 生命周期操作")
    void testSearchIndexOperations() throws Exception {
        // 1. exists
        mockWebServer.enqueue(esResponse(200, ""));
        boolean exists = searchTemplate.indices().exists("my_index");
        assertThat(exists).isTrue();
        RecordedRequest existsReq = mockWebServer.takeRequest();
        assertThat(existsReq.getMethod()).isEqualTo("HEAD");
        assertThat(existsReq.getPath()).isEqualTo("/my_index");

        // exists class
        mockWebServer.enqueue(esResponse(404, ""));
        boolean userExists = searchTemplate.indices().exists(UserDoc.class);
        assertThat(userExists).isFalse();
        RecordedRequest userExistsReq = mockWebServer.takeRequest();
        assertThat(userExistsReq.getMethod()).isEqualTo("HEAD");
        assertThat(userExistsReq.getPath()).isEqualTo("/user_idx");

        // 2. existsAlias
        mockWebServer.enqueue(esResponse(200, ""));
        boolean aliasExists = searchTemplate.indices().existsAlias("my_alias");
        assertThat(aliasExists).isTrue();
        RecordedRequest aliasReq = mockWebServer.takeRequest();
        assertThat(aliasReq.getMethod()).isEqualTo("HEAD");
        assertThat(aliasReq.getPath()).isEqualTo("/_alias/my_alias");

        // 3. create
        String createResp = """
                {
                  "acknowledged": true,
                  "shards_acknowledged": true,
                  "index": "my_index"
                }
                """;
        mockWebServer.enqueue(esResponse(200, createResp));
        boolean created = searchTemplate.indices().create("my_index");
        assertThat(created).isTrue();
        RecordedRequest createReq = mockWebServer.takeRequest();
        assertThat(createReq.getMethod()).isEqualTo("PUT");
        assertThat(createReq.getPath()).isEqualTo("/my_index");

        // 4. putMapping
        String ackResp = "{\"acknowledged\": true}";
        mockWebServer.enqueue(esResponse(200, ackResp));
        boolean mappingPut = searchTemplate.indices().putMapping("my_index", "{\"properties\":{\"title\":{\"type\":\"text\"}}}");
        assertThat(mappingPut).isTrue();
        RecordedRequest mappingReq = mockWebServer.takeRequest();
        assertThat(mappingReq.getMethod()).isEqualTo("PUT");
        assertThat(mappingReq.getPath()).isEqualTo("/my_index/_mapping");

        // 5. getMapping
        String getMappingResp = """
                {
                  "my_index": {
                    "mappings": {
                      "properties": {
                        "title": { "type": "text" }
                      }
                    }
                  }
                }
                """;
        mockWebServer.enqueue(esResponse(200, getMappingResp));
        TypeMapping mapping = searchTemplate.indices().getMapping("my_index");
        assertThat(mapping).isNotNull();
        assertThat(mapping.properties()).containsKey("title");
        RecordedRequest getMappingReq = mockWebServer.takeRequest();
        assertThat(getMappingReq.getMethod()).isEqualTo("GET");
        assertThat(getMappingReq.getPath()).isEqualTo("/my_index/_mapping");

        // 6. putIndexTemplate
        mockWebServer.enqueue(esResponse(200, ackResp));
        boolean templatePut = searchTemplate.indices().putIndexTemplate("log_template", "{\"index_patterns\":[\"log_*\"]}");
        assertThat(templatePut).isTrue();
        RecordedRequest putTemplateReq = mockWebServer.takeRequest();
        assertThat(putTemplateReq.getMethod()).isEqualTo("PUT");
        assertThat(putTemplateReq.getPath()).isEqualTo("/_index_template/log_template");

        // 7. existsIndexTemplate
        mockWebServer.enqueue(esResponse(200, ""));
        boolean tplExists = searchTemplate.indices().existsIndexTemplate("log_template");
        assertThat(tplExists).isTrue();
        RecordedRequest existsTemplateReq = mockWebServer.takeRequest();
        assertThat(existsTemplateReq.getMethod()).isEqualTo("HEAD");
        assertThat(existsTemplateReq.getPath()).isEqualTo("/_index_template/log_template");

        // 8. delete
        mockWebServer.enqueue(esResponse(200, ackResp));
        boolean deleted = searchTemplate.indices().delete("my_index");
        assertThat(deleted).isTrue();
        RecordedRequest deleteReq = mockWebServer.takeRequest();
        assertThat(deleteReq.getMethod()).isEqualTo("DELETE");
        assertThat(deleteReq.getPath()).isEqualTo("/my_index");
    }

    @Test
    @DisplayName("测试生产原子别名热切换 swapAlias 接口向 /_aliases 发送单次原子操作")
    void testAtomicAliasSwap() throws Exception {
        String ackResp = "{\"acknowledged\": true}";
        mockWebServer.enqueue(esResponse(200, ackResp));

        boolean swapped = searchTemplate.indices().swapAlias("order_alias", "order_v1", "order_v2");
        assertThat(swapped).isTrue();

        RecordedRequest request = mockWebServer.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).isEqualTo("/_aliases");
        String body = request.getBody().readUtf8();
        assertThat(body).contains("\"actions\":[");
        assertThat(body).contains("\"remove\":{");
        assertThat(body).contains("\"index\":\"order_v1\"");
        assertThat(body).contains("\"alias\":\"order_alias\"");
        assertThat(body).contains("\"add\":{");
        assertThat(body).contains("\"index\":\"order_v2\"");
        assertThat(body).contains("\"alias\":\"order_alias\"");
    }

    @Test
    @DisplayName("测试 Fluent SearchQueryBuilder 丰富算子组装生成完整 DSL")
    void testSearchFluentQueryBuilderDsl() throws Exception {
        String mockResponseBody = """
                {
                  "took": 10,
                  "timed_out": false,
                  "_shards": { "total": 1, "successful": 1, "skipped": 0, "failed": 0 },
                  "hits": {
                    "total": { "value": 0, "relation": "eq" },
                    "hits": []
                  }
                }
                """;
        mockWebServer.enqueue(esResponse(200, mockResponseBody));

        SearchQuery query = SearchQuery.builder()
                .match("title", "spring boot")
                .term("category", "tech")
                .terms("status", List.of("published", "archived"))
                .range("price", 10.0, 100.0)
                .wildcard("sku", "SP*")
                .prefix("author", "at")
                .exists("createTime")
                .geoDistance("location", 31.23, 121.47, "10km")
                .should(b -> b.term("tag", "java").term("tag", "kotlin"))
                .minimumShouldMatch(1)
                .mustNot(b -> b.term("deleted", true))
                .knn("embedding", new float[]{0.1f, 0.2f, 0.3f}, 5, 20)
                .orderByDesc("price")
                .orderByScore()
                .orderByGeoDistance("location", 31.23, 121.47, SortOrder.Asc)
                .highlight("title", "description")
                .termsAggregation("by_cat", "category", 10)
                .sumAggregation("total_price", "price")
                .page(1, 20)
                .routing("shard_key_1")
                .build();

        searchTemplate.search("test_idx", query, SearchItemDoc.class);

        RecordedRequest request = mockWebServer.takeRequest();
        assertThat(request.getMethod()).isEqualTo("POST");
        assertThat(request.getPath()).contains("/test_idx/_search?routing=shard_key_1");

        String body = request.getBody().readUtf8();
        // 验证 Bool must
        assertThat(body).contains("\"match\":{\"title\":{\"query\":\"spring boot\"}}");
        // 验证 Bool filter
        assertThat(body).contains("\"term\":{\"category\":{\"value\":\"tech\"}}");
        assertThat(body).contains("\"range\":{\"price\":{\"gte\":10.0,\"lte\":100.0}}");
        assertThat(body).contains("\"wildcard\":{\"sku\":{\"value\":\"SP*\"}}");
        assertThat(body).contains("\"geo_distance\":{");
        // 验证 Bool should 与 mustNot
        assertThat(body).contains("\"should\":[");
        assertThat(body).contains("\"minimum_should_match\":\"1\"");
        assertThat(body).contains("\"must_not\":[");
        // 验证 knn 向量
        assertThat(body).contains("\"knn\":[");
        assertThat(body).contains("\"field\":\"embedding\"");
        // 验证 highlight 与 aggs
        assertThat(body).contains("\"highlight\":{");
        assertThat(body).contains("\"aggregations\":{");
        assertThat(body).contains("\"by_cat\":{\"terms\":{\"field\":\"category\",\"size\":10}}");
    }

    @Test
    @DisplayName("测试浅分页检索与声明式 @HighlightField 自动富文本回填（内存真明文守护）")
    void testSearchPageAndHighlightAutoPopulation() throws Exception {
        String mockResponseBody = """
                {
                  "took": 8,
                  "timed_out": false,
                  "_shards": { "total": 1, "successful": 1, "skipped": 0, "failed": 0 },
                  "hits": {
                    "total": { "value": 1, "relation": "eq" },
                    "max_score": 2.5,
                    "hits": [
                      {
                        "_index": "item_idx",
                        "_id": "item_101",
                        "_score": 2.5,
                        "_source": {
                          "id": "item_101",
                          "title": "Spring Boot 核心实战",
                          "category": "book",
                          "price": 99.00,
                          "description": "企业级全能基础设施开发实战",
                          "location": "31.23,121.47",
                          "tenantId": "tenant_1"
                        },
                        "highlight": {
                          "title": ["<em>Spring</em> Boot 核心实战"],
                          "description": ["企业级全能基础设施<em>开发</em>实战"]
                        },
                        "sort": ["99.00", "item_101"]
                      }
                    ]
                  }
                }
                """;
        mockWebServer.enqueue(esResponse(200, mockResponseBody));

        SearchQuery query = SearchQuery.builder()
                .match("title", "Spring")
                .highlight("title", "description")
                .page(1, 10)
                .build();

        SearchPage<SearchItemDoc> page = searchTemplate.search(query, SearchItemDoc.class);

        assertThat(page).isNotNull();
        assertThat(page.getTotal()).isEqualTo(1L);
        assertThat(page.getPageNum()).isEqualTo(1);
        assertThat(page.getPageSize()).isEqualTo(10);
        assertThat(page.getTotalPages()).isEqualTo(1L);
        assertThat(page.getHits()).hasSize(1);

        SearchHit<SearchItemDoc> hit = page.getHits().get(0);
        assertThat(hit.getId()).isEqualTo("item_101");
        assertThat(hit.getScore()).isEqualTo(2.5);
        assertThat(hit.getHighlightFields()).containsKey("title");
        assertThat(hit.getSortValues()).containsExactly("99.00", "item_101");

        SearchItemDoc doc = hit.getContent();
        assertThat(doc).isNotNull();
        // 原字段明文必须保持 100% 纯真无污染（内存真明文原则）
        assertThat(doc.getTitle()).isEqualTo("Spring Boot 核心实战");
        // @HighlightField(target = "title") 回填到高亮属性
        assertThat(doc.getHighlightedTitle()).isEqualTo("<em>Spring</em> Boot 核心实战");
        // @HighlightField 缺省 target 回填到自身同名高亮属性
        assertThat(doc.getHighlightedDescription()).isEqualTo("企业级全能基础设施<em>开发</em>实战");

        // 验证无缝流式转为 Core 统一分页对象 PageResult
        PageResult<SearchItemDoc> pageResult = page.toPageResult();
        assertThat(pageResult.getTotal()).isEqualTo(1L);
        assertThat(pageResult.getCurrent()).isEqualTo(1L);
        assertThat(pageResult.getSize()).isEqualTo(10L);
        assertThat(pageResult.getRecords()).hasSize(1);
        assertThat(pageResult.getRecords().get(0).getId()).isEqualTo("item_101");

        // 验证保留完整载荷的 PageResult<SearchHit<T>>
        PageResult<SearchHit<SearchItemDoc>> hitPageResult = page.toHitPageResult();
        assertThat(hitPageResult.getRecords().get(0).getScore()).isEqualTo(2.5);
    }

    @Test
    @DisplayName("测试浅分页深度超过 max-result-window 阈值时触发刚性安全熔断")
    void testSearchPaginationExceededSafetyLimit() {
        SearchQuery query = SearchQuery.builder()
                .from(9995)
                .size(10) // 9995 + 10 = 10005 > 10000
                .build();

        assertThatThrownBy(() -> searchTemplate.search(query, SearchItemDoc.class))
                .isInstanceOf(SearchPaginationExceededException.class)
                .satisfies(ex -> {
                    SearchPaginationExceededException spe = (SearchPaginationExceededException) ex;
                    assertThat(spe.getResultCode()).isEqualTo(SearchResultCode.SEARCH_PAGINATION_EXCEEDED);
                    assertThat(spe.getRequestedWindow()).isEqualTo(10005);
                    assertThat(spe.getMaxResultWindow()).isEqualTo(10000);
                    assertThat(spe.getMessage()).contains("exceeds the safety limit");
                });

        // 验证前置卫语句生效，未发起任何远程网络请求
        assertThat(mockWebServer.getRequestCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("测试 searchAfter 深度游标滚动分页")
    void testSearchAfterDeepScrollPagination() throws Exception {
        // 第一页返回结果
        String firstPageResp = """
                {
                  "took": 5,
                  "timed_out": false,
                  "_shards": { "total": 1, "successful": 1, "skipped": 0, "failed": 0 },
                  "hits": {
                    "total": { "value": 100, "relation": "eq" },
                    "hits": [
                      {
                        "_index": "item_idx",
                        "_id": "item_101",
                        "_source": { "id": "item_101", "title": "Item 101" },
                        "sort": ["100.00", "item_101"]
                      }
                    ]
                  }
                }
                """;
        mockWebServer.enqueue(esResponse(200, firstPageResp));

        SearchQuery query = SearchQuery.builder().match("title", "Item").build();
        SearchScrollResult<SearchItemDoc> firstPage = searchTemplate.searchAfter(query, (List<String>) null, 1, SearchItemDoc.class);

        assertThat(firstPage.getHits()).hasSize(1);
        assertThat(firstPage.isHasMore()).isTrue();
        assertThat(firstPage.getNextSortValues()).containsExactly("100.00", "item_101");

        RecordedRequest req1 = mockWebServer.takeRequest();
        String body1 = req1.getBody().readUtf8();
        assertThat(body1).doesNotContain("\"search_after\"");

        // 第二页携带 nextSortValues
        String secondPageResp = """
                {
                  "took": 4,
                  "timed_out": false,
                  "_shards": { "total": 1, "successful": 1, "skipped": 0, "failed": 0 },
                  "hits": {
                    "total": { "value": 100, "relation": "eq" },
                    "hits": []
                  }
                }
                """;
        mockWebServer.enqueue(esResponse(200, secondPageResp));

        SearchScrollResult<SearchItemDoc> secondPage = searchTemplate.searchAfter(
                query, firstPage.getNextSortValues(), 1, SearchItemDoc.class);

        assertThat(secondPage.getHits()).isEmpty();
        assertThat(secondPage.isHasMore()).isFalse();
        assertThat(secondPage.getNextSortValues()).isNull();

        RecordedRequest req2 = mockWebServer.takeRequest();
        String body2 = req2.getBody().readUtf8();
        assertThat(body2).contains("\"search_after\":[\"100.00\",\"item_101\"]");
    }

    @Test
    @DisplayName("测试多租户隐式过滤自动织入与 @IgnoreTenant 豁免")
    void testMultiTenancyImplicitFilterAndIgnoreTenant() throws Exception {
        String mockResp = """
                {
                  "took": 3,
                  "timed_out": false,
                  "_shards": { "total": 1, "successful": 1, "skipped": 0, "failed": 0 },
                  "hits": { "total": { "value": 0, "relation": "eq" }, "hits": [] }
                }
                """;

        // 1. 在租户上下文中检索普通文档实体（应自动注入 term("tenant_id", "tenant_east")）
        mockWebServer.enqueue(esResponse(200, mockResp));
        TenantContextHolder.runWithTenant("tenant_east", () -> {
            SearchQuery query = SearchQuery.builder().match("title", "book").build();
            searchTemplate.search(query, SearchItemDoc.class);
            return null;
        });

        RecordedRequest tenantReq = mockWebServer.takeRequest();
        String tenantBody = tenantReq.getBody().readUtf8();
        assertThat(tenantBody).contains("\"term\":{\"tenant_id\":{\"value\":\"tenant_east\"}}");

        // 2. 在租户上下文中检索标注了 @IgnoreTenant 的实体（应自动豁免）
        mockWebServer.enqueue(esResponse(200, mockResp));
        TenantContextHolder.runWithTenant("tenant_east", () -> {
            SearchQuery query = SearchQuery.builder().match("name", "dict").build();
            searchTemplate.search(query, IgnoredTenantDoc.class);
            return null;
        });

        RecordedRequest ignoredReq = mockWebServer.takeRequest();
        String ignoredBody = ignoredReq.getBody().readUtf8();
        assertThat(ignoredBody).doesNotContain("\"tenant_id\"");

        // 3. 编程式忽略租户过滤 (runWithoutTenant)
        mockWebServer.enqueue(esResponse(200, mockResp));
        TenantContextHolder.runWithTenant("tenant_east", () -> {
            TenantContextHolder.setIgnoreTenant(true);
            try {
                SearchQuery query = SearchQuery.builder().match("title", "book").build();
                searchTemplate.search(query, SearchItemDoc.class);
            } finally {
                TenantContextHolder.setIgnoreTenant(false);
            }
            return null;
        });

        RecordedRequest bypassReq = mockWebServer.takeRequest();
        String bypassBody = bypassReq.getBody().readUtf8();
        assertThat(bypassBody).doesNotContain("\"tenant_id\"");
    }

    @Test
    @DisplayName("测试 MDC 链路追踪标识 X-Trace-Id 自动出站透传")
    void testMdcTraceIdHeaderPropagation() throws Exception {
        String mockResp = """
                {
                  "took": 2,
                  "timed_out": false,
                  "_shards": { "total": 1, "successful": 1, "skipped": 0, "failed": 0 },
                  "hits": { "total": { "value": 0, "relation": "eq" }, "hits": [] }
                }
                """;
        mockWebServer.enqueue(esResponse(200, mockResp));

        try {
            MDC.put("traceId", "trace-777-abc");
            SearchQuery query = SearchQuery.builder().build();
            searchTemplate.search(query, SearchItemDoc.class);

            RecordedRequest request = mockWebServer.takeRequest();
            assertThat(request.getHeader("X-Trace-Id")).isEqualTo("trace-777-abc");
        } finally {
            MDC.clear();
        }
    }

    @Test
    @DisplayName("测试 SearchDataScopeHelper 辅助挂载数据范围过滤")
    void testSearchDataScopeHelper() throws Exception {
        String mockResp = """
                {
                  "took": 2,
                  "timed_out": false,
                  "_shards": { "total": 1, "successful": 1, "skipped": 0, "failed": 0 },
                  "hits": { "total": { "value": 0, "relation": "eq" }, "hits": [] }
                }
                """;
        mockWebServer.enqueue(esResponse(200, mockResp));

        SearchQueryBuilder builder = SearchQuery.builder().match("title", "doc");
        SearchDataScopeHelper.applyScope(builder, "dept_id", List.of("101", "102"));
        SearchDataScopeHelper.applyUserOrDeptScope(builder, "create_by", "user_001", "owner_dept", List.of("D1", "D2"));

        SearchQuery query = builder.build();
        searchTemplate.search(query, SearchItemDoc.class);

        RecordedRequest request = mockWebServer.takeRequest();
        String body = request.getBody().readUtf8();
        assertThat(body).contains("\"terms\":{\"dept_id\":[\"101\",\"102\"]}");
        assertThat(body).contains("\"term\":{\"create_by\":{\"value\":\"user_001\"}}");
        assertThat(body).contains("\"terms\":{\"owner_dept\":[\"D1\",\"D2\"]}");
    }

    @Test
    @DisplayName("测试实体局部更新不序列化 null 字段防御静默覆写")
    void testPartialUpdateEntityOmitsNullFields() throws Exception {
        String mockResp = """
                {
                  "_index": "user_idx",
                  "_id": "1849764512345678901",
                  "_version": 5,
                  "result": "updated",
                  "_shards": { "total": 1, "successful": 1, "failed": 0 },
                  "_seq_no": 5,
                  "_primary_term": 1
                }
                """;
        mockWebServer.enqueue(esResponse(200, mockResp));

        UserDoc partial = UserDoc.builder()
                .id(1849764512345678901L)
                .email("no_nulls@example.com")
                .build();

        boolean updated = searchTemplate.update(partial);
        assertThat(updated).isTrue();

        RecordedRequest recordedRequest = mockWebServer.takeRequest();
        String requestBody = recordedRequest.getBody().readUtf8();
        assertThat(requestBody).contains("\"email\":\"no_nulls@example.com\"");
        assertThat(requestBody).doesNotContain("\"username\":");
        assertThat(requestBody).doesNotContain("\"score\":");
        assertThat(requestBody).doesNotContain("\"age\":");
    }

    @Test
    @DisplayName("测试基于 OccVersion 的强类型 OCC 索引与更新重载")
    void testOccVersionOverloads() throws Exception {
        String mockIndexResp = """
                {
                  "_index": "user_idx",
                  "_id": "1849764512345678901",
                  "_version": 2,
                  "result": "created",
                  "_seq_no": 5,
                  "_primary_term": 2,
                  "_shards": { "total": 1, "successful": 1, "failed": 0 }
                }
                """;
        mockWebServer.enqueue(esResponse(201, mockIndexResp));

        UserDoc doc = UserDoc.builder()
                .id(1849764512345678901L)
                .username("occ_user")
                .build();

        OccVersion occ = OccVersion.of(5L, 2L);
        assertThat(occ.isSpecified()).isTrue();
        assertThat(occ.seqNo()).isEqualTo(5L);
        assertThat(occ.primaryTerm()).isEqualTo(2L);

        String id = searchTemplate.index(doc, occ);
        assertThat(id).isEqualTo("1849764512345678901");

        RecordedRequest req1 = mockWebServer.takeRequest();
        assertThat(req1.getRequestUrl().queryParameter("if_seq_no")).isEqualTo("5");
        assertThat(req1.getRequestUrl().queryParameter("if_primary_term")).isEqualTo("2");

        // 测试 update(doc, false, occVersion)
        String mockUpdateResp = """
                {
                  "_index": "user_idx",
                  "_id": "1849764512345678901",
                  "_version": 3,
                  "result": "updated",
                  "_shards": { "total": 1, "successful": 1, "failed": 0 },
                  "_seq_no": 8,
                  "_primary_term": 3
                }
                """;
        mockWebServer.enqueue(esResponse(200, mockUpdateResp));

        boolean updated = searchTemplate.update(doc, false, OccVersion.of(8L, 3L));
        assertThat(updated).isTrue();

        RecordedRequest req2 = mockWebServer.takeRequest();
        assertThat(req2.getRequestUrl().queryParameter("if_seq_no")).isEqualTo("8");
        assertThat(req2.getRequestUrl().queryParameter("if_primary_term")).isEqualTo("3");
    }

    @Test
    @DisplayName("测试 index/getHitById/deleteById/doUpdate 遇到空白参数快速失败")
    void testFailFastValidationForBlankIndexAndId() {
        UserDoc doc = UserDoc.builder().id(1L).username("test").build();

        assertThatThrownBy(() -> searchTemplate.index("", doc))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Index must not be blank");

        assertThatThrownBy(() -> searchTemplate.index("   ", doc))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Index must not be blank");

        assertThatThrownBy(() -> searchTemplate.getHitById("", UserDoc.class, "1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Index must not be blank");

        assertThatThrownBy(() -> searchTemplate.getHitById("user_idx", UserDoc.class, ""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Document id must not be blank");

        assertThatThrownBy(() -> searchTemplate.deleteById("", "1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Index must not be blank");

        assertThatThrownBy(() -> searchTemplate.deleteById("user_idx", "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Document id must not be blank");

        assertThatThrownBy(() -> searchTemplate.update("", "1", Map.of("a", "b")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Index must not be blank");

        assertThatThrownBy(() -> searchTemplate.update("user_idx", "", Map.of("a", "b")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Document id must not be blank");
    }

    @Test
    @DisplayName("测试 execute 回调遭遇 409 版本冲突受管拦截")
    void testExecuteCallbackConflictInterception() {
        String errorResp = """
                {
                  "error": {
                    "root_cause": [
                      {
                        "type": "version_conflict_engine_exception",
                        "reason": "[1]: version conflict, required seqNo [1], primary term [1]. current version [2]"
                      }
                    ],
                    "type": "version_conflict_engine_exception",
                    "reason": "[1]: version conflict, required seqNo [1], primary term [1]. current version [2]",
                    "index": "user_idx",
                    "shard": "0"
                  },
                  "status": 409
                }
                """;
        mockWebServer.enqueue(esResponse(409, errorResp));

        assertThatThrownBy(() -> searchTemplate.execute(client -> {
            return client.index(i -> i.index("user_idx").id("1").document(Map.of("name", "v1")).ifSeqNo(1L).ifPrimaryTerm(1L));
        })).isInstanceOf(SearchVersionConflictException.class);
    }

    @Test
    @DisplayName("测试 searchAfter 支持 Object[] 数组形式的排序凭据")
    void testSearchAfterWithObjectArraySortValues() throws Exception {
        String mockResp = """
                {
                  "took": 2,
                  "timed_out": false,
                  "_shards": { "total": 1, "successful": 1, "skipped": 0, "failed": 0 },
                  "hits": {
                    "total": { "value": 1, "relation": "eq" },
                    "hits": [
                      {
                        "_index": "search_item",
                        "_id": "item_101",
                        "_score": null,
                        "_source": {
                          "id": 101,
                          "title": "Item 101"
                        },
                        "sort": [1700000000, "item_101"]
                      }
                    ]
                  }
                }
                """;
        mockWebServer.enqueue(esResponse(200, mockResp));

        SearchQuery query = SearchQuery.builder().match("title", "Item").build();
        Object[] sortValues = new Object[]{1699999999L, "item_100"};
        SearchScrollResult<SearchItemDoc> result = searchTemplate.searchAfter(query, sortValues, 10, SearchItemDoc.class);

        assertThat(result).isNotNull();
        assertThat(result.getHits()).hasSize(1);
        assertThat(result.getNextSortValues()).containsExactly("1700000000", "item_101");
        assertThat(result.getNextSortValuesArray()).containsExactly("1700000000", "item_101");

        RecordedRequest req = mockWebServer.takeRequest();
        String body = req.getBody().readUtf8();
        assertThat(body).contains("\"search_after\":[\"1699999999\",\"item_100\"]");
    }

    @Test
    @DisplayName("测试 SearchHit.getHighlights() 别名方法契约")
    void testSearchHitGetHighlights() {
        SearchHit<String> hit = SearchHit.<String>builder()
                .id("1")
                .content("hello")
                .highlightFields(Map.of("title", List.of("<em>hello</em>")))
                .build();

        assertThat(hit.getHighlights()).isEqualTo(hit.getHighlightFields());
        assertThat(hit.getHighlights().get("title")).containsExactly("<em>hello</em>");
    }

    @Test
    @DisplayName("测试 searchAfter 三参数首页便捷重载方法")
    void testSearchAfterFirstPageConvenienceMethod() throws Exception {
        String mockResp = """
                {
                  "took": 2,
                  "timed_out": false,
                  "_shards": { "total": 1, "successful": 1, "skipped": 0, "failed": 0 },
                  "hits": {
                    "total": { "value": 1, "relation": "eq" },
                    "hits": [
                      {
                        "_index": "search_item",
                        "_id": "item_201",
                        "_score": null,
                        "_source": {
                          "id": 201,
                          "title": "Item 201"
                        },
                        "sort": ["item_201"]
                      }
                    ]
                  }
                }
                """;
        mockWebServer.enqueue(esResponse(200, mockResp));

        SearchQuery query = SearchQuery.builder().match("title", "Item").build();
        SearchScrollResult<SearchItemDoc> result = searchTemplate.searchAfter(query, 5, SearchItemDoc.class);

        assertThat(result).isNotNull();
        assertThat(result.getHits()).hasSize(1);
        assertThat(result.getNextSortValues()).containsExactly("item_201");

        RecordedRequest req = mockWebServer.takeRequest();
        String body = req.getBody().readUtf8();
        assertThat(body).doesNotContain("\"search_after\"");
    }
}
