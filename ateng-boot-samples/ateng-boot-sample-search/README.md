# ateng-boot-sample-search

本模块是 **[ateng-boot-search](../../ateng-boot-search/README.md)** 的 1:1 全场景验证与开发沙箱工程，内置 `ateng-boot-web` 4 大领域控制器、生产级领域场景服务（`ProductCatalogSearchScenario`、`LogAnalyticsAggScenario`、`BulkIngestionScenario`、`ZeroDowntimeReindexScenario`）及自定义死信监听器（`SampleBulkDeadLetterRecorder`），支持无集群离线 Mock 验证与真实 Elasticsearch 8.x 集群联调双模式。

---

## 1. 快速启动与服务端口

- **默认服务端口**：`8080`（默认开启 Actuator `/actuator/health` 与 `/actuator/metrics`）。
- **默认离线启动**（需搭配内置测试 Mock 桩或在已有本地 ES 环境直接连通 `127.0.0.1:9200`）：

```bash
mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-search
```

### 控制器与领域能力分布

| Controller 域 | 路由前缀 | 核心 Scenario 编排 | 覆盖能力与验证端点清单 |
| :--- | :--- | :--- | :--- |
| **商品多维检索** (`ProductSearchController`) | `/sample/search/product` | [ProductCatalogSearchScenario](./src/main/java/io/github/atengk/boot/sample/search/scenario/ProductCatalogSearchScenario.java) | 文档 `save`/`exists`/`get`/`batch-get`/`delete`/局改/OCC、`multiMatch` 高亮、`@HighlightField` VO 投影（`/search-vo`）、`_source` 裁剪（`/source-trimmed`）、前缀联想（`/suggest`）、拼写纠错（`/spell-suggest`）、`Nested` SKU、`Collapse` 去重、Geo 距离、kNN 向量、混合检索（`/hybrid`，默认 `CLIENT_RRF`）、统计与分位数大盘（`/metrics`、`/dashboard`）、浅分页（`/page`）、`Search-After`（`/search-after`）、`PIT` 快照（`/pit-pages`）、`_msearch`（`/msearch`） |
| **日志时序分析** (`LogAnalyticsController`) | `/sample/search/log` | [LogAnalyticsAggScenario](./src/main/java/io/github/atengk/boot/sample/search/scenario/LogAnalyticsAggScenario.java) | SpEL 按天动态分表索引（`access_log_yyyyMMdd`）、按路径计数（`/count`）、`DateHistogram` 走势（`/trend`）、Cardinality 去重 IP 与按日嵌套子聚合大盘（`/dashboard`）、参数化 `updateByQuery` + `WAIT_UNTIL`（`/mark-ip`）、`deleteByQuery` 安全网清理（`/purge`） |
| **海量批量写入** (`BulkIngestionController`) | `/sample/search/bulk` | [BulkIngestionScenario](./src/main/java/io/github/atengk/boot/sample/search/scenario/BulkIngestionScenario.java) | 严格批量（`/strict`）、宽容局部失败隔离（`/lenient`）、`SearchBulkProcessor` 流式投递（`/stream-add`）、原始映射载荷局部死信触发（`/stream-raw`）、带分片路由删除（`/stream-delete`）、强制排空（`/stream-flush`）、`BulkDeadLetterHandler` 死信统计观测与重置（`/dead-letter/stats`） |
| **零停机 DDL 运维** (`IndexLifecycleController`) | `/sample/search/index` | [ZeroDowntimeReindexScenario](./src/main/java/io/github/atengk/boot/sample/search/scenario/ZeroDowntimeReindexScenario.java) | 建索引（`/create`）、`IndexTemplate` 与 `ComponentTemplate` 增删查（`/template/*`、`/component-template/*`）、`Settings` 查询与热更新（`/settings`）、同步 `reindex`（`/reindex`）、异步 `reindexAsync` + `Task` 查询与取消（`/reindex-async`、`/task`、`/task/cancel`）、`/refresh`、精细别名增删查（`/alias/*`）与原子切换（`/swap-alias`） |

