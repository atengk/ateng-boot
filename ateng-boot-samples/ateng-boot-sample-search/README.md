# ateng-boot-sample-search 全文检索与流批分析体验工程

> 专注于演示与自验 `ateng-boot-search` 核心特性在电商商品检索、时序日志审计、海量流水批量摄取与索引零停机运维四大企业级业务场景下的开箱使用。

---

## 1. 快速启动与服务端口

- **默认 HTTP 端口**：`8080` (可在 `application.yml` 中修改)
- **启动方式**：
  ```bash
  # 方式一：IDE 中运行 SampleSearchApplication.java 的 main 方法
  # 方式二：命令行启动
  mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-search
  ```

---

## 2. 运行环境与双模切换

- **默认/CI 模式**：内置基于 OkHttp MockWebServer 的 `MockSearchServerInitializer` 内存自洽服务与配置，无需依赖外部物理 Elasticsearch 或 Docker 容器即可直接启动与跑通端到端测试；
- **Local 真实模式**：若需直连真实物理 Elasticsearch / OpenSearch 集群，复制 `src/main/resources/application-local.example.yml` 为 `application-local.yml` 并填入真实集群连接节点与账号密码（已被 `.gitignore` 纳管，CI 零风险），激活 `local` Profile 启动即可。

---

## 3. 核心 Controller 接口与一键体验

本工程遵循多 Controller 领域拆分规约，提供 4 大专职 `@RestController`，所有出参统一显式返回强类型 `Result<T>`，入参严格遵循 Zero-Entity-Leakage 传输隔离契约：

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **保存/更新商品文档** | `ProductSearchController` | `POST /sample/search/product/save` | `curl -s -X POST "http://localhost:8080/sample/search/product/save" -H "Content-Type: application/json" -d '{"id":"P001","title":"iPhone 16 Pro Max 手机","brand":"Apple","category":"手机","price":9999.00,"stock":100}'` |
| **探测商品是否存在** | `ProductSearchController` | `GET /sample/search/product/exists` | `curl -s -X GET "http://localhost:8080/sample/search/product/exists?id=P001&brand=Apple"` |
| **主键查询商品详情** | `ProductSearchController` | `GET /sample/search/product/get` | `curl -s -X GET "http://localhost:8080/sample/search/product/get?id=P001&brand=Apple"` |
| **局部更新商品库存** | `ProductSearchController` | `POST /sample/search/product/update-stock` | `curl -s -X POST "http://localhost:8080/sample/search/product/update-stock" -H "Content-Type: application/json" -d '{"id":"P001","brand":"Apple","stock":88}'` |
| **OCC 乐观并发控制调价** | `ProductSearchController` | `POST /sample/search/product/update-price-occ` | `curl -s -X POST "http://localhost:8080/sample/search/product/update-price-occ" -H "Content-Type: application/json" -d '{"id":"P001","brand":"Apple","price":8888.00,"seqNo":1,"primaryTerm":1}'` |
| **多字段高亮全文检索** | `ProductSearchController` | `POST /sample/search/product/search` | `curl -s -X POST "http://localhost:8080/sample/search/product/search" -H "Content-Type: application/json" -d '{"keyword":"手机"}'` |
| **前缀即时补全联想** | `ProductSearchController` | `GET /sample/search/product/suggest` | `curl -s -X GET "http://localhost:8080/sample/search/product/suggest?prefix=iPh"` |
| **Nested SKU 精准匹配** | `ProductSearchController` | `POST /sample/search/product/nested-sku` | `curl -s -X POST "http://localhost:8080/sample/search/product/nested-sku" -H "Content-Type: application/json" -d '{"color":"曜石黑","minPrice":5000.00}'` |
| **地理空间距离周边检索** | `ProductSearchController` | `POST /sample/search/product/geo` | `curl -s -X POST "http://localhost:8080/sample/search/product/geo" -H "Content-Type: application/json" -d '{"lat":31.2304,"lon":121.4737,"distance":"5km"}'` |
| **Search-After 深度游标分页** | `ProductSearchController` | `POST /sample/search/product/search-after` | `curl -s -X POST "http://localhost:8080/sample/search/product/search-after" -H "Content-Type: application/json" -d '{"sortValues":["P001"],"size":10}'` |
| **时序访问量轻量统计** | `LogAnalyticsController` | `GET /sample/search/log/count` | `curl -s -X GET "http://localhost:8080/sample/search/log/count?path=/api/v1/goods/detail"` |
| **时序直方图走势聚合** | `LogAnalyticsController` | `GET /sample/search/log/trend` | `curl -s -X GET "http://localhost:8080/sample/search/log/trend?index=access_log_*&interval=1d"` |
| **DeleteByQuery 租户历史清理** | `LogAnalyticsController` | `POST /sample/search/log/purge` | `curl -s -X POST "http://localhost:8080/sample/search/log/purge" -H "Content-Type: application/json" -H "X-Tenant-Id: tenant_corp_a" -d '{"cutoffTime":"2026-10-01 00:00:00"}'` |
| **严格模式批量写入** | `BulkIngestionController` | `POST /sample/search/bulk/strict` | `curl -s -X POST "http://localhost:8080/sample/search/bulk/strict" -H "Content-Type: application/json" -d '{"items":[{"id":"P101","title":"商品A","brand":"Apple","price":100},{"id":"P102","title":"商品B","brand":"Apple","price":200}]}'` |
| **异步流式批量缓冲投递** | `BulkIngestionController` | `POST /sample/search/bulk/stream-add` | `curl -s -X POST "http://localhost:8080/sample/search/bulk/stream-add" -H "Content-Type: application/json" -d '{"id":"P103","title":"流式商品","brand":"Apple","price":300}'` |
| **索引结构 DDL 创建** | `IndexLifecycleController` | `POST /sample/search/index/create` | `curl -s -X POST "http://localhost:8080/sample/search/index/create" -H "Content-Type: application/json" -d '{"indexName":"order_idx","mappingJson":"{\"mappings\":{\"properties\":{\"orderNo\":{\"type\":\"keyword\"}}}}"}'` |
| **生产原子别名热切换** | `IndexLifecycleController` | `POST /sample/search/index/alias/swap` | `curl -s -X POST "http://localhost:8080/sample/search/index/alias/swap" -H "Content-Type: application/json" -d '{"aliasName":"product_alias","oldIndex":"product_v1","newIndex":"product_v2"}'` |

---

## 4. 全真自动化回归命令

```bash
mvn test -pl ateng-boot-search,ateng-boot-samples/ateng-boot-sample-search -am -T 1C -nsu -B
```

> 详细原理机制、配置清单与扩展定制，请参阅核心 Starter 文档：[ateng-boot-search README](../../ateng-boot-search/README.md)。
