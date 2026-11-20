package io.github.atengk.boot.search;

import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.aggregations.CalendarInterval;
import co.elastic.clients.elasticsearch._types.mapping.TypeMapping;
import co.elastic.clients.elasticsearch._types.query_dsl.ChildScoreMode;
import co.elastic.clients.elasticsearch.core.search.FieldCollapse;
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
        TenantContextHolder.clear();
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
    @DisplayName("测试当实体标注 @SearchRouting 时未传 routing 调用 getById/getHitById/getByIds/getHitsByIds 触发防御性前置阻断")
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

        assertThatThrownBy(() -> searchTemplate.getByIds(RoutedOrderDoc.class, "order_1001"))
                .isInstanceOf(SearchException.class)
                .satisfies(ex -> {
                    SearchException se = (SearchException) ex;
                    assertThat(se.getResultCode()).isEqualTo(SearchResultCode.SEARCH_ROUTING_REQUIRED);
                    assertThat(se.getMessage()).contains("Routing key is required");
                });

        assertThatThrownBy(() -> searchTemplate.getHitsByIds(RoutedOrderDoc.class, List.of("order_1001")))
                .isInstanceOf(SearchException.class)
                .satisfies(ex -> {
                    SearchException se = (SearchException) ex;
                    assertThat(se.getResultCode()).isEqualTo(SearchResultCode.SEARCH_ROUTING_REQUIRED);
                    assertThat(se.getMessage()).contains("Routing key is required");
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
    @DisplayName("测试提供 routing 参数的 getByIds 和 getHitsByIds 批量请求")
    void testRoutedGetByIdsAndHitsByIdsSuccess() throws Exception {
        String mockMgetBody = """
                {
                  "docs": [
                    {
                      "_index": "routed_order_idx",
                      "_id": "order_1001",
                      "_version": 1,
                      "_seq_no": 5,
                      "_primary_term": 1,
                      "_routing": "tenant_east",
                      "found": true,
                      "_source": {
                        "orderId": "order_1001",
                        "tenantId": "tenant_east",
                        "orderAmount": 199.9,
                        "status": "PAID"
                      }
                    }
                  ]
                }
                """;
        mockWebServer.enqueue(esResponse(200, mockMgetBody));

        List<SearchHit<RoutedOrderDoc>> hits = searchTemplate.getHitsByIds(
                RoutedOrderDoc.class, List.of("order_1001"), "tenant_east");
        assertThat(hits).hasSize(1);
        assertThat(hits.get(0).getId()).isEqualTo("order_1001");
        assertThat(hits.get(0).getRouting()).isEqualTo("tenant_east");

        RecordedRequest req = mockWebServer.takeRequest();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).contains("routing=tenant_east");
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

    @Test
    @DisplayName("测试 existsById 存在时返回 true 并发出 HEAD 请求")
    void testExistsByIdSuccess() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("X-Elastic-Product", "Elasticsearch"));

        boolean exists = searchTemplate.existsById(UserDoc.class, "1849764512345678901");
        assertThat(exists).isTrue();

        RecordedRequest recordedRequest = mockWebServer.takeRequest();
        assertThat(recordedRequest.getMethod()).isEqualTo("HEAD");
        assertThat(recordedRequest.getPath()).isEqualTo("/user_idx/_doc/1849764512345678901");
    }

    @Test
    @DisplayName("测试 existsById 不存在时返回 false")
    void testExistsByIdNotFound() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(404)
                .setHeader("X-Elastic-Product", "Elasticsearch"));

        boolean exists = searchTemplate.existsById(UserDoc.class, "999999");
        assertThat(exists).isFalse();

        RecordedRequest recordedRequest = mockWebServer.takeRequest();
        assertThat(recordedRequest.getMethod()).isEqualTo("HEAD");
        assertThat(recordedRequest.getPath()).isEqualTo("/user_idx/_doc/999999");
    }

    @Test
    @DisplayName("测试 existsById 支持显式指定索引与路由键")
    void testExistsByIdWithIndexAndRouting() throws Exception {
        mockWebServer.enqueue(new MockResponse()
                .setResponseCode(200)
                .setHeader("X-Elastic-Product", "Elasticsearch"));

        boolean exists = searchTemplate.existsById("custom_user_idx", "1001", "route_east");
        assertThat(exists).isTrue();

        RecordedRequest recordedRequest = mockWebServer.takeRequest();
        assertThat(recordedRequest.getMethod()).isEqualTo("HEAD");
        assertThat(recordedRequest.getPath()).contains("/custom_user_idx/_doc/1001");
        assertThat(recordedRequest.getPath()).contains("routing=route_east");
    }

    @Test
    @DisplayName("测试实体标注 @SearchRouting 时未传 routing 调用 existsById 触发防御性前置阻断")
    void testExistsByIdRoutingRequiredGuard() {
        assertThatThrownBy(() -> searchTemplate.existsById(RoutedOrderDoc.class, "order_1001"))
                .isInstanceOf(SearchException.class)
                .satisfies(ex -> {
                    SearchException se = (SearchException) ex;
                    assertThat(se.getResultCode()).isEqualTo(SearchResultCode.SEARCH_ROUTING_REQUIRED);
                    assertThat(se.getMessage()).contains("Routing key is required");
                });

        assertThat(mockWebServer.getRequestCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("测试 existsById 参数校验前置防卫")
    void testExistsByIdParameterValidation() {
        assertThatThrownBy(() -> searchTemplate.existsById((Class<?>) null, "1001"))
                .isInstanceOf(NullPointerException.class);

        assertThatThrownBy(() -> searchTemplate.existsById(UserDoc.class, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Document id must not be blank");

        assertThatThrownBy(() -> searchTemplate.existsById(" ", "1001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Index must not be blank");
    }

    @Test
    @DisplayName("测试 count 精准统计文档条数")
    void testCountSuccess() throws Exception {
        String mockResponseBody = """
                {
                  "count": 42,
                  "_shards": {
                    "total": 1,
                    "successful": 1,
                    "skipped": 0,
                    "failed": 0
                  }
                }
                """;
        mockWebServer.enqueue(esResponse(200, mockResponseBody));

        SearchQuery query = SearchQuery.builder().term("username", "ateng").build();
        long count = searchTemplate.count(query, UserDoc.class);

        assertThat(count).isEqualTo(42L);

        RecordedRequest recordedRequest = mockWebServer.takeRequest();
        assertThat(recordedRequest.getMethod()).isEqualTo("POST");
        assertThat(recordedRequest.getPath()).isEqualTo("/user_idx/_count");
        String requestBody = recordedRequest.getBody().readUtf8();
        assertThat(requestBody).contains("\"term\":{\"username\":{\"value\":\"ateng\"}}");
    }

    @Test
    @DisplayName("测试 count 多租户自动隐式注入租户隔离过滤")
    void testCountWithMultiTenancy() throws Exception {
        TenantContextHolder.setTenantId("tenant_001");
        String mockResponseBody = """
                {
                  "count": 5,
                  "_shards": {
                    "total": 1,
                    "successful": 1,
                    "skipped": 0,
                    "failed": 0
                  }
                }
                """;
        mockWebServer.enqueue(esResponse(200, mockResponseBody));

        long count = searchTemplate.count(UserDoc.class);

        assertThat(count).isEqualTo(5L);

        RecordedRequest recordedRequest = mockWebServer.takeRequest();
        assertThat(recordedRequest.getMethod()).isEqualTo("POST");
        assertThat(recordedRequest.getPath()).isEqualTo("/user_idx/_count");
        String requestBody = recordedRequest.getBody().readUtf8();
        assertThat(requestBody).contains("\"term\":{\"tenant_id\":{\"value\":\"tenant_001\"}}");
    }

    @Test
    @DisplayName("测试 count 当索引不存在时保底返回 0")
    void testCountIndexNotFound() {
        String mockResponseBody = """
                {
                  "error": {
                    "root_cause": [
                      {
                        "type": "index_not_found_exception",
                        "reason": "no such index [non_existing_idx]",
                        "index": "non_existing_idx"
                      }
                    ],
                    "type": "index_not_found_exception",
                    "reason": "no such index [non_existing_idx]",
                    "index": "non_existing_idx"
                  },
                  "status": 404
                }
                """;
        mockWebServer.enqueue(esResponse(404, mockResponseBody));

        long count = searchTemplate.count("non_existing_idx", null);
        assertThat(count).isEqualTo(0L);
    }

    @Test
    @DisplayName("测试 count 指定索引与 routing 路由参数")
    void testCountWithExplicitIndexAndRouting() throws Exception {
        String mockResponseBody = """
                {
                  "count": 18,
                  "_shards": {
                    "total": 1,
                    "successful": 1,
                    "skipped": 0,
                    "failed": 0
                  }
                }
                """;
        mockWebServer.enqueue(esResponse(200, mockResponseBody));

        SearchQuery query = SearchQuery.builder().routing("route_east").build();
        long count = searchTemplate.count("custom_order_idx", query);

        assertThat(count).isEqualTo(18L);

        RecordedRequest recordedRequest = mockWebServer.takeRequest();
        assertThat(recordedRequest.getMethod()).isEqualTo("POST");
        assertThat(recordedRequest.getPath()).contains("/custom_order_idx/_count");
        assertThat(recordedRequest.getPath()).contains("routing=route_east");
    }

    @Test
    @DisplayName("测试 getByIds 全部命中文档")
    void testGetByIdsAllHit() throws Exception {
        String mockResponseBody = """
                {
                  "docs": [
                    {
                      "_index": "user_idx",
                      "_id": "1001",
                      "_version": 1,
                      "_seq_no": 1,
                      "_primary_term": 1,
                      "found": true,
                      "_source": {
                        "id": "1001",
                        "username": "user1001",
                        "email": "user1001@example.com",
                        "age": 20,
                        "createTime": "2026-10-25 12:00:00"
                      }
                    },
                    {
                      "_index": "user_idx",
                      "_id": "1002",
                      "_version": 1,
                      "_seq_no": 2,
                      "_primary_term": 1,
                      "found": true,
                      "_source": {
                        "id": "1002",
                        "username": "user1002",
                        "email": "user1002@example.com",
                        "age": 22,
                        "createTime": "2026-10-25 12:00:00"
                      }
                    }
                  ]
                }
                """;
        mockWebServer.enqueue(esResponse(200, mockResponseBody));

        List<UserDoc> users = searchTemplate.getByIds(UserDoc.class, List.of("1001", "1002"));

        assertThat(users).hasSize(2);
        assertThat(users.get(0).getUsername()).isEqualTo("user1001");
        assertThat(users.get(1).getUsername()).isEqualTo("user1002");

        RecordedRequest recordedRequest = mockWebServer.takeRequest();
        assertThat(recordedRequest.getMethod()).isEqualTo("POST");
        assertThat(recordedRequest.getPath()).isEqualTo("/user_idx/_mget");
        String requestBody = recordedRequest.getBody().readUtf8();
        assertThat(requestBody).contains("\"ids\":[\"1001\",\"1002\"]");
    }

    @Test
    @DisplayName("测试 getByIds 部分命中时自动过滤未找到的元素（严禁返回 null 元素）")
    void testGetByIdsPartialHit() throws Exception {
        String mockResponseBody = """
                {
                  "docs": [
                    {
                      "_index": "user_idx",
                      "_id": "1001",
                      "_version": 1,
                      "_seq_no": 1,
                      "_primary_term": 1,
                      "found": true,
                      "_source": {
                        "id": "1001",
                        "username": "user1001",
                        "email": "user1001@example.com",
                        "age": 20,
                        "createTime": "2026-10-25 12:00:00"
                      }
                    },
                    {
                      "_index": "user_idx",
                      "_id": "9999",
                      "found": false
                    }
                  ]
                }
                """;
        mockWebServer.enqueue(esResponse(200, mockResponseBody));

        List<UserDoc> users = searchTemplate.getByIds(UserDoc.class, List.of("1001", "9999"));

        assertThat(users).hasSize(1);
        assertThat(users.get(0).getUsername()).isEqualTo("user1001");
        assertThat(users).doesNotContainNull();
    }

    @Test
    @DisplayName("测试 getByIds 全部未命中时保底返回空集合")
    void testGetByIdsAllMiss() {
        String mockResponseBody = """
                {
                  "docs": [
                    {
                      "_index": "user_idx",
                      "_id": "9991",
                      "found": false
                    },
                    {
                      "_index": "user_idx",
                      "_id": "9992",
                      "found": false
                    }
                  ]
                }
                """;
        mockWebServer.enqueue(esResponse(200, mockResponseBody));

        List<UserDoc> users = searchTemplate.getByIds(UserDoc.class, List.of("9991", "9992"));

        assertThat(users).isNotNull();
        assertThat(users).isEmpty();
    }

    @Test
    @DisplayName("测试 getByIds 传入 null 或空集合时零网络请求保底返回 emptyList")
    void testGetByIdsEmptyOrNullIds() {
        List<UserDoc> res1 = searchTemplate.getByIds(UserDoc.class, (List<String>) null);
        assertThat(res1).isEmpty();

        List<UserDoc> res2 = searchTemplate.getByIds(UserDoc.class, Collections.emptyList());
        assertThat(res2).isEmpty();

        List<UserDoc> res3 = searchTemplate.getByIds(UserDoc.class);
        assertThat(res3).isEmpty();

        assertThat(mockWebServer.getRequestCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("测试 getHitsByIds 返回包含 seqNo/primaryTerm/version 的完整命中文档载荷")
    void testGetHitsByIdsSuccess() throws Exception {
        String mockResponseBody = """
                {
                  "docs": [
                    {
                      "_index": "user_idx",
                      "_id": "1001",
                      "_version": 3,
                      "_seq_no": 8,
                      "_primary_term": 2,
                      "_routing": "route_east",
                      "found": true,
                      "_source": {
                        "id": "1001",
                        "username": "ateng",
                        "email": "ateng@example.com",
                        "age": 18,
                        "createTime": "2026-10-25 12:00:00"
                      }
                    }
                  ]
                }
                """;
        mockWebServer.enqueue(esResponse(200, mockResponseBody));

        List<SearchHit<UserDoc>> hits = searchTemplate.getHitsByIds(UserDoc.class, "1001");

        assertThat(hits).hasSize(1);
        SearchHit<UserDoc> hit = hits.get(0);
        assertThat(hit.getId()).isEqualTo("1001");
        assertThat(hit.getIndex()).isEqualTo("user_idx");
        assertThat(hit.getVersion()).isEqualTo(3L);
        assertThat(hit.getSeqNo()).isEqualTo(8L);
        assertThat(hit.getPrimaryTerm()).isEqualTo(2L);
        assertThat(hit.getRouting()).isEqualTo("route_east");
        assertThat(hit.getContent()).isNotNull();
        assertThat(hit.getContent().getUsername()).isEqualTo("ateng");
    }

    @Test
    @DisplayName("测试 getByIds 指定索引时目标索引不存在保底返回空集合")
    void testGetByIdsIndexNotFound() {
        String mockResponseBody = """
                {
                  "error": {
                    "type": "index_not_found_exception",
                    "reason": "no such index [missing_idx]"
                  },
                  "status": 404
                }
                """;
        mockWebServer.enqueue(esResponse(404, mockResponseBody));

        List<UserDoc> users = searchTemplate.getByIds("missing_idx", UserDoc.class, List.of("1001"));
        assertThat(users).isNotNull();
        assertThat(users).isEmpty();
    }

    @Test
    @DisplayName("测试 deleteByQuery 成功条件批量删除文档")
    void testDeleteByQuerySuccess() throws Exception {
        String mockResponseBody = """
                {
                  "took": 15,
                  "timed_out": false,
                  "total": 10,
                  "deleted": 10,
                  "batches": 1,
                  "version_conflicts": 0,
                  "noops": 0,
                  "retries": { "bulk": 0, "search": 0 },
                  "throttled_millis": 0,
                  "requests_per_second": -1.0,
                  "throttled_until_millis": 0,
                  "failures": []
                }
                """;
        mockWebServer.enqueue(esResponse(200, mockResponseBody));

        SearchQuery query = SearchQuery.builder().term("status", "CANCELLED").build();
        long deleted = searchTemplate.deleteByQuery(query, UserDoc.class);

        assertThat(deleted).isEqualTo(10L);

        RecordedRequest recordedRequest = mockWebServer.takeRequest();
        assertThat(recordedRequest.getMethod()).isEqualTo("POST");
        assertThat(recordedRequest.getPath()).contains("/user_idx/_delete_by_query");
        assertThat(recordedRequest.getPath()).contains("conflicts=proceed");
        String requestBody = recordedRequest.getBody().readUtf8();
        assertThat(requestBody).contains("\"term\":{\"status\":{\"value\":\"CANCELLED\"}}");
    }

    @Test
    @DisplayName("测试 deleteByQuery 多租户自动隐式注入租户隔离过滤")
    void testDeleteByQueryWithTenantIsolation() throws Exception {
        TenantContextHolder.setTenantId("tenant_001");
        String mockResponseBody = """
                {
                  "took": 8,
                  "timed_out": false,
                  "total": 3,
                  "deleted": 3,
                  "batches": 1,
                  "version_conflicts": 0,
                  "noops": 0,
                  "retries": { "bulk": 0, "search": 0 },
                  "throttled_millis": 0,
                  "requests_per_second": -1.0,
                  "throttled_until_millis": 0,
                  "failures": []
                }
                """;
        mockWebServer.enqueue(esResponse(200, mockResponseBody));

        long deleted = searchTemplate.deleteByQuery(SearchQuery.builder().matchAll().build(), UserDoc.class);

        assertThat(deleted).isEqualTo(3L);

        RecordedRequest recordedRequest = mockWebServer.takeRequest();
        assertThat(recordedRequest.getMethod()).isEqualTo("POST");
        assertThat(recordedRequest.getPath()).contains("/user_idx/_delete_by_query");
        String requestBody = recordedRequest.getBody().readUtf8();
        assertThat(requestBody).contains("\"term\":{\"tenant_id\":{\"value\":\"tenant_001\"}}");
    }

    @Test
    @DisplayName("测试 deleteByQuery 实体标注 @IgnoreTenant 时豁免多租户过滤")
    void testDeleteByQueryWithIgnoredTenant() throws Exception {
        TenantContextHolder.setTenantId("tenant_001");
        String mockResponseBody = """
                {
                  "took": 12,
                  "timed_out": false,
                  "total": 8,
                  "deleted": 8,
                  "batches": 1,
                  "version_conflicts": 0,
                  "noops": 0,
                  "retries": { "bulk": 0, "search": 0 },
                  "throttled_millis": 0,
                  "requests_per_second": -1.0,
                  "throttled_until_millis": 0,
                  "failures": []
                }
                """;
        mockWebServer.enqueue(esResponse(200, mockResponseBody));

        long deleted = searchTemplate.deleteByQuery(SearchQuery.builder().matchAll().build(), IgnoredTenantDoc.class);

        assertThat(deleted).isEqualTo(8L);

        RecordedRequest recordedRequest = mockWebServer.takeRequest();
        assertThat(recordedRequest.getMethod()).isEqualTo("POST");
        assertThat(recordedRequest.getPath()).contains("/global_dict_idx/_delete_by_query");
        String requestBody = recordedRequest.getBody().readUtf8();
        assertThat(requestBody).doesNotContain("\"tenant_id\"");
    }

    @Test
    @DisplayName("测试 deleteByQuery 指定显式索引与 routing 路由参数")
    void testDeleteByQueryWithExplicitIndexAndRouting() throws Exception {
        String mockResponseBody = """
                {
                  "took": 6,
                  "timed_out": false,
                  "total": 5,
                  "deleted": 5,
                  "batches": 1,
                  "version_conflicts": 0,
                  "noops": 0,
                  "retries": { "bulk": 0, "search": 0 },
                  "throttled_millis": 0,
                  "requests_per_second": -1.0,
                  "throttled_until_millis": 0,
                  "failures": []
                }
                """;
        mockWebServer.enqueue(esResponse(200, mockResponseBody));

        SearchQuery query = SearchQuery.builder().routing("route_east").term("type", "audit").build();
        long deleted = searchTemplate.deleteByQuery("custom_audit_idx", query);

        assertThat(deleted).isEqualTo(5L);

        RecordedRequest recordedRequest = mockWebServer.takeRequest();
        assertThat(recordedRequest.getMethod()).isEqualTo("POST");
        assertThat(recordedRequest.getPath()).contains("/custom_audit_idx/_delete_by_query");
        assertThat(recordedRequest.getPath()).contains("routing=route_east");
        assertThat(recordedRequest.getPath()).contains("conflicts=proceed");
    }

    @Test
    @DisplayName("测试 deleteByQuery 当目标索引不存在时保底返回 0")
    void testDeleteByQueryIndexNotFound() {
        String mockResponseBody = """
                {
                  "error": {
                    "type": "index_not_found_exception",
                    "reason": "no such index [non_existing_idx]"
                  },
                  "status": 404
                }
                """;
        mockWebServer.enqueue(esResponse(404, mockResponseBody));

        long deleted = searchTemplate.deleteByQuery("non_existing_idx", SearchQuery.builder().build());
        assertThat(deleted).isEqualTo(0L);
    }

    @Test
    @DisplayName("测试 deleteByQuery 底层服务端错误转译为 SearchException 且保留原因")
    void testDeleteByQueryDriverErrorTranslation() {
        mockWebServer.enqueue(new MockResponse().setResponseCode(500).setBody("Internal Server Error"));

        assertThatThrownBy(() -> searchTemplate.deleteByQuery("user_idx", SearchQuery.builder().build()))
                .isInstanceOf(SearchException.class)
                .satisfies(ex -> {
                    SearchException se = (SearchException) ex;
                    assertThat(se.getResultCode()).isEqualTo(SearchResultCode.SEARCH_DOCUMENT_DELETE_ERROR);
                    assertThat(se.getMessage()).contains("Failed to execute deleteByQuery");
                });
    }

    @Test
    @DisplayName("测试 deleteByQuery 参数校验防线：非空与非空白校验")
    void testDeleteByQueryParameterValidation() {
        // 1. null query 前置拦截
        assertThatThrownBy(() -> searchTemplate.deleteByQuery(null, UserDoc.class))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("query must not be null");

        assertThatThrownBy(() -> searchTemplate.deleteByQuery("user_idx", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("query must not be null");

        // 2. null clazz 前置拦截
        assertThatThrownBy(() -> searchTemplate.deleteByQuery(SearchQuery.builder().build(), null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("clazz must not be null");

        // 3. blank index 前置拦截
        assertThatThrownBy(() -> searchTemplate.deleteByQuery("  ", SearchQuery.builder().build()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Index must not be blank");
    }

    @Test
    @DisplayName("测试 count 与 deleteByQuery 支持多索引查询时不丢失后续索引")
    void testMultiIndicesHandlingOnCountAndDel() throws Exception {
        // 1. count 多索引测试
        String mockCountBody = """
                {
                  "count": 42,
                  "_shards": { "total": 2, "successful": 2, "skipped": 0, "failed": 0 }
                }
                """;
        mockWebServer.enqueue(esResponse(200, mockCountBody));

        SearchQuery multiIndexQuery = SearchQuery.builder()
                .indices(List.of("idx_log_202610", "idx_log_202611"))
                .matchAll()
                .build();

        long count = searchTemplate.count(multiIndexQuery, UserDoc.class);
        assertThat(count).isEqualTo(42L);

        RecordedRequest countReq = mockWebServer.takeRequest();
        assertThat(countReq.getPath()).contains("idx_log_202610,idx_log_202611/_count");

        // 2. deleteByQuery 多索引测试
        String mockDelBody = """
                {
                  "took": 10,
                  "timed_out": false,
                  "total": 5,
                  "deleted": 5,
                  "batches": 1,
                  "version_conflicts": 0,
                  "noops": 0,
                  "retries": { "bulk": 0, "search": 0 },
                  "throttled_millis": 0,
                  "requests_per_second": -1.0,
                  "throttled_until_millis": 0,
                  "failures": []
                }
                """;
        mockWebServer.enqueue(esResponse(200, mockDelBody));

        long deleted = searchTemplate.deleteByQuery(multiIndexQuery, UserDoc.class);
        assertThat(deleted).isEqualTo(5L);

        RecordedRequest delReq = mockWebServer.takeRequest();
        assertThat(delReq.getPath()).contains("idx_log_202610,idx_log_202611/_delete_by_query");
    }

    @Test
    @DisplayName("测试 matchPhrase 短语精准保序检索 DSL 生成")
    void testMatchPhraseDsl() throws Exception {
        String mockResp = """
                {
                  "took": 2,
                  "timed_out": false,
                  "_shards": { "total": 1, "successful": 1, "skipped": 0, "failed": 0 },
                  "hits": { "total": { "value": 0, "relation": "eq" }, "hits": [] }
                }
                """;
        mockWebServer.enqueue(esResponse(200, mockResp));

        SearchQuery query = SearchQuery.builder().matchPhrase("intro", "quick brown fox").build();
        searchTemplate.search(query, UserDoc.class);

        RecordedRequest req = mockWebServer.takeRequest();
        String body = req.getBody().readUtf8();
        assertThat(body).contains("\"match_phrase\":{\"intro\":{\"query\":\"quick brown fox\"}}");
    }

    @Test
    @DisplayName("测试 matchPhrasePrefix 搜索联想自动补全 DSL 生成及 maxExpansions 参数")
    void testMatchPhrasePrefixDsl() throws Exception {
        String mockResp = """
                {
                  "took": 2,
                  "timed_out": false,
                  "_shards": { "total": 1, "successful": 1, "skipped": 0, "failed": 0 },
                  "hits": { "total": { "value": 0, "relation": "eq" }, "hits": [] }
                }
                """;
        mockWebServer.enqueue(esResponse(200, mockResp));

        SearchQuery query = SearchQuery.builder().matchPhrasePrefix("title", "spring b", 20).build();
        searchTemplate.search(query, UserDoc.class);

        RecordedRequest req = mockWebServer.takeRequest();
        String body = req.getBody().readUtf8();
        assertThat(body).contains("\"match_phrase_prefix\":{\"title\":{\"max_expansions\":20,\"query\":\"spring b\"}}");
    }

    @Test
    @DisplayName("测试 fuzzy 拼写容错检索 DSL 生成及 fuzziness 参数")
    void testFuzzyDsl() throws Exception {
        String mockResp = """
                {
                  "took": 2,
                  "timed_out": false,
                  "_shards": { "total": 1, "successful": 1, "skipped": 0, "failed": 0 },
                  "hits": { "total": { "value": 0, "relation": "eq" }, "hits": [] }
                }
                """;
        mockWebServer.enqueue(esResponse(200, mockResp));

        SearchQuery query = SearchQuery.builder().fuzzy("username", "atengg", "AUTO").build();
        searchTemplate.search(query, UserDoc.class);

        RecordedRequest req = mockWebServer.takeRequest();
        String body = req.getBody().readUtf8();
        assertThat(body).contains("\"fuzzy\":{\"username\":{\"fuzziness\":\"AUTO\",\"value\":\"atengg\"}}");
    }

    @Test
    @DisplayName("测试 nested 嵌套对象作用域独立匹配 DSL 生成及 scoreMode 参数")
    void testNestedDsl() throws Exception {
        String mockResp = """
                {
                  "took": 2,
                  "timed_out": false,
                  "_shards": { "total": 1, "successful": 1, "skipped": 0, "failed": 0 },
                  "hits": { "total": { "value": 0, "relation": "eq" }, "hits": [] }
                }
                """;
        mockWebServer.enqueue(esResponse(200, mockResp));

        SearchQuery query = SearchQuery.builder().nested("skus", ChildScoreMode.Avg, sub -> sub
                .term("skus.color", "white")
                .gte("skus.price", 99)).build();
        searchTemplate.search(query, UserDoc.class);

        RecordedRequest req = mockWebServer.takeRequest();
        String body = req.getBody().readUtf8();
        assertThat(body).contains("\"nested\":{\"path\":\"skus\"");
        assertThat(body).contains("\"score_mode\":\"avg\"");
        assertThat(body).contains("\"term\":{\"skus.color\":{\"value\":\"white\"}}");
        assertThat(body).contains("\"range\":{\"skus.price\":{\"gte\":99}}");
    }

    @Test
    @DisplayName("测试 collapse 字段折叠去重 DSL 生成及 SearchTemplate 请求下发")
    void testCollapseDsl() throws Exception {
        String mockResp = """
                {
                  "took": 2,
                  "timed_out": false,
                  "_shards": { "total": 1, "successful": 1, "skipped": 0, "failed": 0 },
                  "hits": { "total": { "value": 0, "relation": "eq" }, "hits": [] }
                }
                """;
        mockWebServer.enqueue(esResponse(200, mockResp));

        SearchQuery query = SearchQuery.builder()
                .term("status", "ACTIVE")
                .collapse("brand.keyword")
                .build();
        searchTemplate.search(query, UserDoc.class);

        RecordedRequest req = mockWebServer.takeRequest();
        String body = req.getBody().readUtf8();
        assertThat(body).contains("\"collapse\":{\"field\":\"brand.keyword\"}");
        assertThat(body).contains("\"term\":{\"status\":{\"value\":\"ACTIVE\"}}");

        // 测试直接传入原生 FieldCollapse 对象
        mockWebServer.enqueue(esResponse(200, mockResp));
        SearchQuery rawCollapseQuery = SearchQuery.builder()
                .collapse(FieldCollapse.of(fc -> fc.field("spuId")))
                .build();
        searchTemplate.search(rawCollapseQuery, UserDoc.class);

        RecordedRequest req2 = mockWebServer.takeRequest();
        String body2 = req2.getBody().readUtf8();
        assertThat(body2).contains("\"collapse\":{\"field\":\"spuId\"}");
    }

    @Test
    @DisplayName("测试 minAggregation 与 maxAggregation 极值度量算子 DSL 生成")
    void testMinAndMaxAggregationDsl() throws Exception {
        String mockResp = """
                {
                  "took": 2,
                  "timed_out": false,
                  "_shards": { "total": 1, "successful": 1, "skipped": 0, "failed": 0 },
                  "hits": { "total": { "value": 0, "relation": "eq" }, "hits": [] }
                }
                """;
        mockWebServer.enqueue(esResponse(200, mockResp));

        SearchQuery query = SearchQuery.builder()
                .minAggregation("min_price", "price")
                .maxAggregation("max_price", "price")
                .build();
        searchTemplate.search(query, UserDoc.class);

        RecordedRequest req = mockWebServer.takeRequest();
        String body = req.getBody().readUtf8();
        assertThat(body).contains("\"min_price\":{\"min\":{\"field\":\"price\"}}");
        assertThat(body).contains("\"max_price\":{\"max\":{\"field\":\"price\"}}");
    }

    @Test
    @DisplayName("测试 dateHistogramAggregation 时序直方图分桶聚合算子 DSL 生成")
    void testDateHistogramAggregationDsl() throws Exception {
        String mockResp = """
                {
                  "took": 2,
                  "timed_out": false,
                  "_shards": { "total": 1, "successful": 1, "skipped": 0, "failed": 0 },
                  "hits": { "total": { "value": 0, "relation": "eq" }, "hits": [] }
                }
                """;
        mockWebServer.enqueue(esResponse(200, mockResp));

        SearchQuery query = SearchQuery.builder()
                .dateHistogramAggregation("daily_trend", "createTime", "1d")
                .dateHistogramAggregation("monthly_trend", "createTime", "1M")
                .dateHistogramAggregation("hourly_trend", "createTime", CalendarInterval.Hour)
                .build();
        searchTemplate.search(query, UserDoc.class);

        RecordedRequest req = mockWebServer.takeRequest();
        String body = req.getBody().readUtf8();
        assertThat(body).contains("\"daily_trend\":{\"date_histogram\":{\"calendar_interval\":\"day\",\"field\":\"createTime\"}}");
        assertThat(body).contains("\"monthly_trend\":{\"date_histogram\":{\"calendar_interval\":\"month\",\"field\":\"createTime\"}}");
        assertThat(body).contains("\"hourly_trend\":{\"date_histogram\":{\"calendar_interval\":\"hour\",\"field\":\"createTime\"}}");
    }

    @Test
    @DisplayName("测试 refresh 物理段合并刷新（支持字符串索引与实体类 Class）")
    void testRefreshIndexAndClass() throws Exception {
        String mockResp = """
                {
                  "_shards": {
                    "total": 2,
                    "successful": 2,
                    "failed": 0
                  }
                }
                """;
        mockWebServer.enqueue(esResponse(200, mockResp));

        boolean refreshed = searchTemplate.indices().refresh("order_idx");
        assertThat(refreshed).isTrue();

        RecordedRequest req = mockWebServer.takeRequest();
        assertThat(req.getMethod()).isEqualTo("POST");
        assertThat(req.getPath()).isEqualTo("/order_idx/_refresh");

        // 测试 Class 入参
        mockWebServer.enqueue(esResponse(200, mockResp));
        boolean refreshedDoc = searchTemplate.indices().refresh(UserDoc.class);
        assertThat(refreshedDoc).isTrue();

        RecordedRequest reqDoc = mockWebServer.takeRequest();
        assertThat(reqDoc.getMethod()).isEqualTo("POST");
        assertThat(reqDoc.getPath()).isEqualTo("/user_idx/_refresh");

        // 测试分片刷新失败场景
        String failedShardsResp = """
                {
                  "_shards": {
                    "total": 2,
                    "successful": 1,
                    "failed": 1
                  }
                }
                """;
        mockWebServer.enqueue(esResponse(200, failedShardsResp));
        boolean failedRefresh = searchTemplate.indices().refresh("broken_idx");
        assertThat(failedRefresh).isFalse();
    }

    @Test
    @DisplayName("测试 deleteIndexTemplate 索引模板删除与 404 容错保底")
    void testDeleteIndexTemplate() throws Exception {
        String mockResp = """
                {
                  "acknowledged": true
                }
                """;
        mockWebServer.enqueue(esResponse(200, mockResp));

        boolean deleted = searchTemplate.indices().deleteIndexTemplate("log_template");
        assertThat(deleted).isTrue();

        RecordedRequest req = mockWebServer.takeRequest();
        assertThat(req.getMethod()).isEqualTo("DELETE");
        assertThat(req.getPath()).isEqualTo("/_index_template/log_template");

        // 测试不存在模板返回 false
        String notFoundResp = """
                {
                  "error": {
                    "root_cause": [{ "type": "resource_not_found_exception", "reason": "index template [not_found] missing" }],
                    "type": "resource_not_found_exception",
                    "reason": "index template [not_found] missing"
                  },
                  "status": 404
                }
                """;
        mockWebServer.enqueue(esResponse(404, notFoundResp));
        boolean notFoundDeleted = searchTemplate.indices().deleteIndexTemplate("not_found");
        assertThat(notFoundDeleted).isFalse();
    }

    @Test
    @DisplayName("测试 reindex 数据平滑重索引（全量与条件过滤）")
    void testReindex() throws Exception {
        String mockResp = """
                {
                  "took": 15,
                  "timed_out": false,
                  "total": 50,
                  "updated": 0,
                  "created": 50,
                  "deleted": 0,
                  "batches": 1,
                  "version_conflicts": 0,
                  "noops": 0,
                  "retries": { "bulk": 0, "search": 0 },
                  "throttled_millis": 0,
                  "requests_per_second": -1.0,
                  "throttled_until_millis": 0,
                  "failures": []
                }
                """;
        mockWebServer.enqueue(esResponse(200, mockResp));

        // 1. 全量重索引
        boolean reindexed = searchTemplate.indices().reindex("source_idx", "target_idx");
        assertThat(reindexed).isTrue();

        RecordedRequest req1 = mockWebServer.takeRequest();
        assertThat(req1.getMethod()).isEqualTo("POST");
        assertThat(req1.getPath()).contains("/_reindex");
        assertThat(req1.getPath()).contains("wait_for_completion=true");
        String body1 = req1.getBody().readUtf8();
        assertThat(body1).contains("\"source\":{\"index\":[\"source_idx\"]}");
        assertThat(body1).contains("\"dest\":{\"index\":\"target_idx\"}");
        assertThat(body1).contains("\"conflicts\":\"proceed\"");

        // 2. 带条件增量/过滤重索引
        mockWebServer.enqueue(esResponse(200, mockResp));
        SearchQuery filterQuery = SearchQuery.builder().term("status", "ACTIVE").build();
        boolean reindexWithFilter = searchTemplate.indices().reindex("source_idx", "target_idx", filterQuery);
        assertThat(reindexWithFilter).isTrue();

        RecordedRequest req2 = mockWebServer.takeRequest();
        assertThat(req2.getMethod()).isEqualTo("POST");
        assertThat(req2.getPath()).contains("/_reindex");
        assertThat(req2.getPath()).contains("wait_for_completion=true");
        String body2 = req2.getBody().readUtf8();
        assertThat(body2).contains("\"source\":{\"index\":[\"source_idx\"],\"query\":{\"bool\":{\"filter\":[{\"term\":{\"status\":{\"value\":\"ACTIVE\"}}}]}}}");
        assertThat(body2).contains("\"dest\":{\"index\":\"target_idx\"}");
    }

    @Test
    @DisplayName("测试 reindex 源索引与目标索引同名或空白时触发防御性校验")
    void testReindexSelfIndexGuard() {
        assertThatThrownBy(() -> searchTemplate.indices().reindex("same_idx", "same_idx"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceIndex and targetIndex must not be the same");

        assertThatThrownBy(() -> searchTemplate.indices().reindex(" ", "target_idx"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceIndex must not be blank");
    }

    @Test
    @DisplayName("测试 reindex 服务端响应 timed_out 为 true 时判定失败")
    void testReindexTimedOutReturnsFalse() {
        String mockResp = """
                {
                  "took": 30000,
                  "timed_out": true,
                  "total": 100,
                  "updated": 0,
                  "created": 50,
                  "deleted": 0,
                  "batches": 1,
                  "version_conflicts": 0,
                  "noops": 0,
                  "retries": { "bulk": 0, "search": 0 },
                  "throttled_millis": 0,
                  "requests_per_second": -1.0,
                  "throttled_until_millis": 0,
                  "failures": []
                }
                """;
        mockWebServer.enqueue(esResponse(200, mockResp));

        boolean result = searchTemplate.indices().reindex("source_idx", "target_idx");
        assertThat(result).isFalse();
    }

    @Test
    @DisplayName("测试 matchPhrase 与 matchPhrasePrefix 空白字符串防卫")
    void testMatchPhraseBlankQueryDefense() {
        SearchQuery query = SearchQuery.builder()
                .matchPhrase("title", "   ")
                .matchPhrasePrefix("title", "")
                .build();

        // 空白文本不生成 matchPhrase 节点，缺省回退为 matchAll
        assertThat(query.getQuery().isMatchAll()).isTrue();
    }

    @Test
    @DisplayName("测试 RestClientBuilderCustomizer 出站请求头定制、负数超时拦截与 classpath TrustStore 防卫")
    void testRestClientCustomizerAndSslBounds() throws Exception {
        AtengSearchProperties props = new AtengSearchProperties();
        props.setUris(Collections.singletonList("http://" + mockWebServer.getHostName() + ":" + mockWebServer.getPort()));
        props.setConnectionRequestTimeout(java.time.Duration.ofSeconds(2));

        ElasticsearchClientDriver customDriver = new ElasticsearchClientDriver(
                props,
                null,
                List.of((builder, p) -> builder.setDefaultHeaders(
                        new org.apache.http.Header[]{new org.apache.http.message.BasicHeader("X-Custom-Header", "ateng-search")}
                ))
        );
        try {
            SearchTemplate customTemplate = new SearchTemplate(customDriver, props);
            mockWebServer.enqueue(esResponse(200, "{\"_index\":\"user_idx\",\"_id\":\"1\",\"found\":false}"));
            customTemplate.getById(UserDoc.class, "1");

            RecordedRequest req = mockWebServer.takeRequest();
            assertThat(req.getHeader("X-Custom-Header")).isEqualTo("ateng-search");
        } finally {
            customDriver.close();
        }

        // 负数超时 Fail-Fast
        AtengSearchProperties negativeProps = new AtengSearchProperties();
        negativeProps.setConnectionRequestTimeout(java.time.Duration.ofMillis(-1));
        assertThatThrownBy(() -> new ElasticsearchClientDriver(negativeProps))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("connectionRequestTimeout");

        // classpath: 不存在证书时转译为 SearchException 且保留原因链
        AtengSearchProperties sslProps = new AtengSearchProperties();
        sslProps.getSsl().setEnabled(true);
        sslProps.getSsl().setTrustStorePath("classpath:non-existent-truststore.jks");
        assertThatThrownBy(() -> new ElasticsearchClientDriver(sslProps))
                .isInstanceOf(SearchException.class)
                .hasMessageContaining("SSL")
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    @DisplayName("测试有效 classpath TrustStore 加载、超大 Duration 饱和截断与 connectionRequestTimeout 连接池等待超时")
    void testValidClasspathTrustStoreAndConnectionPoolTimeout(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws Exception {
        // 1. 生成有效默认类型 TrustStore 并验证 classpath: 加载成功且释放流
        java.nio.file.Path trustStoreFile = tempDir.resolve("test-search-truststore.keystore");
        java.security.KeyStore keyStore = java.security.KeyStore.getInstance(java.security.KeyStore.getDefaultType());
        keyStore.load(null, "changeit".toCharArray());
        try (java.io.OutputStream os = java.nio.file.Files.newOutputStream(trustStoreFile)) {
            keyStore.store(os, "changeit".toCharArray());
        }

        ClassLoader originalCl = Thread.currentThread().getContextClassLoader();
        try (java.net.URLClassLoader urlClassLoader = new java.net.URLClassLoader(
                new java.net.URL[]{tempDir.toUri().toURL()}, originalCl)) {
            Thread.currentThread().setContextClassLoader(urlClassLoader);

            AtengSearchProperties validSslProps = new AtengSearchProperties();
            validSslProps.setUris(Collections.singletonList("https://" + mockWebServer.getHostName() + ":" + mockWebServer.getPort()));
            validSslProps.getSsl().setEnabled(true);
            validSslProps.getSsl().setTrustStorePath("classpath:" + trustStoreFile.getFileName());
            validSslProps.getSsl().setTrustStorePassword("changeit");

            try (ElasticsearchClientDriver sslDriver = new ElasticsearchClientDriver(validSslProps)) {
                assertThat(sslDriver.getElasticsearchClient()).isNotNull();
            }
        } finally {
            Thread.currentThread().setContextClassLoader(originalCl);
        }

        // 2. 超大 Duration（超过 Integer.MAX_VALUE 毫秒）饱和截断不溢出
        AtengSearchProperties hugeTimeoutProps = new AtengSearchProperties();
        hugeTimeoutProps.setUris(Collections.singletonList("http://" + mockWebServer.getHostName() + ":" + mockWebServer.getPort()));
        hugeTimeoutProps.setConnectTimeout(java.time.Duration.ofDays(30));
        hugeTimeoutProps.setSocketTimeout(java.time.Duration.ofDays(30));
        hugeTimeoutProps.setConnectionRequestTimeout(java.time.Duration.ofDays(30));
        try (ElasticsearchClientDriver hugeDriver = new ElasticsearchClientDriver(hugeTimeoutProps)) {
            assertThat(hugeDriver.getElasticsearchClient()).isNotNull();
        }

        // 3. 单连接池饱和时验证 connectionRequestTimeout 生效快速超时
        AtengSearchProperties poolProps = new AtengSearchProperties();
        poolProps.setUris(Collections.singletonList("http://" + mockWebServer.getHostName() + ":" + mockWebServer.getPort()));
        poolProps.setMaxConnections(1);
        poolProps.setMaxConnectionsPerRoute(1);
        poolProps.setConnectionRequestTimeout(java.time.Duration.ofMillis(80));
        poolProps.setSocketTimeout(java.time.Duration.ofSeconds(5));

        mockWebServer.enqueue(esResponse(200, "{\"_index\":\"user_idx\",\"_id\":\"slow\",\"found\":false}")
                .setBodyDelay(500, java.util.concurrent.TimeUnit.MILLISECONDS));
        mockWebServer.enqueue(esResponse(200, "{\"_index\":\"user_idx\",\"_id\":\"fast\",\"found\":false}"));

        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (ElasticsearchClientDriver poolDriver = new ElasticsearchClientDriver(poolProps)) {
            SearchTemplate poolTemplate = new SearchTemplate(poolDriver, poolProps);
            java.util.concurrent.Future<UserDoc> firstFuture = executor.submit(() -> poolTemplate.getById(UserDoc.class, "slow"));
            Thread.sleep(25);
            assertThatThrownBy(() -> poolTemplate.getById(UserDoc.class, "fast"))
                    .isInstanceOf(SearchException.class)
                    .extracting(ex -> ((SearchException) ex).getResultCode())
                    .isEqualTo(SearchResultCode.SEARCH_TIMEOUT_ERROR);
            assertThat(firstFuture.get()).isNull();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @DisplayName("测试 SearchInterceptor SPI 拦截链前后置执行、属性共享与异常回调")
    void testSearchInterceptorLifecycle() {
        List<String> events = new java.util.ArrayList<>();
        io.github.atengk.boot.search.spi.SearchInterceptor first = new io.github.atengk.boot.search.spi.SearchInterceptor() {
            @Override
            public void beforeExecute(io.github.atengk.boot.search.spi.SearchInvocationContext context) {
                context.setAttribute("traceTag", "first");
                events.add("before-1:" + context.getOperation() + ":" + context.getIndex());
            }

            @Override
            public void afterExecute(io.github.atengk.boot.search.spi.SearchInvocationContext context, Object result) {
                events.add("after-1:" + context.getAttribute("traceTag"));
            }

            @Override
            public void onError(io.github.atengk.boot.search.spi.SearchInvocationContext context, Throwable cause) {
                events.add("error-1:" + cause.getClass().getSimpleName());
            }
        };
        io.github.atengk.boot.search.spi.SearchInterceptor second = new io.github.atengk.boot.search.spi.SearchInterceptor() {
            @Override
            public void beforeExecute(io.github.atengk.boot.search.spi.SearchInvocationContext context) {
                events.add("before-2:" + context.getAttribute("traceTag"));
            }

            @Override
            public void afterExecute(io.github.atengk.boot.search.spi.SearchInvocationContext context, Object result) {
                events.add("after-2");
            }
        };

        SearchTemplate intercepted = new SearchTemplate(driver, new AtengSearchProperties(), null, List.of(first, second));
        mockWebServer.enqueue(esResponse(200,
                "{\"took\":1,\"timed_out\":false,\"_shards\":{\"total\":1,\"successful\":1,\"skipped\":0,\"failed\":0},\"hits\":{\"total\":{\"value\":0,\"relation\":\"eq\"},\"hits\":[]}}"));

        intercepted.search(SearchQueryBuilder.create().matchAll().build(), UserDoc.class);
        assertThat(events).containsExactly("before-1:search:user_idx", "before-2:first", "after-2", "after-1:first");

        events.clear();
        mockWebServer.enqueue(esResponse(500, "{\"error\":{\"type\":\"search_phase_execution_exception\",\"reason\":\"boom\"},\"status\":500}"));
        assertThatThrownBy(() -> intercepted.search(SearchQueryBuilder.create().matchAll().build(), UserDoc.class))
                .isInstanceOf(SearchException.class);
        assertThat(events).anyMatch(e -> e.startsWith("error-1:"));
    }

    @Test
    @DisplayName("测试慢查询向量数组脱敏日志输出与 HTTP 429/401 异常精细分类转译")
    void testSlowQueryVectorMaskingAndClassifierMapping() {
        ch.qos.logback.classic.Logger templateLogger =
                (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(SearchTemplate.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> listAppender =
                new ch.qos.logback.core.read.ListAppender<>();
        listAppender.start();
        templateLogger.addAppender(listAppender);

        try {
            AtengSearchProperties props = new AtengSearchProperties();
            props.getObservability().setSlowQueryThreshold(java.time.Duration.ofNanos(1));
            props.getObservability().setLogSlowQueryDsl(true);
            props.getObservability().setMaskVectorInLogs(true);

            io.micrometer.core.instrument.simple.SimpleMeterRegistry meterRegistry =
                    new io.micrometer.core.instrument.simple.SimpleMeterRegistry();
            io.github.atengk.boot.search.metrics.SearchMetrics metrics =
                    new io.github.atengk.boot.search.metrics.SearchMetrics(meterRegistry);

            SearchTemplate observedTemplate = new SearchTemplate(driver, props, metrics);

            mockWebServer.enqueue(esResponse(200,
                    "{\"took\":1,\"timed_out\":false,\"_shards\":{\"total\":1,\"successful\":1,\"skipped\":0,\"failed\":0},\"hits\":{\"total\":{\"value\":0,\"relation\":\"eq\"},\"hits\":[]}}"));

            SearchQuery knnQuery = SearchQueryBuilder.create()
                    .knn("embedding", List.of(0.123456f, -0.654321f, 0.999999f), 5, 20)
                    .build();
            observedTemplate.search(knnQuery, UserDoc.class);

            assertThat(listAppender.list)
                    .extracting(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                    .anySatisfy(msg -> {
                        assertThat(msg).contains("Slow search operation detected");
                        assertThat(msg).contains("<vector masked: 3 dims>");
                        assertThat(msg).doesNotContain("0.123456");
                    });

            assertThat(meterRegistry.find("ateng.search.operation.time")
                    .tag("operation", "search")
                    .tag("outcome", "SUCCESS")
                    .timer())
                    .isNotNull()
                    .satisfies(timer -> assertThat(timer.count()).isEqualTo(1L));

            // 429 限流 -> SEARCH_RATE_LIMITED
            mockWebServer.enqueue(esResponse(429, "{\"error\":{\"type\":\"es_rejected_execution_exception\",\"reason\":\"rejected execution\"},\"status\":429}"));
            assertThatThrownBy(() -> observedTemplate.search(SearchQueryBuilder.create().matchAll().build(), UserDoc.class))
                    .isInstanceOf(SearchException.class)
                    .extracting(ex -> ((SearchException) ex).getResultCode())
                    .isEqualTo(SearchResultCode.SEARCH_RATE_LIMITED);

            // 401 鉴权失败 -> SEARCH_AUTH_ERROR
            mockWebServer.enqueue(esResponse(401, "{\"error\":{\"type\":\"security_exception\",\"reason\":\"unauthorized\"},\"status\":401}"));
            assertThatThrownBy(() -> observedTemplate.search(SearchQueryBuilder.create().matchAll().build(), UserDoc.class))
                    .isInstanceOf(SearchException.class)
                    .extracting(ex -> ((SearchException) ex).getResultCode())
                    .isEqualTo(SearchResultCode.SEARCH_AUTH_ERROR);
        } finally {
            templateLogger.detachAppender(listAppender);
            listAppender.stop();
        }
    }

    @io.github.atengk.boot.search.annotation.SearchIndex("record_order_idx")
    record RecordOrder(
            @io.github.atengk.boot.search.annotation.SearchId String id,
            String title,
            String tenant_id
    ) {
    }

    @Test
    @DisplayName("测试写入与局部更新阶段多租户自动注入（POJO/Record/Map）及跨租户篡改快速失败")
    void testTenantWriteInjectionAndTamperingFailFast() throws Exception {
        TenantContextHolder.setTenantId("tenant_a");

        // 1. 无 tenant_id 字段的 POJO 写入自动注入 tenant_id（STRICT 下显式 ID 先查归属）
        mockWebServer.enqueue(esResponse(404, "{\"_index\":\"user_idx\",\"_id\":\"101\",\"found\":false}"));
        mockWebServer.enqueue(esResponse(201, "{\"_index\":\"user_idx\",\"_id\":\"101\",\"_version\":1,\"result\":\"created\",\"_seq_no\":1,\"_primary_term\":1,\"_shards\":{\"total\":1,\"successful\":1,\"failed\":0}}"));
        searchTemplate.save(UserDoc.builder().id(101L).username("alice").build());
        mockWebServer.takeRequest(); // pre-check GET
        RecordedRequest pojoReq = mockWebServer.takeRequest();
        assertThat(pojoReq.getBody().readUtf8()).contains("\"tenant_id\":\"tenant_a\"");

        // 2. 不可变 Record (tenant_id=null) 写入自动注入 tenant_id
        mockWebServer.enqueue(esResponse(404, "{\"_index\":\"record_order_idx\",\"_id\":\"R-1\",\"found\":false}"));
        mockWebServer.enqueue(esResponse(201, "{\"_index\":\"record_order_idx\",\"_id\":\"R-1\",\"_version\":1,\"result\":\"created\",\"_seq_no\":1,\"_primary_term\":1,\"_shards\":{\"total\":1,\"successful\":1,\"failed\":0}}"));
        searchTemplate.save(new RecordOrder("R-1", "order-1", null));
        mockWebServer.takeRequest(); // pre-check GET
        RecordedRequest recordReq = mockWebServer.takeRequest();
        assertThat(recordReq.getBody().readUtf8()).contains("\"tenant_id\":\"tenant_a\"");

        // 3. Map 局部更新自动注入 tenant_id（STRICT 模式下先核验归属再发送 _update）
        mockWebServer.enqueue(esResponse(200, "{\"_index\":\"user_idx\",\"_id\":\"101\",\"found\":true,\"_source\":{\"tenant_id\":\"tenant_a\"}}"));
        mockWebServer.enqueue(esResponse(200, "{\"_index\":\"user_idx\",\"_id\":\"101\",\"_version\":2,\"result\":\"updated\",\"_seq_no\":2,\"_primary_term\":1,\"_shards\":{\"total\":1,\"successful\":1,\"failed\":0}}"));
        searchTemplate.update("user_idx", "101", Map.of("username", "alice-2"));
        mockWebServer.takeRequest(); // pre-check GET
        RecordedRequest mapUpdateReq = mockWebServer.takeRequest();
        assertThat(mapUpdateReq.getBody().readUtf8()).contains("\"tenant_id\":\"tenant_a\"");

        // 4. 批量索引与批量更新自动注入 tenant_id（STRICT 下显式 ID 先发 _mget 预检）
        mockWebServer.enqueue(esResponse(200, "{\"docs\":[{\"_index\":\"record_order_idx\",\"_id\":\"R-2\",\"found\":false}]}"));
        mockWebServer.enqueue(esResponse(200, "{\"took\":1,\"errors\":false,\"items\":[{\"index\":{\"_index\":\"record_order_idx\",\"_id\":\"R-2\",\"status\":201,\"result\":\"created\"}}]}"));
        searchTemplate.indexBatch(List.of(new RecordOrder("R-2", "order-2", null)));
        mockWebServer.takeRequest(); // pre-check _mget
        RecordedRequest bulkIdxReq = mockWebServer.takeRequest();
        assertThat(bulkIdxReq.getBody().readUtf8()).contains("\"tenant_id\":\"tenant_a\"");

        // 5. 跨租户篡改（携带 tenant_b）在单写入、局部更新、批量写入及 BulkProcessor 入口全部立即 Fail-Fast（零 HTTP 请求发出）
        int requestCountBeforeTamper = mockWebServer.getRequestCount();
        assertThatThrownBy(() -> searchTemplate.save(new RecordOrder("R-3", "tamper", "tenant_b")))
                .isInstanceOf(SearchException.class)
                .extracting(e -> ((SearchException) e).getResultCode())
                .isEqualTo(SearchResultCode.SEARCH_TENANT_ACCESS_DENIED);
        assertThatThrownBy(() -> searchTemplate.update("user_idx", "101", Map.of("tenant_id", "tenant_b", "username", "hacker")))
                .isInstanceOf(SearchException.class)
                .extracting(e -> ((SearchException) e).getResultCode())
                .isEqualTo(SearchResultCode.SEARCH_TENANT_ACCESS_DENIED);
        assertThatThrownBy(() -> searchTemplate.indexBatch(List.of(new RecordOrder("R-4", "ok", "tenant_a"), new RecordOrder("R-5", "tamper", "tenant_b"))))
                .isInstanceOf(SearchException.class)
                .extracting(e -> ((SearchException) e).getResultCode())
                .isEqualTo(SearchResultCode.SEARCH_TENANT_ACCESS_DENIED);
        assertThatThrownBy(() -> searchTemplate.updateBatch(List.of(new RecordOrder("R-6", "tamper", "tenant_b"))))
                .isInstanceOf(SearchException.class)
                .extracting(e -> ((SearchException) e).getResultCode())
                .isEqualTo(SearchResultCode.SEARCH_TENANT_ACCESS_DENIED);
        try (io.github.atengk.boot.search.bulk.SearchBulkProcessor processor = searchTemplate.newBulkProcessorBuilder().build()) {
            assertThatThrownBy(() -> processor.add(new RecordOrder("R-7", "tamper", "tenant_b")))
                    .isInstanceOf(SearchException.class)
                    .extracting(e -> ((SearchException) e).getResultCode())
                    .isEqualTo(SearchResultCode.SEARCH_TENANT_ACCESS_DENIED);
        }
        assertThat(mockWebServer.getRequestCount()).isEqualTo(requestCountBeforeTamper);

        // 6. @IgnoreTenant 实体（含 Map 局部更新重载）与 runWithoutTenant 豁免写入，不注入也不拦截
        mockWebServer.enqueue(esResponse(201, "{\"_index\":\"global_dict_idx\",\"_id\":\"D-1\",\"_version\":1,\"result\":\"created\",\"_seq_no\":1,\"_primary_term\":1,\"_shards\":{\"total\":1,\"successful\":1,\"failed\":0}}"));
        searchTemplate.save(IgnoredTenantDoc.builder().code("D-1").name("dict").build());
        RecordedRequest ignoredEntityReq = mockWebServer.takeRequest();
        assertThat(ignoredEntityReq.getBody().readUtf8()).doesNotContain("\"tenant_id\"");

        mockWebServer.enqueue(esResponse(200, "{\"_index\":\"global_dict_idx\",\"_id\":\"D-1\",\"_version\":2,\"result\":\"updated\",\"_seq_no\":2,\"_primary_term\":1,\"_shards\":{\"total\":1,\"successful\":1,\"failed\":0}}"));
        assertThat(searchTemplate.update(IgnoredTenantDoc.class, "D-1", Map.of("name", "dict-v2"))).isTrue();
        RecordedRequest ignoredUpdateReq = mockWebServer.takeRequest();
        assertThat(ignoredUpdateReq.getPath()).contains("/global_dict_idx/_update/D-1");
        assertThat(ignoredUpdateReq.getBody().readUtf8()).doesNotContain("\"tenant_id\"");

        mockWebServer.enqueue(esResponse(201, "{\"_index\":\"record_order_idx\",\"_id\":\"R-8\",\"_version\":1,\"result\":\"created\",\"_seq_no\":1,\"_primary_term\":1,\"_shards\":{\"total\":1,\"successful\":1,\"failed\":0}}"));
        TenantContextHolder.runWithoutTenant(() -> searchTemplate.save(new RecordOrder("R-8", "admin-write", "tenant_b")));
        RecordedRequest bypassReq = mockWebServer.takeRequest();
        assertThat(bypassReq.getBody().readUtf8()).contains("\"tenant_id\":\"tenant_b\"");

        // 7. STRICT 模式显式 ID save / updateBatch / SearchBulkProcessor.delete 对已存在他租户文档拦截且不发送 PUT/_bulk
        mockWebServer.enqueue(esResponse(200, "{\"_index\":\"user_idx\",\"_id\":\"202\",\"found\":true,\"_source\":{\"tenant_id\":\"tenant_b\"}}"));
        int beforeCrossSave = mockWebServer.getRequestCount();
        assertThatThrownBy(() -> searchTemplate.save(UserDoc.builder().id(202L).username("overwrite-attempt").build()))
                .isInstanceOf(SearchException.class)
                .extracting(e -> ((SearchException) e).getResultCode())
                .isEqualTo(SearchResultCode.SEARCH_TENANT_ACCESS_DENIED);
        assertThat(mockWebServer.getRequestCount()).isEqualTo(beforeCrossSave + 1);
        assertThat(mockWebServer.takeRequest().getMethod()).isEqualTo("GET");

        mockWebServer.enqueue(esResponse(200, "{\"docs\":[{\"_index\":\"record_order_idx\",\"_id\":\"R-9\",\"found\":true,\"_source\":{\"tenant_id\":\"tenant_b\"}}]}"));
        int beforeCrossUpdateBatch = mockWebServer.getRequestCount();
        assertThatThrownBy(() -> searchTemplate.updateBatch(List.of(new RecordOrder("R-9", "hijack", "tenant_a"))))
                .isInstanceOf(SearchException.class)
                .extracting(e -> ((SearchException) e).getResultCode())
                .isEqualTo(SearchResultCode.SEARCH_TENANT_ACCESS_DENIED);
        assertThat(mockWebServer.getRequestCount()).isEqualTo(beforeCrossUpdateBatch + 1);
        assertThat(mockWebServer.takeRequest().getPath()).contains("/_mget");

        mockWebServer.enqueue(esResponse(200, "{\"docs\":[{\"_index\":\"user_idx\",\"_id\":\"303\",\"found\":true,\"_source\":{\"tenant_id\":\"tenant_b\"}}]}"));
        java.util.concurrent.atomic.AtomicReference<Throwable> bulkFailure = new java.util.concurrent.atomic.AtomicReference<>();
        int beforeCrossBulkDelete = mockWebServer.getRequestCount();
        try (io.github.atengk.boot.search.bulk.SearchBulkProcessor processor = searchTemplate.newBulkProcessorBuilder()
                .listener(new io.github.atengk.boot.search.bulk.BulkListener() {
                    @Override
                    public void afterBulk(long executionId, int actionCount, Throwable failure) {
                        bulkFailure.set(failure);
                    }
                })
                .build()) {
            processor.delete("303", UserDoc.class);
            processor.flush();
        }
        assertThat(bulkFailure.get()).isInstanceOf(SearchException.class);
        assertThat(((SearchException) bulkFailure.get()).getResultCode()).isEqualTo(SearchResultCode.SEARCH_TENANT_ACCESS_DENIED);
        assertThat(mockWebServer.getRequestCount()).isEqualTo(beforeCrossBulkDelete + 1);
        assertThat(mockWebServer.takeRequest().getPath()).contains("/_mget");
    }

    @Test
    @DisplayName("测试 STRICT 模式点查、批量主键检索与 existsById 降级 _count 防跨租户 IDOR 探测及 FILTER_ONLY 放行")
    void testStrictPointGetAndExistsAntiIdor() throws Exception {
        TenantContextHolder.setTenantId("tenant_a");

        // 1. STRICT: getById / getHitById 跨租户(tenant_b)与缺失租户列返回 null，同租户(tenant_a)正常返回
        mockWebServer.enqueue(esResponse(200, "{\"_index\":\"user_idx\",\"_id\":\"101\",\"_version\":1,\"_seq_no\":3,\"_primary_term\":1,\"found\":true,\"_source\":{\"id\":101,\"username\":\"bob\",\"tenant_id\":\"tenant_b\"}}"));
        assertThat(searchTemplate.getById(UserDoc.class, "101")).isNull();
        mockWebServer.takeRequest();

        mockWebServer.enqueue(esResponse(200, "{\"_index\":\"user_idx\",\"_id\":\"103\",\"_version\":1,\"_seq_no\":4,\"_primary_term\":1,\"found\":true,\"_source\":{\"id\":103,\"username\":\"orphan\"}}"));
        assertThat(searchTemplate.getHitById(UserDoc.class, "103")).isNull();
        mockWebServer.takeRequest();

        mockWebServer.enqueue(esResponse(200, "{\"_index\":\"user_idx\",\"_id\":\"102\",\"_version\":1,\"_seq_no\":5,\"_primary_term\":1,\"found\":true,\"_source\":{\"id\":102,\"username\":\"alice\",\"tenant_id\":\"tenant_a\"}}"));
        SearchHit<UserDoc> ownedHit = searchTemplate.getHitById(UserDoc.class, "102");
        assertThat(ownedHit).isNotNull();
        assertThat(ownedHit.getContent().getUsername()).isEqualTo("alice");
        assertThat(ownedHit.getSeqNo()).isEqualTo(5L);
        mockWebServer.takeRequest();

        // 2. STRICT: getHitsByIds 自动剔除他租户与无归属文档，仅保留同租户
        mockWebServer.enqueue(esResponse(200, """
                {
                  "docs": [
                    {"_index":"user_idx","_id":"101","_version":1,"_seq_no":1,"_primary_term":1,"found":true,"_source":{"id":101,"username":"bob","tenant_id":"tenant_b"}},
                    {"_index":"user_idx","_id":"102","_version":1,"_seq_no":2,"_primary_term":1,"found":true,"_source":{"id":102,"username":"alice","tenant_id":"tenant_a"}},
                    {"_index":"user_idx","_id":"103","_version":1,"_seq_no":3,"_primary_term":1,"found":true,"_source":{"id":103,"username":"orphan"}}
                  ]
                }
                """));
        List<SearchHit<UserDoc>> mgetHits = searchTemplate.getHitsByIds(UserDoc.class, List.of("101", "102", "103"));
        assertThat(mgetHits).hasSize(1);
        assertThat(mgetHits.get(0).getId()).isEqualTo("102");
        mockWebServer.takeRequest();

        // 3. STRICT: existsById 降级为带 ids + tenant_id 过滤的 _count 探测并保留 routing
        mockWebServer.enqueue(esResponse(200, "{\"count\":0,\"_shards\":{\"total\":1,\"successful\":1,\"skipped\":0,\"failed\":0}}"));
        assertThat(searchTemplate.existsById(UserDoc.class, "101")).isFalse();
        RecordedRequest countDeniedReq = mockWebServer.takeRequest();
        assertThat(countDeniedReq.getMethod()).isEqualTo("POST");
        assertThat(countDeniedReq.getPath()).isEqualTo("/user_idx/_count");
        String countBody = countDeniedReq.getBody().readUtf8();
        assertThat(countBody).contains("\"ids\":{\"values\":[\"101\"]}");
        assertThat(countBody).contains("\"term\":{\"tenant_id\":{\"value\":\"tenant_a\"}}");

        mockWebServer.enqueue(esResponse(200, "{\"count\":1,\"_shards\":{\"total\":1,\"successful\":1,\"skipped\":0,\"failed\":0}}"));
        assertThat(searchTemplate.existsById(RoutedOrderDoc.class, "O1", "route-1")).isTrue();
        RecordedRequest routedCountReq = mockWebServer.takeRequest();
        assertThat(routedCountReq.getPath()).contains("/_count").contains("routing=route-1");

        // 4. @IgnoreTenant / runWithoutTenant 保持轻量 HEAD 与原样放行
        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setHeader("X-Elastic-Product", "Elasticsearch"));
        assertThat(searchTemplate.existsById(IgnoredTenantDoc.class, "D-1")).isTrue();
        assertThat(mockWebServer.takeRequest().getMethod()).isEqualTo("HEAD");

        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setHeader("X-Elastic-Product", "Elasticsearch"));
        assertThat(TenantContextHolder.runWithoutTenant(() -> searchTemplate.existsById(UserDoc.class, "101"))).isTrue();
        assertThat(mockWebServer.takeRequest().getMethod()).isEqualTo("HEAD");

        // 5. FILTER_ONLY 模式：点查不做跨租户过滤，existsById 保留 HEAD
        AtengSearchProperties filterOnlyProps = new AtengSearchProperties();
        filterOnlyProps.getMultiTenancy().setIsolationMode(io.github.atengk.boot.search.enums.TenantIsolationMode.FILTER_ONLY);
        SearchTemplate filterOnlyTemplate = new SearchTemplate(driver, filterOnlyProps);

        mockWebServer.enqueue(esResponse(200, "{\"_index\":\"user_idx\",\"_id\":\"101\",\"_version\":1,\"_seq_no\":3,\"_primary_term\":1,\"found\":true,\"_source\":{\"id\":101,\"username\":\"bob\",\"tenant_id\":\"tenant_b\"}}"));
        assertThat(filterOnlyTemplate.getById(UserDoc.class, "101")).isNotNull();
        mockWebServer.takeRequest();

        mockWebServer.enqueue(new MockResponse().setResponseCode(200).setHeader("X-Elastic-Product", "Elasticsearch"));
        assertThat(filterOnlyTemplate.existsById(UserDoc.class, "101")).isTrue();
        assertThat(mockWebServer.takeRequest().getMethod()).isEqualTo("HEAD");
    }

    @Test
    @DisplayName("测试 STRICT 模式点删改 _source 裁剪核验防越权、OCC/Routing 保留及 reindex 租户边界保护")
    void testStrictPointWriteDeleteAntiIdorAndReindexBoundary() throws Exception {
        TenantContextHolder.setTenantId("tenant_a");

        // 1. STRICT update 归属他租户 -> 前置 GET _source_includes 命中 tenant_b，拦截抛 SEARCH_TENANT_ACCESS_DENIED，不发 _update
        mockWebServer.enqueue(esResponse(200, "{\"_index\":\"routed_order_idx\",\"_id\":\"O-1\",\"found\":true,\"_source\":{\"tenant_id\":\"tenant_b\"}}"));
        RoutedOrderDoc updateDoc = RoutedOrderDoc.builder().orderId("O-1").tenantId("route-1").description("PAID").build();
        assertThatThrownBy(() -> searchTemplate.update(updateDoc, false, io.github.atengk.boot.search.model.OccVersion.of(7L, 2L)))
                .isInstanceOf(SearchException.class)
                .extracting(e -> ((SearchException) e).getResultCode())
                .isEqualTo(SearchResultCode.SEARCH_TENANT_ACCESS_DENIED);
        RecordedRequest preGetUpdate = mockWebServer.takeRequest();
        assertThat(preGetUpdate.getMethod()).isEqualTo("GET");
        assertThat(preGetUpdate.getPath()).contains("_source_includes=tenant_id").contains("routing=route-1");

        // 2. STRICT update (docAsUpsert=false) 文档不存在 -> 返回 false，不发 _update
        mockWebServer.enqueue(esResponse(404, "{\"_index\":\"routed_order_idx\",\"_id\":\"O-404\",\"found\":false}"));
        RoutedOrderDoc missingDoc = RoutedOrderDoc.builder().orderId("O-404").tenantId("route-1").description("PAID").build();
        assertThat(searchTemplate.update(missingDoc, false, (io.github.atengk.boot.search.model.OccVersion) null)).isFalse();
        mockWebServer.takeRequest();

        // 3. STRICT update 同租户校验通过 -> 放行原始 _update 并完整保留 OccVersion 与 routing 及租户列注入
        mockWebServer.enqueue(esResponse(200, "{\"_index\":\"routed_order_idx\",\"_id\":\"O-1\",\"found\":true,\"_source\":{\"tenant_id\":\"tenant_a\"}}"));
        mockWebServer.enqueue(esResponse(200, "{\"_index\":\"routed_order_idx\",\"_id\":\"O-1\",\"_version\":8,\"result\":\"updated\",\"_seq_no\":8,\"_primary_term\":2,\"_shards\":{\"total\":1,\"successful\":1,\"failed\":0}}"));
        assertThat(searchTemplate.update(updateDoc, false, io.github.atengk.boot.search.model.OccVersion.of(7L, 2L))).isTrue();
        mockWebServer.takeRequest(); // pre-check GET
        RecordedRequest actualUpdateReq = mockWebServer.takeRequest();
        assertThat(actualUpdateReq.getPath()).contains("/_update/O-1").contains("if_seq_no=7").contains("if_primary_term=2").contains("routing=route-1");
        assertThat(actualUpdateReq.getBody().readUtf8()).contains("\"tenant_id\":\"tenant_a\"");

        // 4. STRICT deleteById 归属他租户 -> 前置 GET 拦截抛 SEARCH_TENANT_ACCESS_DENIED
        mockWebServer.enqueue(esResponse(200, "{\"_index\":\"routed_order_idx\",\"_id\":\"O-2\",\"found\":true,\"_source\":{\"tenant_id\":\"tenant_b\"}}"));
        assertThatThrownBy(() -> searchTemplate.deleteById(RoutedOrderDoc.class, "O-2", "route-2"))
                .isInstanceOf(SearchException.class)
                .extracting(e -> ((SearchException) e).getResultCode())
                .isEqualTo(SearchResultCode.SEARCH_TENANT_ACCESS_DENIED);
        RecordedRequest preGetDel = mockWebServer.takeRequest();
        assertThat(preGetDel.getPath()).contains("_source_includes=tenant_id").contains("routing=route-2");

        // 5. STRICT deleteById 不存在返回 false；同租户校验通过放行 DELETE 并保留 routing
        mockWebServer.enqueue(esResponse(404, "{\"_index\":\"routed_order_idx\",\"_id\":\"O-404\",\"found\":false}"));
        assertThat(searchTemplate.deleteById(RoutedOrderDoc.class, "O-404", "route-1")).isFalse();
        mockWebServer.takeRequest();

        mockWebServer.enqueue(esResponse(200, "{\"_index\":\"routed_order_idx\",\"_id\":\"O-1\",\"found\":true,\"_source\":{\"tenant_id\":\"tenant_a\"}}"));
        mockWebServer.enqueue(esResponse(200, "{\"_index\":\"routed_order_idx\",\"_id\":\"O-1\",\"_version\":2,\"result\":\"deleted\",\"_seq_no\":2,\"_primary_term\":1,\"_shards\":{\"total\":1,\"successful\":1,\"failed\":0}}"));
        assertThat(searchTemplate.deleteById(RoutedOrderDoc.class, "O-1", "route-1")).isTrue();
        mockWebServer.takeRequest(); // pre-check GET
        RecordedRequest actualDelReq = mockWebServer.takeRequest();
        assertThat(actualDelReq.getMethod()).isEqualTo("DELETE");
        assertThat(actualDelReq.getPath()).contains("routing=route-1");

        // 6. STRICT deleteBatch: 前置 mget _source 裁剪检测到他租户文档立即拦截抛异常，同租户与未命中则放行 bulk
        mockWebServer.enqueue(esResponse(200, """
                {
                  "docs": [
                    {"_index":"user_idx","_id":"1","found":true,"_source":{"tenant_id":"tenant_a"}},
                    {"_index":"user_idx","_id":"2","found":true,"_source":{"tenant_id":"tenant_b"}}
                  ]
                }
                """));
        assertThatThrownBy(() -> searchTemplate.deleteBatch(UserDoc.class, List.of("1", "2")))
                .isInstanceOf(SearchException.class)
                .extracting(e -> ((SearchException) e).getResultCode())
                .isEqualTo(SearchResultCode.SEARCH_TENANT_ACCESS_DENIED);
        RecordedRequest mgetPreDel = mockWebServer.takeRequest();
        assertThat(mgetPreDel.getPath()).contains("/_mget").contains("_source_includes=tenant_id");

        mockWebServer.enqueue(esResponse(200, """
                {
                  "docs": [
                    {"_index":"user_idx","_id":"1","found":true,"_source":{"tenant_id":"tenant_a"}},
                    {"_index":"user_idx","_id":"999","found":false}
                  ]
                }
                """));
        mockWebServer.enqueue(esResponse(200, "{\"took\":1,\"errors\":false,\"items\":[{\"delete\":{\"_index\":\"user_idx\",\"_id\":\"1\",\"status\":200,\"result\":\"deleted\"}},{\"delete\":{\"_index\":\"user_idx\",\"_id\":\"999\",\"status\":404,\"result\":\"not_found\"}}]}"));
        SearchBulkResult bulkDelRes = searchTemplate.deleteBatch(UserDoc.class, List.of("1", "999"));
        assertThat(bulkDelRes.getSuccessCount()).isEqualTo(2);
        mockWebServer.takeRequest(); // mget
        assertThat(mockWebServer.takeRequest().getPath()).contains("/_bulk");

        // 7. FILTER_ONLY 模式下点删改直通，不发前置核验 GET
        AtengSearchProperties filterOnlyProps = new AtengSearchProperties();
        filterOnlyProps.getMultiTenancy().setIsolationMode(io.github.atengk.boot.search.enums.TenantIsolationMode.FILTER_ONLY);
        SearchTemplate filterOnlyTemplate = new SearchTemplate(driver, filterOnlyProps);
        mockWebServer.enqueue(esResponse(200, "{\"_index\":\"user_idx\",\"_id\":\"2\",\"_version\":2,\"result\":\"deleted\",\"_seq_no\":2,\"_primary_term\":1,\"_shards\":{\"total\":1,\"successful\":1,\"failed\":0}}"));
        assertThat(filterOnlyTemplate.deleteById(UserDoc.class, "2")).isTrue();
        assertThat(mockWebServer.takeRequest().getMethod()).isEqualTo("DELETE");

        // 8. reindex 自动注入租户边界过滤；携带跨租户过滤条件时在 STRICT 下阻断
        mockWebServer.enqueue(esResponse(200, "{\"took\":5,\"timed_out\":false,\"total\":3,\"created\":3,\"updated\":0,\"deleted\":0,\"batches\":1,\"version_conflicts\":0,\"noops\":0,\"failures\":[]}"));
        assertThat(searchTemplate.indices().reindex("order_v1", "order_v2")).isTrue();
        RecordedRequest reindexReq = mockWebServer.takeRequest();
        assertThat(reindexReq.getBody().readUtf8()).contains("\"term\":{\"tenant_id\":{\"value\":\"tenant_a\"}}");

        SearchQuery crossTenantReindexQuery = SearchQueryBuilder.create().term("tenant_id", "tenant_b").build();
        assertThatThrownBy(() -> searchTemplate.indices().reindex("order_v1", "order_v2", crossTenantReindexQuery))
                .isInstanceOf(SearchException.class)
                .extracting(e -> ((SearchException) e).getResultCode())
                .isEqualTo(SearchResultCode.SEARCH_TENANT_ACCESS_DENIED);

        // 9. deleteByQuery 携带跨租户过滤条件在 STRICT 下同样立即拦截，且 @IgnoreTenant 实体放行
        assertThatThrownBy(() -> searchTemplate.deleteByQuery(crossTenantReindexQuery, UserDoc.class))
                .isInstanceOf(SearchException.class)
                .extracting(e -> ((SearchException) e).getResultCode())
                .isEqualTo(SearchResultCode.SEARCH_TENANT_ACCESS_DENIED);
    }
}