> [!TIP]
> 样例工程内置 [SampleTenantHeaderFilter](./src/main/java/io/github/atengk/boot/sample/search/filter/SampleTenantHeaderFilter.java)，自动从 HTTP 请求头 `X-Tenant-Id` 提取当前租户并绑定 `TenantContextHolder`，便于直接通过 `curl` 验证 `multi-tenancy.isolation-mode: STRICT` 多租户隔离闭环。

---

## 2. 双模环境准备与 `local` 集群切换

- **模式 A（离线自洽测试）**：`@TestConfiguration`（标注 `@Profile("!local")`）自动挂载本地 Mock HTTP Server 桩，无需外部 ES / Docker 即可全绿运行全部集成与 MockMvc 端到端测试。
- **模式 B（真实 Elasticsearch 8.x / OpenSearch 集群）**：基于 [application-local.example.yml](./src/main/resources/application-local.example.yml) 复制生成 `src/main/resources/application-local.yml`（已被 `.gitignore` 忽略，严禁提交真实凭据），并按实际集群开启认证/SSL 可选扩展配置：

```yaml
spring:
  config:
    activate:
      on-profile: local

ateng:
  boot:
    search:
      enabled: true
      engine: elasticsearch # 引擎类型：elasticsearch 或 opensearch
      uris:
        - https://YOUR_SEARCH_HOST:YOUR_SEARCH_PORT
      # 若集群开启 Basic Auth / HTTPS，按需补充以下可选字段（严禁提交明文凭据）
      username: YOUR_SEARCH_USERNAME
      password: ${SEARCH_PASSWORD:YOUR_SEARCH_PASSWORD}
      ssl:
        enabled: true
        insecure-skip-verify: false
      connect-timeout: 2s
      socket-timeout: 5s
```

以 `local` Profile 启动 Web 服务：

```bash
mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-search -Dspring-boot.run.profiles=local
```

---

## 3. 核心 Controller 接口与一键体验

