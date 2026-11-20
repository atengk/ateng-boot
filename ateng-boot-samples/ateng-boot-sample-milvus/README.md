# ateng-boot-sample-milvus 向量数据库与混合检索体验工程

> 专注于演示与自验 `ateng-boot-milvus` 核心特性在企业级知识库、时序物理分区归档、多模态稀疏与稠密混合检索、SaaS 多租户隔离及动态逻辑库切换等真实场景下的开箱使用。

---

## 1. 快速启动与服务端口

- **默认 HTTP 端口**：`8080` (可在 [application.yml](./src/main/resources/application.yml) 中修改)
- **启动方式**：
  ```bash
  # 方式一：IDE 中运行 SampleMilvusApplication.java 的 main 方法
  # 方式二：命令行启动（若本地已启动 Milvus 19530 或配置了 application-local.yml）
  mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-milvus
  ```

---

## 2. 运行环境与双模切换

- **默认/CI 模式**：自动化测试通过 `SampleMilvusIntegrationTest` 内置的 `@Profile("!local")` 测试双替身桩（Mockito + gRPC 本地拦截桩）100% 离线自洽跑通，无需外部 Milvus 实例；
- **Local 真实模式**：若需连接本地或远端真实 Milvus / Zilliz Cloud 实例，复制 [application-local.example.yml](./src/main/resources/application-local.example.yml) 为 `application-local.yml` 并填入真实节点与认证凭据（已被 `.gitignore` 忽略，CI 零风险），激活 `local` Profile 启动应用或运行集成测试即可直连验证。

---

## 3. 核心 Controller 接口与一键体验

