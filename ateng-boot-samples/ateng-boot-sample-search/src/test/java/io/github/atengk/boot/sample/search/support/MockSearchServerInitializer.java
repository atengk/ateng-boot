package io.github.atengk.boot.sample.search.support;

import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.ContextClosedEvent;

import java.io.IOException;

/**
 * 100% 内存自洽 Mock Elasticsearch 服务端初始化器
 *
 * @author Ateng
 * @since 2026-10-26
 */
public class MockSearchServerInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    private static MockWebServer server;

    public static MockWebServer getMockServer() {
        return server;
    }

    @Override
    public void initialize(ConfigurableApplicationContext applicationContext) {
        // 当激活 local 环境（直连真实物理中间件）时跳过 MockWebServer 启动
        if (java.util.Arrays.asList(applicationContext.getEnvironment().getActiveProfiles()).contains("local")) {
            return;
        }

        if (server == null) {
            try {
                server = new MockWebServer();
                server.setDispatcher(new ElasticMockDispatcher());
                server.start();

                int port = server.getPort();
                System.setProperty("search.mock.port", String.valueOf(port));
            } catch (IOException e) {
                throw new RuntimeException("Failed to start in-memory MockWebServer", e);
            }

            applicationContext.addApplicationListener(event -> {
                if (event instanceof ContextClosedEvent) {
                    if (server != null) {
                        try {
                            server.shutdown();
                            server = null;
                        } catch (IOException ignored) {
                        }
                    }
                }
            });
        }
    }

    public static class ElasticMockDispatcher extends Dispatcher {

        private final java.util.Map<String, String> tenantDocStore = new java.util.concurrent.ConcurrentHashMap<>();

        private static String stripQuery(String path) {
            int q = path.indexOf('?');
            return q >= 0 ? path.substring(0, q) : path;
        }

        private static String extractDocId(String cleanPath) {
            int idx = cleanPath.indexOf("/_doc/");
            if (idx < 0) {
                return null;
            }
            String after = cleanPath.substring(idx + 6);
            int slash = after.indexOf('/');
            return slash >= 0 ? after.substring(0, slash) : after;
        }

        private static String extractIndexName(String cleanPath) {
            String trimmed = cleanPath.startsWith("/") ? cleanPath.substring(1) : cleanPath;
            int slash = trimmed.indexOf('/');
            return slash >= 0 ? trimmed.substring(0, slash) : trimmed;
        }

        @Override
        public MockResponse dispatch(RecordedRequest request) {
            String path = request.getPath() != null ? request.getPath() : "";
            String cleanPath = stripQuery(path);
            String method = request.getMethod() != null ? request.getMethod() : "GET";
            String body = request.getBody() != null ? request.getBody().readUtf8() : "";

            // 0. HEAD 轻量探测 (existsById)
            if (method.equals("HEAD")) {
                return new MockResponse()
                        .setResponseCode(200)
                        .setHeader("Content-Type", "application/json")
                        .setHeader("X-Elastic-Product", "Elasticsearch");
            }

            // 1. 集群健康探针
            if (path.contains("/_cluster/health")) {
                return jsonResponse(200, """
                        {
                          "cluster_name": "mock-es-cluster",
                          "status": "green",
                          "timed_out": false,
                          "number_of_nodes": 3,
                          "number_of_data_nodes": 2,
                          "active_primary_shards": 5,
                          "active_shards": 10,
                          "relocating_shards": 0,
                          "initializing_shards": 0,
                          "unassigned_shards": 0,
                          "delayed_unassigned_shards": 0,
                          "number_of_pending_tasks": 0,
                          "number_of_in_flight_fetch": 0,
                          "task_max_waiting_in_queue_millis": 0,
                          "active_shards_percent_as_number": "100.0"
                        }
                        """);
            }

            // 1.5 Point-In-Time (PIT) 开启与释放（须在通用 DELETE 索引分支之前匹配）
            if (path.contains("/_pit")) {
                if ("DELETE".equals(method)) {
                    return jsonResponse(200, "{\"succeeded\":true,\"num_freed\":1}");
                }
                return jsonResponse(200, "{\"id\":\"mock-pit-id-001\"}");
            }

            // 2. 别名与索引结构管理
            if (path.contains("/_aliases") || path.contains("/_index_template") || path.contains("/_mapping") || (method.equals("DELETE") && !path.contains("/_doc"))) {
                return jsonResponse(200, "{\"acknowledged\":true}");
            }

            // 2.0 索引结构 DDL 创建 PUT
            if (method.equals("PUT") && !path.contains("/_doc") && (path.contains("product_") || path.contains("access_log_") || path.contains("order_"))) {
                String indexName = path.replaceAll("^/|\\?.*$", "");
                return jsonResponse(200, """
                        {
                          "acknowledged": true,
                          "shards_acknowledged": true,
                          "index": "%s"
                        }
                        """.formatted(indexName));
            }

            // 2.1 物理段刷新
            if (path.contains("/_refresh")) {
                return jsonResponse(200, """
                        {
                          "_shards": { "total": 1, "successful": 1, "failed": 0 }
                        }
                        """);
            }

            // 2.2 服务端数据重索引
            if (path.contains("/_reindex")) {
                return jsonResponse(200, """
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
                        """);
            }

            // 2.3 条件清理 deleteByQuery
            if (path.contains("/_delete_by_query")) {
                String requestedTenant = extractFilterTenantValue(body);
                boolean hasTenantLogEntries = tenantDocStore.keySet().stream().anyMatch(k -> k.startsWith("LOG_TENANT_"));
                if (requestedTenant != null && hasTenantLogEntries) {
                    long deletedCount = 0L;
                    java.util.Iterator<java.util.Map.Entry<String, String>> it = tenantDocStore.entrySet().iterator();
                    while (it.hasNext()) {
                        java.util.Map.Entry<String, String> entry = it.next();
                        if (entry.getKey().startsWith("LOG_TENANT_")
                                && requestedTenant.equals(extractStoredTenantValue(entry.getValue()))) {
                            it.remove();
                            deletedCount++;
                        }
                    }
                    return jsonResponse(200, """
                            {
                              "took": 5,
                              "timed_out": false,
                              "total": %d,
                              "deleted": %d,
                              "batches": 1,
                              "version_conflicts": 0,
                              "noops": 0,
                              "retries": { "bulk": 0, "search": 0 },
                              "throttled_millis": 0,
                              "requests_per_second": -1.0,
                              "throttled_until_millis": 0,
                              "failures": []
                            }
                            """.formatted(deletedCount, deletedCount));
                }
                return jsonResponse(200, """
                        {
                          "took": 5,
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
                        """);
            }

            // 2.4 轻量计数 count
            if (path.contains("/_count")) {
                if (body.contains("P_TENANT_") || body.contains("LOG_TENANT_")) {
                    String matchedDoc = null;
                    for (java.util.Map.Entry<String, String> entry : tenantDocStore.entrySet()) {
                        if (body.contains("\"" + entry.getKey() + "\"")) {
                            matchedDoc = entry.getValue();
                            break;
                        }
                    }
                    long countVal = 0L;
                    if (matchedDoc != null) {
                        String tenantMarker = extractStoredTenantValue(matchedDoc);
                        if (tenantMarker != null && body.contains("\"value\":\"" + tenantMarker + "\"")) {
                            countVal = 1L;
                        }
                    }
                    return jsonResponse(200, """
                            {
                              "count": %d,
                              "_shards": { "total": 1, "successful": 1, "skipped": 0, "failed": 0 }
                            }
                            """.formatted(countVal));
                }
                String requestedTenant = extractFilterTenantValue(body);
                if (requestedTenant != null
                        && (body.contains("/api/tenant-a/audit") || body.contains("/api/v1/tenant-a/orders"))) {
                    long countVal = 0L;
                    for (java.util.Map.Entry<String, String> entry : tenantDocStore.entrySet()) {
                        if (entry.getKey().startsWith("LOG_TENANT_")
                                && requestedTenant.equals(extractStoredTenantValue(entry.getValue()))
                                && ((body.contains("/api/tenant-a/audit") && entry.getValue().contains("/api/tenant-a/audit"))
                                || (body.contains("/api/v1/tenant-a/orders") && entry.getValue().contains("/api/v1/tenant-a/orders")))) {
                            countVal++;
                        }
                    }
                    return jsonResponse(200, """
                            {
                              "count": %d,
                              "_shards": { "total": 1, "successful": 1, "skipped": 0, "failed": 0 }
                            }
                            """.formatted(countVal));
                }
                return jsonResponse(200, """
                        {
                          "count": 42,
                          "_shards": { "total": 1, "successful": 1, "skipped": 0, "failed": 0 }
                        }
                        """);
            }

            // 2.5 批量获取 mget
            if (path.contains("/_mget")) {
                if (body.contains("P_TENANT_") || body.contains("P_BULK_TENANT_")
                        || body.contains("LOG_TENANT_") || body.contains("DLQ_BAD_DOC")) {
                    StringBuilder docsJson = new StringBuilder("[");
                    boolean first = true;
                    for (java.util.Map.Entry<String, String> entry : tenantDocStore.entrySet()) {
                        if (body.contains("\"" + entry.getKey() + "\"")) {
                            if (!first) {
                                docsJson.append(',');
                            }
                            docsJson.append("{\"_index\":\"product_index\",\"_id\":\"").append(entry.getKey())
                                    .append("\",\"found\":true,\"_source\":").append(entry.getValue()).append('}');
                            first = false;
                        }
                    }
                    docsJson.append(']');
                    return jsonResponse(200, "{\"docs\":" + docsJson + "}");
                }
                return jsonResponse(200, """
                        {
                          "docs": [
                            {
                              "_index": "product_index",
                              "_id": "P001",
                              "_version": 1,
                              "_seq_no": 1,
                              "_primary_term": 1,
                              "found": true,
                              "_source": {
                                "id": "P001",
                                "title": "iPhone 16 Pro Max",
                                "description": "苹果年度旗舰芯片A18 Pro",
                                "category": "手机",
                                "brand": "Apple",
                                "tags": ["数码", "旗舰"],
                                "price": 9999.00,
                                "stock": 100,
                                "status": "ON_SALE",
                                "tenantId": "tenant-001",
                                "createTime": "2026-10-26 10:00:00",
                                "skus": [
                                  { "skuId": "SKU-001", "color": "曜石黑", "price": 9999.00, "stock": 50 }
                                ]
                              }
                            }
                          ]
                        }
                        """);
            }

            // 3. 批量写入 Bulk 请求
            if (path.contains("/_bulk")) {
                if (body.contains("FAIL_TRIGGER")) {
                    return jsonResponse(200, """
                            {
                              "took": 5,
                              "errors": true,
                              "items": [
                                { "index": { "_index": "product_index", "_id": "P101", "status": 201 } },
                                { "index": { "_index": "product_index", "_id": "FAIL_TRIGGER", "status": 400, "error": { "type": "mapper_parsing_exception", "reason": "failed to parse price" } } }
                              ]
                            }
                            """);
                }
                if (body.contains("DLQ_BAD_DOC")) {
                    return jsonResponse(200, """
                            {
                              "took": 5,
                              "errors": true,
                              "items": [
                                { "index": { "_index": "product_index", "_id": "DLQ_BAD_DOC", "status": 400, "error": { "type": "mapper_parsing_exception", "reason": "failed to parse field [price]" } } }
                              ]
                            }
                            """);
                }
                syncTenantDocStoreFromBulk(body);
                return jsonResponse(200, """
                        {
                          "took": 2,
                          "errors": false,
                          "items": [
                            { "index": { "_index": "product_index", "_id": "P001", "status": 201, "result": "created" } },
                            { "index": { "_index": "product_index", "_id": "P002", "status": 201, "result": "created" } }
                          ]
                        }
                        """);
            }

            // 4. 局部更新 Update
            if (path.contains("/_update/")) {
                if (path.contains("if_seq_no=999")) {
                    return jsonResponse(409, """
                            {
                              "error": {
                                "root_cause": [
                                  { "type": "version_conflict_engine_exception", "reason": "[P001]: version conflict, required seqNo [999], primary term [1]" }
                                ],
                                "type": "version_conflict_engine_exception",
                                "reason": "[P001]: version conflict, required seqNo [999], primary term [1]"
                              },
                              "status": 409
                            }
                            """);
                }
                return jsonResponse(200, """
                        {
                          "_index": "product_index",
                          "_id": "P001",
                          "_version": 2,
                          "result": "updated",
                          "_seq_no": 2,
                          "_primary_term": 1,
                          "_shards": { "total": 1, "successful": 1, "failed": 0 }
                        }
                        """);
            }

            // 4.95 批量异构检索 _msearch
            if (path.contains("/_msearch")) {
                String[] lines = body.split("\\R");
                StringBuilder responsesJson = new StringBuilder("[");
                boolean firstItem = true;
                for (int i = 0; i + 1 < lines.length; i += 2) {
                    String itemBody = lines[i + 1];
                    if (!firstItem) {
                        responsesJson.append(',');
                    }
                    firstItem = false;
                    if (itemBody.contains("\"size\":0") || itemBody.contains("avg_price")) {
                        responsesJson.append("""
                                {
                                  "took": 2,
                                  "timed_out": false,
                                  "_shards": { "total": 1, "successful": 1, "skipped": 0, "failed": 0 },
                                  "hits": { "total": { "value": 1, "relation": "eq" }, "hits": [] },
                                  "aggregations": { "avg#avg_price": { "value": 9999.0 } }
                                }
                                """);
                    } else if (itemBody.contains("\"knn\"")) {
                        responsesJson.append("""
                                {
                                  "took": 2,
                                  "timed_out": false,
                                  "_shards": { "total": 1, "successful": 1, "skipped": 0, "failed": 0 },
                                  "hits": {
                                    "total": { "value": 2, "relation": "eq" },
                                    "hits": [
                                      {
                                        "_index": "product_index",
                                        "_id": "P002",
                                        "_score": 0.95,
                                        "_source": {
                                          "id": "P002",
                                          "title": "MacBook Pro M4",
                                          "description": "苹果旗舰芯片工作站",
                                          "category": "笔记本",
                                          "brand": "Apple",
                                          "price": 15999.00,
                                          "stock": 50,
                                          "status": "ON_SALE"
                                        }
                                      },
                                      {
                                        "_index": "product_index",
                                        "_id": "P001",
                                        "_score": 0.88,
                                        "_source": {
                                          "id": "P001",
                                          "title": "iPhone 16 Pro Max",
                                          "description": "苹果年度旗舰芯片A18 Pro",
                                          "category": "手机",
                                          "brand": "Apple",
                                          "price": 9999.00,
                                          "stock": 100,
                                          "status": "ON_SALE"
                                        }
                                      }
                                    ]
                                  }
                                }
                                """);
                    } else {
                        responsesJson.append("""
                                {
                                  "took": 2,
                                  "timed_out": false,
                                  "_shards": { "total": 1, "successful": 1, "skipped": 0, "failed": 0 },
                                  "hits": {
                                    "total": { "value": 1, "relation": "eq" },
                                    "hits": [
                                      {
                                        "_index": "product_index",
                                        "_id": "P001",
                                        "_score": 1.8,
                                        "_source": {
                                          "id": "P001",
                                          "title": "iPhone 16 Pro Max",
                                          "description": "苹果年度旗舰芯片A18 Pro",
                                          "category": "手机",
                                          "brand": "Apple",
                                          "price": 9999.00,
                                          "stock": 100,
                                          "status": "ON_SALE"
                                        },
                                        "highlight": {
                                          "title": ["<em>iPhone</em> 16 Pro Max"],
                                          "description": ["苹果年度<em>旗舰</em>芯片"]
                                        }
                                      }
                                    ]
                                  }
                                }
                                """);
                    }
                }
                responsesJson.append(']');
                return jsonResponse(200, "{\"took\":4,\"responses\":" + responsesJson + "}");
            }

            // 5. 检索 Search
            if (path.contains("/_search")) {
                if (body.contains("\"excludes\":[\"embedding\"]") || body.contains("\"excludes\":[\"embedding\"")) {
                    return jsonResponse(200, """
                            {
                              "took": 2,
                              "timed_out": false,
                              "_shards": { "total": 3, "successful": 3, "skipped": 0, "failed": 0 },
                              "hits": {
                                "total": { "value": 1, "relation": "eq" },
                                "max_score": 1.5,
                                "hits": [
                                  {
                                    "_index": "product_index",
                                    "_id": "P001",
                                    "_score": 1.5,
                                    "_routing": "Apple",
                                    "_source": {
                                      "id": "P001",
                                      "title": "iPhone 16 Pro Max",
                                      "description": "苹果年度旗舰芯片A18 Pro，支持Apple Intelligence",
                                      "category": "手机",
                                      "brand": "Apple",
                                      "price": 9999.00,
                                      "stock": 100,
                                      "status": "ON_SALE"
                                    }
                                  }
                                ]
                              }
                            }
                            """);
                }
                if (body.contains("daily_traffic")) {
                    return jsonResponse(200, """
                            {
                              "took": 3,
                              "timed_out": false,
                              "_shards": { "total": 1, "successful": 1, "skipped": 0, "failed": 0 },
                              "hits": { "total": { "value": 100, "relation": "eq" }, "hits": [] },
                              "aggregations": {
                                "date_histogram#daily_traffic": {
                                  "buckets": [
                                    { "key_as_string": "2026-11-01", "key": 1793491200000, "doc_count": 60 },
                                    { "key_as_string": "2026-11-02", "key": 1793577600000, "doc_count": 40 }
                                  ]
                                }
                              }
                            }
                            """);
                }
                if (body.contains("min_price") || body.contains("max_price")) {
                    return jsonResponse(200, """
                            {
                              "took": 2,
                              "timed_out": false,
                              "_shards": { "total": 1, "successful": 1, "skipped": 0, "failed": 0 },
                              "hits": { "total": { "value": 10, "relation": "eq" }, "hits": [] },
                              "aggregations": {
                                "min#min_price": {
                                  "value": 1999.0
                                },
                                "max#max_price": {
                                  "value": 9999.0
                                },
                                "avg#avg_price": {
                                  "value": 5999.0
                                }
                              }
                            }
                            """);
                }
                if (body.contains("brand_agg")) {
                    return jsonResponse(200, """
                            {
                              "took": 3,
                              "timed_out": false,
                              "_shards": { "total": 3, "successful": 3, "skipped": 0, "failed": 0 },
                              "hits": {
                                "total": { "value": 1, "relation": "eq" },
                                "max_score": 1.5,
                                "hits": [
                                  {
                                    "_index": "product_index",
                                    "_id": "P001",
                                    "_score": 1.5,
                                    "_routing": "Apple",
                                    "_source": {
                                      "id": "P001",
                                      "title": "iPhone 16 Pro Max",
                                      "description": "苹果年度旗舰芯片A18 Pro，支持Apple Intelligence",
                                      "category": "手机",
                                      "brand": "Apple",
                                      "tags": ["数码", "旗舰"],
                                      "price": 9999.00,
                                      "stock": 100,
                                      "status": "ON_SALE",
                                      "location": { "lat": 31.2304, "lon": 121.4737 },
                                      "embedding": [0.12, 0.34, 0.56, 0.78],
                                      "tenantId": "tenant-001",
                                      "createTime": "2026-10-26 10:00:00"
                                    },
                                    "highlight": {
                                      "title": ["<em>iPhone</em> 16 Pro Max"],
                                      "description": ["苹果年度<em>旗舰</em>芯片"]
                                    },
                                    "sort": [9999.0, "P001"]
                                  }
                                ]
                              },
                              "aggregations": {
                                "sterms#brand_agg": {
                                  "doc_count_error_upper_bound": 0,
                                  "sum_other_doc_count": 0,
                                  "buckets": [
                                    { "key": "Apple", "doc_count": 1 }
                                  ]
                                },
                                "avg#avg_price": {
                                  "value": 9999.00
                                }
                              }
                            }
                            """);
                }
                return jsonResponse(200, """
                        {
                          "took": 3,
                          "timed_out": false,
                          "_shards": { "total": 3, "successful": 3, "skipped": 0, "failed": 0 },
                          "hits": {
                            "total": { "value": 1, "relation": "eq" },
                            "max_score": 1.5,
                            "hits": [
                              {
                                "_index": "product_index",
                                "_id": "P001",
                                "_score": 1.5,
                                "_routing": "Apple",
                                "_source": {
                                  "id": "P001",
                                  "title": "iPhone 16 Pro Max",
                                  "description": "苹果年度旗舰芯片A18 Pro，支持Apple Intelligence",
                                  "category": "手机",
                                  "brand": "Apple",
                                  "tags": ["数码", "旗舰"],
                                  "price": 9999.00,
                                  "stock": 100,
                                  "status": "ON_SALE",
                                  "location": { "lat": 31.2304, "lon": 121.4737 },
                                  "embedding": [0.12, 0.34, 0.56, 0.78],
                                  "tenantId": "tenant-001",
                                  "createTime": "2026-10-26 10:00:00",
                                  "skus": [
                                    { "skuId": "SKU-001", "color": "曜石黑", "price": 9999.00, "stock": 50 }
                                  ]
                                },
                                "highlight": {
                                  "title": ["<em>iPhone</em> 16 Pro Max"],
                                  "description": ["苹果年度<em>旗舰</em>芯片"]
                                },
                                "sort": [9999.0, "P001"]
                              }
                            ]
                          }
                        }
                        """);
            }

            // 6. 单文档获取 Get
            if (method.equals("GET") && (path.contains("/_doc/") || path.contains("/product_index/"))) {
                String docId = extractDocId(cleanPath);
                String indexName = extractIndexName(cleanPath);
                if (isTrackedTenantDocId(docId)) {
                    String storedJson = tenantDocStore.get(docId);
                    if (storedJson == null) {
                        return jsonResponse(404, """
                                {
                                  "_index": "%s",
                                  "_id": "%s",
                                  "found": false
                                }
                                """.formatted(indexName, docId));
                    }
                    return jsonResponse(200, """
                            {
                              "_index": "%s",
                              "_id": "%s",
                              "_version": 1,
                              "_seq_no": 1,
                              "_primary_term": 1,
                              "found": true,
                              "_source": %s
                            }
                            """.formatted(indexName, docId, storedJson));
                }
                return jsonResponse(200, """
                        {
                          "_index": "product_index",
                          "_id": "P001",
                          "_version": 1,
                          "_seq_no": 1,
                          "_primary_term": 1,
                          "_routing": "Apple",
                          "found": true,
                          "_source": {
                            "id": "P001",
                            "title": "iPhone 16 Pro Max",
                            "description": "苹果年度旗舰芯片A18 Pro",
                            "category": "手机",
                            "brand": "Apple",
                            "tags": ["数码", "旗舰"],
                            "price": 9999.00,
                            "stock": 100,
                            "status": "ON_SALE",
                            "tenantId": "tenant-001",
                            "createTime": "2026-10-26 10:00:00",
                            "skus": [
                              { "skuId": "SKU-001", "color": "曜石黑", "price": 9999.00, "stock": 50 }
                            ]
                          }
                        }
                        """);
            }

            // 7. 单文档写入 Index (PUT/POST)
            if ((method.equals("PUT") || method.equals("POST")) && (path.contains("/_doc/") || path.contains("/access_log_") || path.contains("/product_index"))) {
                String docId = extractDocId(cleanPath);
                String indexName = extractIndexName(cleanPath);
                if (isTrackedTenantDocId(docId) && !body.isBlank()) {
                    tenantDocStore.put(docId, body);
                }
                String resolvedId = docId != null ? docId : "P001";
                String resolvedIndex = !indexName.isBlank() ? indexName : "product_index";
                return jsonResponse(201, """
                        {
                          "_index": "%s",
                          "_id": "%s",
                          "_version": 1,
                          "result": "created",
                          "_seq_no": 1,
                          "_primary_term": 1,
                          "_shards": { "total": 1, "successful": 1, "failed": 0 }
                        }
                        """.formatted(resolvedIndex, resolvedId));
            }

            // 8. 单文档删除 Delete
            if (method.equals("DELETE") && (path.contains("/_doc/") || path.contains("/product_index"))) {
                String docId = extractDocId(cleanPath);
                String indexName = extractIndexName(cleanPath);
                if (isTrackedTenantDocId(docId)) {
                    tenantDocStore.remove(docId);
                }
                String resolvedId = docId != null ? docId : "P001";
                String resolvedIndex = !indexName.isBlank() ? indexName : "product_index";
                return jsonResponse(200, """
                        {
                          "_index": "%s",
                          "_id": "%s",
                          "_version": 2,
                          "result": "deleted",
                          "_seq_no": 2,
                          "_primary_term": 1,
                          "_shards": { "total": 1, "successful": 1, "failed": 0 }
                        }
                        """.formatted(resolvedIndex, resolvedId));
            }

            // 默认兜底响应
            return jsonResponse(200, "{\"acknowledged\":true}");
        }

        private boolean isTrackedTenantDocId(String docId) {
            return docId != null
                    && (docId.startsWith("P_TENANT_") || docId.startsWith("P_BULK_TENANT_") || docId.startsWith("LOG_TENANT_"));
        }

        private void syncTenantDocStoreFromBulk(String ndjsonBody) {
            if (ndjsonBody == null || ndjsonBody.isBlank()) {
                return;
            }
            String[] lines = ndjsonBody.split("\\R");
            for (int i = 0; i < lines.length; i++) {
                String meta = lines[i];
                if (meta == null || meta.isBlank()) {
                    continue;
                }
                String docId = extractJsonFieldValue(meta, "\"_id\":\"");
                if (meta.contains("{\"delete\":")) {
                    if (isTrackedTenantDocId(docId)) {
                        tenantDocStore.remove(docId);
                    }
                } else if (meta.contains("{\"index\":") || meta.contains("{\"update\":")) {
                    if (i + 1 < lines.length && isTrackedTenantDocId(docId)) {
                        tenantDocStore.put(docId, lines[i + 1]);
                    }
                    i++;
                }
            }
        }

        private String extractJsonFieldValue(String json, String prefix) {
            int idx = json.indexOf(prefix);
            if (idx < 0) {
                return null;
            }
            int start = idx + prefix.length();
            int end = json.indexOf('"', start);
            return end > start ? json.substring(start, end) : null;
        }

        private String extractStoredTenantValue(String json) {
            if (json == null || json.isBlank()) {
                return null;
            }
            for (String field : new String[]{"\"tenantId\":\"", "\"tenant_id\":\""}) {
                int idx = json.indexOf(field);
                if (idx >= 0) {
                    int start = idx + field.length();
                    int end = json.indexOf('"', start);
                    if (end > start) {
                        return json.substring(start, end);
                    }
                }
            }
            return null;
        }

        private String extractFilterTenantValue(String queryJson) {
            if (queryJson == null || queryJson.isBlank()) {
                return null;
            }
            for (String field : new String[]{"\"tenantId\":", "\"tenant_id\":"}) {
                int fieldIdx = queryJson.indexOf(field);
                if (fieldIdx >= 0) {
                    int valueIdx = queryJson.indexOf("\"value\":\"", fieldIdx + field.length());
                    if (valueIdx >= 0) {
                        int start = valueIdx + "\"value\":\"".length();
                        int end = queryJson.indexOf('"', start);
                        if (end > start) {
                            return queryJson.substring(start, end);
                        }
                    }
                }
            }
            return null;
        }

        private MockResponse jsonResponse(int status, String json) {
            return new MockResponse()
                    .setResponseCode(status)
                    .setHeader("Content-Type", "application/json")
                    .setHeader("X-Elastic-Product", "Elasticsearch")
                    .setBody(json);
        }
    }
}