应用启动后，可通过以下 cURL 命令或 HTTP 客户端直接发起调用体验（多租户端点默认通过请求头 `X-Tenant-Id` 透传租户上下文）：

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **写入商品文档 (含路由/Geo/向量/Nested)** | `ProductSearchController` | `POST /sample/search/product/save` | `curl -X POST http://localhost:8080/sample/search/product/save -H "Content-Type: application/json" -H "X-Tenant-Id: tenant-a" -d '{"id":"p-1001","title":"Ateng Pro 工业级降噪无线耳机","description":"主动混合降噪，超长续航旗舰蓝牙耳机","category":"electronics","brand":"Ateng","price":1299.00,"stock":80,"status":"ON_SALE","location":{"lat":31.2304,"lon":121.4737},"embedding":[0.91,0.18,0.05,0.02],"skus":[{"skuId":"sku-black","color":"Black","price":1299.00,"stock":50}]}'` |
| **轻量存在探测 (HEAD / STRICT 过滤)** | `ProductSearchController` | `GET /sample/search/product/exists` | `curl "http://localhost:8080/sample/search/product/exists?id=p-1001&brand=Ateng" -H "X-Tenant-Id: tenant-a"` |
| **批量主键拉取 (_mget)** | `ProductSearchController` | `POST /sample/search/product/batch-get` | `curl -X POST http://localhost:8080/sample/search/product/batch-get -H "Content-Type: application/json" -H "X-Tenant-Id: tenant-a" -d '{"ids":["p-1001"],"brand":"Ateng"}'` |
| **@HighlightField VO 安全投影检索** | `ProductSearchController` | `POST /sample/search/product/search-vo` | `curl -X POST http://localhost:8080/sample/search/product/search-vo -H "Content-Type: application/json" -H "X-Tenant-Id: tenant-a" -d '{"keyword":"降噪耳机"}'` |
| **_source 向量裁剪检索** | `ProductSearchController` | `GET /sample/search/product/source-trimmed` | `curl "http://localhost:8080/sample/search/product/source-trimmed?keyword=耳机" -H "X-Tenant-Id: tenant-a"` |
| **AI 混合检索融合 (CLIENT_RRF)** | `ProductSearchController` | `POST /sample/search/product/hybrid` | `curl -X POST http://localhost:8080/sample/search/product/hybrid -H "Content-Type: application/json" -H "X-Tenant-Id: tenant-a" -d '{"keyword":"降噪耳机","vector":[0.90,0.20,0.04,0.01],"k":5,"mode":"CLIENT_RRF"}'` |
| **_msearch 批量异构检索** | `ProductSearchController` | `POST /sample/search/product/msearch` | `curl -X POST http://localhost:8080/sample/search/product/msearch -H "Content-Type: application/json" -H "X-Tenant-Id: tenant-a" -d '{"keyword":"耳机","category":"electronics"}'` |
| **PIT 一致性快照深翻页遍历** | `ProductSearchController` | `GET /sample/search/product/pit-pages` | `curl "http://localhost:8080/sample/search/product/pit-pages?size=2" -H "X-Tenant-Id: tenant-a"` |
| **统计与分位数大盘 (P50/P95/P99)** | `ProductSearchController` | `GET /sample/search/product/dashboard` | `curl http://localhost:8080/sample/search/product/dashboard -H "X-Tenant-Id: tenant-a"` |
| **拼写纠错建议 (Term/Phrase Suggester)** | `ProductSearchController` | `GET /sample/search/product/spell-suggest` | `curl "http://localhost:8080/sample/search/product/spell-suggest?text=Ateng&size=5" -H "X-Tenant-Id: tenant-a"` |
| **OCC 乐观并发安全调价** | `ProductSearchController` | `POST /sample/search/product/update-price-occ` | `curl -X POST http://localhost:8080/sample/search/product/update-price-occ -H "Content-Type: application/json" -H "X-Tenant-Id: tenant-a" -d '{"id":"p-1001","brand":"Ateng","price":1199.00,"seqNo":0,"primaryTerm":1}'` |
| **写入动态分表访问日志 (SpEL)** | `LogAnalyticsController` | `POST /sample/search/log/record` | `curl -X POST http://localhost:8080/sample/search/log/record -H "Content-Type: application/json" -H "X-Tenant-Id: tenant-a" -d '{"id":"log-001","path":"/api/v1/orders","clientIp":"203.0.113.20"}'` |
| **按路径统计日志数** | `LogAnalyticsController` | `GET /sample/search/log/count` | `curl "http://localhost:8080/sample/search/log/count?path=/api/v1/orders" -H "X-Tenant-Id: tenant-a"` |
| **按天直方图流量趋势** | `LogAnalyticsController` | `GET /sample/search/log/trend` | `curl "http://localhost:8080/sample/search/log/trend?index=access_log_*&interval=1d" -H "X-Tenant-Id: tenant-a"` |
| **Cardinality 去重 IP 与子聚合大盘** | `LogAnalyticsController` | `GET /sample/search/log/dashboard` | `curl "http://localhost:8080/sample/search/log/dashboard?index=access_log_*" -H "X-Tenant-Id: tenant-a"` |
| **参数化 Painless updateByQuery** | `LogAnalyticsController` | `POST /sample/search/log/mark-ip` | `curl -X POST http://localhost:8080/sample/search/log/mark-ip -H "Content-Type: application/json" -H "X-Tenant-Id: tenant-a" -d '{"path":"/api/v1/orders","clientIp":"198.51.100.88"}'` |
| **严格同步批量入库** | `BulkIngestionController` | `POST /sample/search/bulk/strict` | `curl -X POST http://localhost:8080/sample/search/bulk/strict -H "Content-Type: application/json" -H "X-Tenant-Id: tenant-a" -d '{"items":[{"id":"b-1","title":"流式机械键盘","brand":"Ateng","price":499.00,"stock":20}]}'` |
| **流式缓冲非阻塞投递** | `BulkIngestionController` | `POST /sample/search/bulk/stream-add` | `curl -X POST http://localhost:8080/sample/search/bulk/stream-add -H "Content-Type: application/json" -H "X-Tenant-Id: tenant-a" -d '{"id":"b-2","title":"Type-C 扩展坞","brand":"Ateng","price":199.00,"stock":60}'` |
| **原始载荷局部死信触发演示** | `BulkIngestionController` | `POST /sample/search/bulk/stream-raw` | `curl -X POST http://localhost:8080/sample/search/bulk/stream-raw -H "Content-Type: application/json" -H "X-Tenant-Id: tenant-a" -d '{"id":"dlq-bad-1","routing":"Ateng","fields":{"id":"dlq-bad-1","price":"invalid-number"}}'` |
| **流式带分片路由删除** | `BulkIngestionController` | `POST /sample/search/bulk/stream-delete` | `curl -X POST http://localhost:8080/sample/search/bulk/stream-delete -H "Content-Type: application/json" -H "X-Tenant-Id: tenant-a" -d '{"id":"b-2","routing":"Ateng"}'` |
| **强制排空批量缓冲队列** | `BulkIngestionController` | `POST /sample/search/bulk/stream-flush` | `curl -X POST http://localhost:8080/sample/search/bulk/stream-flush` |
| **查询 BulkDeadLetterHandler 死信统计** | `BulkIngestionController` | `GET /sample/search/bulk/dead-letter/stats` | `curl http://localhost:8080/sample/search/bulk/dead-letter/stats` |
| **重置 BulkDeadLetterHandler 死信统计** | `BulkIngestionController` | `DELETE /sample/search/bulk/dead-letter/stats` | `curl -X DELETE http://localhost:8080/sample/search/bulk/dead-letter/stats` |
| **应用组件模板 (ComponentTemplate)** | `IndexLifecycleController` | `POST /sample/search/index/component-template/apply` | `curl -X POST http://localhost:8080/sample/search/index/component-template/apply -H "Content-Type: application/json" -d '{"templateName":"sample_base_settings","templateJson":"{\"template\":{\"settings\":{\"number_of_replicas\":0}}}"}'` |
| **探测与删除组件模板** | `IndexLifecycleController` | `GET /sample/search/index/component-template/exists` | `curl "http://localhost:8080/sample/search/index/component-template/exists?templateName=sample_base_settings"` |
| **读取与热更新索引 Settings** | `IndexLifecycleController` | `PUT /sample/search/index/settings` | `curl -X PUT http://localhost:8080/sample/search/index/settings -H "Content-Type: application/json" -d '{"indexName":"product_index","settingsJson":"{\"index\":{\"refresh_interval\":\"5s\"}}"}'` |
| **提交异步 Reindex 任务** | `IndexLifecycleController` | `POST /sample/search/index/reindex-async` | `curl -X POST http://localhost:8080/sample/search/index/reindex-async -H "Content-Type: application/json" -d '{"sourceIndex":"product_index","targetIndex":"product_index_v2"}'` |
| **查询与取消异步 Task** | `IndexLifecycleController` | `GET /sample/search/index/task` | `curl "http://localhost:8080/sample/search/index/task?taskId=NODE_ID:TASK_ID"` |
| **挂载精细别名 (isWriteIndex)** | `IndexLifecycleController` | `POST /sample/search/index/alias/add` | `curl -X POST http://localhost:8080/sample/search/index/alias/add -H "Content-Type: application/json" -d '{"indexName":"product_index_v2","aliasName":"product_active","writeIndex":true}'` |
| **原子切换索引别名 (swapAlias)** | `IndexLifecycleController` | `POST /sample/search/index/swap-alias` | `curl -X POST http://localhost:8080/sample/search/index/swap-alias -H "Content-Type: application/json" -d '{"aliasName":"product_active","oldIndex":"product_index","newIndex":"product_index_v2"}'` |

---

## 4. 测试验证

```bash
# 1. 默认全离线双模块完整测试门禁（核心 Starter + Sample Web 控制器与场景全矩阵）
mvn test -pl ateng-boot-search,ateng-boot-samples/ateng-boot-sample-search -am -T 1C -nsu -B

# 2. 激活 local Profile 对真实 Elasticsearch 8.x 集群执行端到端集成测试
# 测试套件 @AfterAll 会确定性清理全部测试创建的物理索引、模板、组件模板与别名
mvn test -pl ateng-boot-samples/ateng-boot-sample-search -am "-Dspring.profiles.active=local" -T 1C -nsu -B
```

- 核心设计契约、配置全表与 SPI 指南请查阅 **[ateng-boot-search 核心文档](../../ateng-boot-search/README.md)**。
- 领域统一词汇与架构演进脉络请查阅 **[ateng-boot-search/CONTEXT.md](../../ateng-boot-search/CONTEXT.md)** 与 **[ADR-0106](../../docs/adr/0106-search-sample-multi-controller-and-documentation-specification-architecture.md)**。
