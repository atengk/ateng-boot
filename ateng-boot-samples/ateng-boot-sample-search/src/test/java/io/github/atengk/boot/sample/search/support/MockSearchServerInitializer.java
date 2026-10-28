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

        @Override
        public MockResponse dispatch(RecordedRequest request) {
            String path = request.getPath() != null ? request.getPath() : "";
            String method = request.getMethod() != null ? request.getMethod() : "GET";
            String body = request.getBody() != null ? request.getBody().readUtf8() : "";

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

            // 2. 别名与索引结构管理
            if (path.contains("/_aliases") || (!path.contains("/_doc") && (path.contains("product_") || path.contains("access_log_") || path.contains("/order_")) && (method.equals("PUT") || method.equals("DELETE")))) {
                return jsonResponse(200, "{\"acknowledged\":true}");
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

            // 5. 检索 Search
            if (path.contains("/_search")) {
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
                                  "createTime": "2026-10-26 10:00:00"
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
                            "createTime": "2026-10-26 10:00:00"
                          }
                        }
                        """);
            }

            // 7. 单文档写入 Index (PUT/POST)
            if ((method.equals("PUT") || method.equals("POST")) && (path.contains("/_doc/") || path.contains("/access_log_") || path.contains("/product_index"))) {
                return jsonResponse(201, """
                        {
                          "_index": "product_index",
                          "_id": "P001",
                          "_version": 1,
                          "result": "created",
                          "_seq_no": 1,
                          "_primary_term": 1,
                          "_shards": { "total": 1, "successful": 1, "failed": 0 }
                        }
                        """);
            }

            // 8. 单文档删除 Delete
            if (method.equals("DELETE") && (path.contains("/_doc/") || path.contains("/product_index"))) {
                return jsonResponse(200, """
                        {
                          "_index": "product_index",
                          "_id": "P001",
                          "_version": 2,
                          "result": "deleted",
                          "_seq_no": 2,
                          "_primary_term": 1,
                          "_shards": { "total": 1, "successful": 1, "failed": 0 }
                        }
                        """);
            }

            // 默认兜底响应
            return jsonResponse(200, "{\"acknowledged\":true}");
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