本工程遵循多 Controller 领域拆分规约，拆分为 9 大专职 `@RestController`（入参严格遵循 DTO 隔离、出参统一显式返回 `Result<T>`）：

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **健康探针与服务端版本** | `MilvusLifecycleController` | `GET /sample/milvus/lifecycle/health` | `curl -s -X GET "http://localhost:8080/sample/milvus/lifecycle/health"` |
| **一键建表建索引并加载** | `MilvusLifecycleController` | `POST /sample/milvus/lifecycle/collection/init` | `curl -s -X POST "http://localhost:8080/sample/milvus/lifecycle/collection/init"` |
| **列举全部物理集合** | `MilvusLifecycleController` | `GET /sample/milvus/lifecycle/collection/list` | `curl -s -X GET "http://localhost:8080/sample/milvus/lifecycle/collection/list"` |
| **查看集合结构与加载状态** | `MilvusLifecycleController` | `GET /sample/milvus/lifecycle/collection/describe` | `curl -s -X GET "http://localhost:8080/sample/milvus/lifecycle/collection/describe?collectionName=sample_document"` |
| **加载/释放集合内存** | `MilvusLifecycleController` | `POST /sample/milvus/lifecycle/collection/load` | `curl -s -X POST "http://localhost:8080/sample/milvus/lifecycle/collection/load?collectionName=sample_document"` |
| **释放集合内存占用** | `MilvusLifecycleController` | `POST /sample/milvus/lifecycle/collection/release` | `curl -s -X POST "http://localhost:8080/sample/milvus/lifecycle/collection/release?collectionName=sample_document"` |
| **原生 gRPC 显式落盘** | `MilvusLifecycleController` | `POST /sample/milvus/lifecycle/collection/flush` | `curl -s -X POST "http://localhost:8080/sample/milvus/lifecycle/collection/flush?collectionName=sample_document"` |
| **删除测试集合** | `MilvusLifecycleController` | `DELETE /sample/milvus/lifecycle/collection` | `curl -s -X DELETE "http://localhost:8080/sample/milvus/lifecycle/collection?collectionName=sample_document"` |
| **初始化归档分区日志集合** | `MilvusPartitionController` | `POST /sample/milvus/partition/init` | `curl -s -X POST "http://localhost:8080/sample/milvus/partition/init"` |
| **创建物理分区** | `MilvusPartitionController` | `POST /sample/milvus/partition/create` | `curl -s -X POST "http://localhost:8080/sample/milvus/partition/create" -H "Content-Type: application/json" -d '{"collectionName":"sample_archive_log","partitionName":"part_202611"}'` |
| **列举全部物理分区** | `MilvusPartitionController` | `GET /sample/milvus/partition/list` | `curl -s -X GET "http://localhost:8080/sample/milvus/partition/list?collectionName=sample_archive_log"` |
| **加载指定物理分区** | `MilvusPartitionController` | `POST /sample/milvus/partition/load` | `curl -s -X POST "http://localhost:8080/sample/milvus/partition/load" -H "Content-Type: application/json" -d '{"collectionName":"sample_archive_log","partitionNames":["part_202611"]}'` |
| **探测分区全加载状态** | `MilvusPartitionController` | `GET /sample/milvus/partition/loaded` | `curl -s -X GET "http://localhost:8080/sample/milvus/partition/loaded?collectionName=sample_archive_log&partitionNames=part_202611"` |
| **释放指定物理分区** | `MilvusPartitionController` | `POST /sample/milvus/partition/release` | `curl -s -X POST "http://localhost:8080/sample/milvus/partition/release" -H "Content-Type: application/json" -d '{"collectionName":"sample_archive_log","partitionNames":["part_202611"]}'` |
| **按月自动建分区归档写入** | `MilvusPartitionController` | `POST /sample/milvus/partition/archive-insert` | `curl -s -X POST "http://localhost:8080/sample/milvus/partition/archive-insert" -H "Content-Type: application/json" -d '{"partitionName":"part_202612","id":2001,"archiveMonth":"2026-12","title":"年度审计向量归档","vector":[0.11,0.22,0.33,0.44]}'` |
| **删除指定物理分区** | `MilvusPartitionController` | `DELETE /sample/milvus/partition` | `curl -s -X DELETE "http://localhost:8080/sample/milvus/partition?collectionName=sample_archive_log&partitionName=part_202611"` |
| **创建逻辑集合别名** | `MilvusAliasController` | `POST /sample/milvus/alias/create` | `curl -s -X POST "http://localhost:8080/sample/milvus/alias/create" -H "Content-Type: application/json" -d '{"collectionName":"sample_document","alias":"doc_alias_prod"}'` |
| **蓝绿平滑切换别名** | `MilvusAliasController` | `POST /sample/milvus/alias/alter` | `curl -s -X POST "http://localhost:8080/sample/milvus/alias/alter" -H "Content-Type: application/json" -d '{"collectionName":"sample_document","alias":"doc_alias_prod","checkLoaded":true}'` |
| **查询集合别名与详情** | `MilvusAliasController` | `GET /sample/milvus/alias/list` | `curl -s -X GET "http://localhost:8080/sample/milvus/alias/list?collectionName=sample_document"` |
| **查询别名绑定的物理集合** | `MilvusAliasController` | `GET /sample/milvus/alias/detail` | `curl -s -X GET "http://localhost:8080/sample/milvus/alias/detail?alias=doc_alias_prod"` |
| **删除逻辑别名** | `MilvusAliasController` | `DELETE /sample/milvus/alias/drop` | `curl -s -X DELETE "http://localhost:8080/sample/milvus/alias/drop?alias=doc_alias_prod"` |
| **创建逻辑数据库** | `MilvusDatabaseController` | `POST /sample/milvus/database/create` | `curl -s -X POST "http://localhost:8080/sample/milvus/database/create" -H "Content-Type: application/json" -d '{"databaseName":"sample_route_db"}'` |
| **探测逻辑库是否存在** | `MilvusDatabaseController` | `GET /sample/milvus/database/has` | `curl -s -X GET "http://localhost:8080/sample/milvus/database/has?databaseName=default"` |
| **列举逻辑数据库** | `MilvusDatabaseController` | `GET /sample/milvus/database/list` | `curl -s -X GET "http://localhost:8080/sample/milvus/database/list"` |
| **作用域动态切库检索** | `MilvusDatabaseController` | `POST /sample/milvus/database/search` | `curl -s -X POST "http://localhost:8080/sample/milvus/database/search" -H "Content-Type: application/json" -d '{"databaseName":"default","vector":[0.12,0.34,0.56,0.78],"topK":5}'` |
| **删除逻辑数据库** | `MilvusDatabaseController` | `DELETE /sample/milvus/database/drop` | `curl -s -X DELETE "http://localhost:8080/sample/milvus/database/drop?databaseName=sample_route_db"` |
| **单篇知识库文档写入** | `MilvusVectorController` | `POST /sample/milvus/vector/insert` | `curl -s -X POST "http://localhost:8080/sample/milvus/vector/insert" -H "Content-Type: application/json" -d '{"id":1,"category":"TECH","tenantId":"tenant_a","title":"Milvus 向量检索实战","content":"企业级向量检索与混合召回架构","vector":[0.12,0.34,0.56,0.78]}'` |
| **单篇覆盖写入 (Upsert)** | `MilvusVectorController` | `POST /sample/milvus/vector/upsert` | `curl -s -X POST "http://localhost:8080/sample/milvus/vector/upsert" -H "Content-Type: application/json" -d '{"id":1,"category":"TECH","tenantId":"tenant_a","title":"Milvus 向量检索实战(修订)","content":"支持幂等覆盖更新","vector":[0.15,0.35,0.55,0.75]}'` |
| **分批切片批量插入** | `MilvusVectorController` | `POST /sample/milvus/vector/batch-insert` | `curl -s -X POST "http://localhost:8080/sample/milvus/vector/batch-insert" -H "Content-Type: application/json" -d '{"documents":[{"id":2,"category":"AI","title":"RAG 架构实践","content":"基于稠密向量的语义检索","vector":[0.21,0.43,0.65,0.87]}]}'` |
| **分批切片批量 Upsert** | `MilvusVectorController` | `POST /sample/milvus/vector/batch-upsert` | `curl -s -X POST "http://localhost:8080/sample/milvus/vector/batch-upsert" -H "Content-Type: application/json" -d '{"documents":[{"id":2,"category":"AI","title":"RAG 架构实践(更新)","content":"分批幂等覆盖与退避重试","vector":[0.22,0.44,0.66,0.88]}]}'` |
| **主键点查 / 批量点查** | `MilvusVectorController` | `GET /sample/milvus/vector/get/{id}` | `curl -s -X GET "http://localhost:8080/sample/milvus/vector/get/1"` |
| **批量主键点查** | `MilvusVectorController` | `GET /sample/milvus/vector/batch-get` | `curl -s -X GET "http://localhost:8080/sample/milvus/vector/batch-get?ids=1,2"` |
| **主键存在性探测** | `MilvusVectorController` | `GET /sample/milvus/vector/exists/{id}` | `curl -s -X GET "http://localhost:8080/sample/milvus/vector/exists/1"` |
| **单向量 ANN 检索** | `MilvusVectorController` | `POST /sample/milvus/vector/search` | `curl -s -X POST "http://localhost:8080/sample/milvus/vector/search" -H "Content-Type: application/json" -d '{"vector":[0.12,0.34,0.56,0.78],"topK":5,"filterExpr":"category == '\''TECH'\''"}'` |
| **标量查询与条数统计** | `MilvusVectorController` | `GET /sample/milvus/vector/query` | `curl -s -X GET "http://localhost:8080/sample/milvus/vector/query?expr=id%20%3E%3D%201"` |
| **条件文档统计** | `MilvusVectorController` | `GET /sample/milvus/vector/count` | `curl -s -X GET "http://localhost:8080/sample/milvus/vector/count?expr=category%20%3D%3D%20%27TECH%27"` |
| **按主键 / 按条件删除** | `MilvusVectorController` | `DELETE /sample/milvus/vector/delete/{id}` | `curl -s -X DELETE "http://localhost:8080/sample/milvus/vector/delete/1"` |
| **按标量表达式批量删数** | `MilvusVectorController` | `DELETE /sample/milvus/vector/delete-by-expr` | `curl -s -X DELETE "http://localhost:8080/sample/milvus/vector/delete-by-expr?expr=id%20%3D%3D%202"` |
| **GroupBy 去重聚合检索** | `MilvusAdvancedSearchController` | `POST /sample/milvus/search/group-by` | `curl -s -X POST "http://localhost:8080/sample/milvus/search/group-by" -H "Content-Type: application/json" -d '{"collectionName":"sample_document","vector":[0.12,0.34,0.56,0.78],"groupByField":"category","topK":3}'` |
| **Range 相似度区间检索** | `MilvusAdvancedSearchController` | `POST /sample/milvus/search/range` | `curl -s -X POST "http://localhost:8080/sample/milvus/search/range" -H "Content-Type: application/json" -d '{"collectionName":"sample_document","vector":[0.12,0.34,0.56,0.78],"radius":0.5,"rangeFilter":0.85,"topK":5}'` |
| **ExprHelper 与全文检索** | `MilvusAdvancedSearchController` | `POST /sample/milvus/search/expr` | `curl -s -X POST "http://localhost:8080/sample/milvus/search/expr" -H "Content-Type: application/json" -d '{"collectionName":"sample_document","vector":[0.12,0.34,0.56,0.78],"category":"TECH","minId":1,"topK":5}'` |
| **QueryIterator 游标流遍历** | `MilvusAdvancedSearchController` | `POST /sample/milvus/search/stream` | `curl -s -X POST "http://localhost:8080/sample/milvus/search/stream" -H "Content-Type: application/json" -d '{"category":"TECH","minId":1,"batchSize":200}'` |
| **RRF / Weighted 混合检索** | `MilvusHybridController` | `POST /sample/milvus/hybrid/search` | `curl -s -X POST "http://localhost:8080/sample/milvus/hybrid/search" -H "Content-Type: application/json" -d '{"vector":[0.12,0.34,0.56,0.78],"secondaryVector":[0.21,0.43,0.65,0.87],"topK":5,"rankerType":"RRF","rrfK":60}'` |
| **初始化多模态索引集合** | `MilvusMultimodalController` | `POST /sample/milvus/multimodal/init` | `curl -s -X POST "http://localhost:8080/sample/milvus/multimodal/init"` |
| **写入多模态稠密稀疏文档** | `MilvusMultimodalController` | `POST /sample/milvus/multimodal/insert` | `curl -s -X POST "http://localhost:8080/sample/milvus/multimodal/insert" -H "Content-Type: application/json" -d '{"id":1001,"title":"多模态检索白皮书","summary":"稠密语义与稀疏关键词双引擎","denseVector":[0.12,0.34,0.56,0.78],"sparseVector":{"101":0.85,"202":0.45},"extra":{"source":"manual","score":99}}'` |
| **点查多模态动态字段详情** | `MilvusMultimodalController` | `GET /sample/milvus/multimodal/{id}` | `curl -s -X GET "http://localhost:8080/sample/milvus/multimodal/1001"` |
| **指定列稠密向量强类型检索** | `MilvusMultimodalController` | `POST /sample/milvus/multimodal/search/dense` | `curl -s -X POST "http://localhost:8080/sample/milvus/multimodal/search/dense" -H "Content-Type: application/json" -d '{"vector":[0.12,0.34,0.56,0.78],"topK":3}'` |
| **稀疏向量 BM25/SPLADE 检索** | `MilvusMultimodalController` | `POST /sample/milvus/multimodal/search/sparse` | `curl -s -X POST "http://localhost:8080/sample/milvus/multimodal/search/sparse" -H "Content-Type: application/json" -d '{"sparseVector":{"101":0.85,"202":0.45},"topK":3}'` |
| **稠密+稀疏多模态混合检索** | `MilvusMultimodalController` | `POST /sample/milvus/multimodal/search/hybrid` | `curl -s -X POST "http://localhost:8080/sample/milvus/multimodal/search/hybrid" -H "Content-Type: application/json" -d '{"denseVector":[0.12,0.34,0.56,0.78],"sparseVector":{"101":0.85,"202":0.45},"topK":5}'` |
| **多租户自动补全写入** | `MilvusTenantController` | `POST /sample/milvus/tenant/insert` | `curl -s -X POST "http://localhost:8080/sample/milvus/tenant/insert" -H "Content-Type: application/json" -H "X-Tenant-Id: tenant_corp_alpha" -d '{"id":100,"category":"POLICY","title":"租户安全白皮书","content":"仅限本租户检索访问","vector":[0.11,0.22,0.33,0.44]}'` |
| **多租户隔离向量检索** | `MilvusTenantController` | `POST /sample/milvus/tenant/search` | `curl -s -X POST "http://localhost:8080/sample/milvus/tenant/search" -H "Content-Type: application/json" -H "X-Tenant-Id: tenant_corp_alpha" -d '{"vector":[0.11,0.22,0.33,0.44],"topK":5}'` |
| **租户文档统计与点查删** | `MilvusTenantController` | `GET /sample/milvus/tenant/count` | `curl -s -X GET "http://localhost:8080/sample/milvus/tenant/count" -H "X-Tenant-Id: tenant_corp_alpha"` |
| **租户防越权主键点查** | `MilvusTenantController` | `GET /sample/milvus/tenant/get/{id}` | `curl -s -X GET "http://localhost:8080/sample/milvus/tenant/get/100" -H "X-Tenant-Id: tenant_corp_alpha"` |
| **租户安全删除隔离** | `MilvusTenantController` | `DELETE /sample/milvus/tenant/delete/{id}` | `curl -s -X DELETE "http://localhost:8080/sample/milvus/tenant/delete/100" -H "X-Tenant-Id: tenant_corp_alpha"` |

---

## 4. 全真自动化回归命令

```bash
# 离线自洽全量测试（默认/CI 模式）
mvn test -pl ateng-boot-milvus,ateng-boot-samples/ateng-boot-sample-milvus -am -T 1C -nsu -B

# 本地真实 Milvus 联调回归（需已配置 src/main/resources/application-local.yml）
mvn test -pl ateng-boot-samples/ateng-boot-sample-milvus -am -Dtest=SampleMilvusIntegrationTest -Dspring.profiles.active=local -T 1C -nsu -B
```

> 详细原理机制、配置清单与扩展定制，请参阅核心 Starter 文档：[ateng-boot-milvus README](../../ateng-boot-milvus/README.md)。
