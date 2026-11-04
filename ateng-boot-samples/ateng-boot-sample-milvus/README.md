# ateng-boot-sample-milvus 向量数据库与多向量混合检索体验卡

> 本样例工程基于 `ateng-boot-milvus` 与 `ateng-boot-web` 构建，提供企业级 Milvus 向量数据库、多向量混合检索（ANN Search）、SaaS 多租户安全隔离与分批切片摄取的可交互 Web REST 接口与端到端自动化验证套件。

---

## 1. 工程定位与核心能力

- **Web 契约底座**: 基于 `ateng-boot-web` 统一传输契约，所有接口均显式返回强类型 `Result<T>`，彻底遵循传输对象与实体解耦（Zero-Entity-Leakage）。
- **多领域 Controller 拆分**: 拒绝臃肿上帝类，拆分为 7 个职责单一的领域控制器：
  - `MilvusLifecycleController` (`/sample/milvus/lifecycle`): 服务端健康探针、集合一键初始化与预加载、加载/释放与集合元数据查看。
  - `MilvusAliasController` (`/sample/milvus/alias`): 集合别名生命周期运维与蓝绿零停机无感发布平滑切流。
  - `MilvusVectorController` (`/sample/milvus/vector`): 单向量 CRUD、主键点查、标量表达式组合过滤、ANN 相似度检索与防 OOM 分批切片数据摄取。
  - `MilvusAdvancedSearchController` (`/sample/milvus/search`): 单向量 GroupBy 去重聚合检索与 Range Search 范围区间检索。
  - `MilvusHybridController` (`/sample/milvus/hybrid`): 多向量多路召回、RRF 互逆排名融合与加权融合重排。
  - `MilvusTenantController` (`/sample/milvus/tenant`): SaaS 多租户隔离读写、请求头租户标识自动织入与线程上下文安全重置。
  - `MilvusMultimodalController` (`/sample/milvus/multimodal`): 多模态多特征向量集合生命周期、稠密语义检索、稀疏关键字 (BM25/SPLADE) 检索与稠密+稀疏多路混合召回。
- **双模自适应驱动**:
  - **默认/CI 模式**: 自动装配 `@Profile("!local")` 纯内存离线测试桩，零网络依赖秒级全绿自洽；
  - **Local 真实模式**: 激活 `local` Profile 自动直连真实 Milvus 实例或云服务（如 Zilliz Cloud），全真走通生产链路。

---

## 2. 快速启动与环境切换

### 2.1 端口与默认配置
工程默认服务端口为 `8080`，默认配置位于 `src/main/resources/application.yml`。

### 2.2 本地真实联调切换 (Local Profile)
1. 复制模板文件 `application-local.example.yml` 为 `application-local.yml`：
   ```bash
   cp src/main/resources/application-local.example.yml src/main/resources/application-local.yml
   ```
2. 填入本地或远程真实 Milvus 连接参数（该文件已被 `.gitignore` 严格忽略，绝对保障凭据安全）：
   ```yaml
   spring:
     config:
       activate:
         on-profile: local

   ateng:
     boot:
       milvus:
         enabled: true
         uri: http://192.168.1.100:19530
         token: root:Milvus # 或 Zilliz Cloud API Key
         database: default
         consistency-level: BOUNDED
   ```
3. 激活 `local` Profile 启动：
   ```bash
   mvn spring-boot:run -Dspring-boot.run.profiles=local
   ```

---

## 3. 核心 Controller 接口与一键体验 cURL

### 3.1 集合生命周期与运维 (`MilvusLifecycleController`)

#### 1) 探测服务端连通性与版本
```bash
curl -X GET "http://localhost:8080/sample/milvus/lifecycle/health"
```

#### 2) 一键初始化集合与向量索引 (包含预加载)
```bash
curl -X POST "http://localhost:8080/sample/milvus/lifecycle/collection/init"
```

#### 3) 列出所有集合名称
```bash
curl -X GET "http://localhost:8080/sample/milvus/lifecycle/collection/list"
```

#### 4) 查看集合结构详情与加载状态
```bash
curl -X GET "http://localhost:8080/sample/milvus/lifecycle/collection/describe?collectionName=sample_document"
```

#### 5) 释放集合内存
```bash
curl -X POST "http://localhost:8080/sample/milvus/lifecycle/collection/release?collectionName=sample_document"
```

#### 6) 重新加载集合至内存
```bash
curl -X POST "http://localhost:8080/sample/milvus/lifecycle/collection/load?collectionName=sample_document"
```

#### 7) 删除集合
```bash
curl -X DELETE "http://localhost:8080/sample/milvus/lifecycle/collection?collectionName=sample_document"
```

---

### 3.2 单向量 CRUD 与检索 (`MilvusVectorController`)

#### 1) 插入单篇文档
```bash
curl -X POST "http://localhost:8080/sample/milvus/vector/insert" \
  -H "Content-Type: application/json" \
  -d '{
    "id": 1,
    "category": "TECH",
    "tenantId": "tenant_a",
    "title": "Spring Boot 向量检索技术",
    "content": "基于 Milvus 2.4+ 构建企业级向量检索基础设施与多路召回",
    "vector": [0.12, 0.34, 0.56, 0.78]
  }'
```

#### 2) 覆盖写入文档 (Upsert)
```bash
curl -X POST "http://localhost:8080/sample/milvus/vector/upsert" \
  -H "Content-Type: application/json" \
  -d '{
    "id": 1,
    "category": "TECH",
    "tenantId": "tenant_a",
    "title": "Spring Boot 向量检索技术 (更新版)",
    "content": "全面支持 MilvusTemplate 极简门面与透明分批切片摄取",
    "vector": [0.15, 0.35, 0.55, 0.75]
  }'
```

#### 3) 分批切片数据摄取 (Batch Insert)
```bash
curl -X POST "http://localhost:8080/sample/milvus/vector/batch-insert" \
  -H "Content-Type: application/json" \
  -d '{
    "documents": [
      {
        "id": 2,
        "category": "AI",
        "title": "RAG 知识库检索增强",
        "content": "大模型外挂向量库检索与重排",
        "vector": [0.21, 0.43, 0.65, 0.87]
      },
      {
        "id": 3,
        "category": "BIGDATA",
        "title": "流批一体向量化计算",
        "content": "海量文本特征实时抽取与切片写入",
        "vector": [0.31, 0.53, 0.75, 0.97]
      }
    ]
  }'
```

#### 4) 主键点查文档详情
```bash
curl -X GET "http://localhost:8080/sample/milvus/vector/get/1"
```

#### 5) 单向量 ANN 相似度检索 (支持标量过滤)
```bash
curl -X POST "http://localhost:8080/sample/milvus/vector/search" \
  -H "Content-Type: application/json" \
  -d '{
    "vector": [0.12, 0.34, 0.56, 0.78],
    "topK": 5,
    "filterExpr": "category == '\''TECH'\''"
  }'
```

#### 6) 纯标量表达式查询
```bash
curl -X GET "http://localhost:8080/sample/milvus/vector/query?expr=id%20%3E%3D%201"
```

#### 7) 文档统计
```bash
curl -X GET "http://localhost:8080/sample/milvus/vector/count?expr=category%20%3D%3D%20%27TECH%27"
```

#### 8) 主键删除文档
```bash
curl -X DELETE "http://localhost:8080/sample/milvus/vector/delete/1"
```

---

### 3.3 多向量多路混合检索 (`MilvusHybridController`)

#### 1) RRF 互逆排名融合检索
```bash
curl -X POST "http://localhost:8080/sample/milvus/hybrid/search" \
  -H "Content-Type: application/json" \
  -d '{
    "vector": [0.12, 0.34, 0.56, 0.78],
    "secondaryVector": [0.21, 0.43, 0.65, 0.87],
    "topK": 10,
    "rankerType": "RRF",
    "rrfK": 60,
    "filterExpr": "id > 0"
  }'
```

#### 2) WEIGHTED 加权融合检索
```bash
curl -X POST "http://localhost:8080/sample/milvus/hybrid/search" \
  -H "Content-Type: application/json" \
  -d '{
    "vector": [0.12, 0.34, 0.56, 0.78],
    "topK": 5,
    "rankerType": "WEIGHTED",
    "weights": [0.8, 0.2]
  }'
```

---

### 3.4 SaaS 多租户隔离验证 (`MilvusTenantController`)

#### 1) 租户专属文档写入 (自动织入 `tenant_id`)
```bash
curl -X POST "http://localhost:8080/sample/milvus/tenant/insert" \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: tenant_corp_001" \
  -d '{
    "id": 101,
    "category": "CONFIDENTIAL",
    "title": "租户001专属商业机密",
    "content": "该文档受到租户隔离守卫防护，其他租户不可见",
    "vector": [0.11, 0.22, 0.33, 0.44]
  }'
```

#### 2) 租户隔离上下文受限检索 (防越权数据泄露)
```bash
curl -X POST "http://localhost:8080/sample/milvus/tenant/search" \
  -H "Content-Type: application/json" \
  -H "X-Tenant-Id: tenant_corp_001" \
  -d '{
    "vector": [0.11, 0.22, 0.33, 0.44],
    "topK": 5
  }'
```

#### 3) 租户文档总数统计
```bash
curl -X GET "http://localhost:8080/sample/milvus/tenant/count" \
  -H "X-Tenant-Id: tenant_corp_001"
```

---

### 3.5 集合别名生命周期与蓝绿无感发布 (`MilvusAliasController`)

#### 1) 为集合创建逻辑别名
```bash
curl -X POST "http://localhost:8080/sample/milvus/alias/create" \
  -H "Content-Type: application/json" \
  -d '{
    "collectionName": "sample_document",
    "alias": "doc_alias_prod"
  }'
```

#### 2) 平滑切换别名 (蓝绿发布无感切流，带前置内存就绪校验)
```bash
curl -X POST "http://localhost:8080/sample/milvus/alias/alter" \
  -H "Content-Type: application/json" \
  -d '{
    "collectionName": "sample_document",
    "alias": "doc_alias_prod",
    "checkLoaded": true
  }'
```

#### 3) 查询集合绑定的所有别名
```bash
curl -X GET "http://localhost:8080/sample/milvus/alias/list?collectionName=sample_document"
```

#### 4) 查看别名元数据详情
```bash
curl -X GET "http://localhost:8080/sample/milvus/alias/detail?alias=doc_alias_prod"
```

#### 5) 解绑/删除别名
```bash
curl -X DELETE "http://localhost:8080/sample/milvus/alias/drop?alias=doc_alias_prod"
```

---

### 3.6 高级特征检索：GroupBy 与 Range Search (`MilvusAdvancedSearchController`)

#### 1) 单向量 GroupBy 去重聚合检索 (按分类每个组仅召回最匹配 Top 1)
```bash
curl -X POST "http://localhost:8080/sample/milvus/search/group-by" \
  -H "Content-Type: application/json" \
  -d '{
    "collectionName": "sample_document",
    "vector": [0.12, 0.34, 0.56, 0.78],
    "groupByField": "category",
    "topK": 3
  }'
```

#### 2) 范围过滤检索 (Range Search，圈定置信度相似度区间)
```bash
curl -X POST "http://localhost:8080/sample/milvus/search/range" \
  -H "Content-Type: application/json" \
  -d '{
    "collectionName": "sample_document",
    "vector": [0.12, 0.34, 0.56, 0.78],
    "radius": 0.50,
    "rangeFilter": 0.85,
    "topK": 5
  }'
```

---

### 3.7 多模态多特征与稀疏向量检索 (`MilvusMultimodalController`)

#### 1) 一键初始化多模态集合与倒排/多向量混合索引
```bash
curl -X POST "http://localhost:8080/sample/milvus/multimodal/init"
```

#### 2) 单篇多模态文档新增写入 (含稠密、稀疏与动态扩展属性)
```bash
curl -X POST "http://localhost:8080/sample/milvus/multimodal/insert" \
  -H "Content-Type: application/json" \
  -d '{
    "id": 1001,
    "title": "多模态检索技术白皮书",
    "summary": "结合稠密语义与稀疏关键字的高精度检索架构",
    "denseVector": [0.12, 0.34, 0.56, 0.78],
    "sparseVector": {
      "101": 0.85,
      "202": 0.45
    },
    "extra": {
      "source": "crawler",
      "score": 99
    }
  }'
```

#### 3) 主键点查多模态文档详情
```bash
curl -X GET "http://localhost:8080/sample/milvus/multimodal/1001"
```

#### 4) 稠密语义向量 ANN 相似度检索
```bash
curl -X POST "http://localhost:8080/sample/milvus/multimodal/search/dense" \
  -H "Content-Type: application/json" \
  -d '{
    "vector": [0.12, 0.34, 0.56, 0.78],
    "topK": 5,
    "expr": "title LIKE '\''%检索%'\''"
  }'
```

#### 5) 稀疏关键词向量 ANN 检索 (BM25 / SPLADE)
```bash
curl -X POST "http://localhost:8080/sample/milvus/multimodal/search/sparse" \
  -H "Content-Type: application/json" \
  -d '{
    "sparseVector": {
      "101": 0.85,
      "202": 0.45
    },
    "topK": 5
  }'
```

#### 6) 稠密与稀疏多模态多路混合召回融合重排 (RRF)
```bash
curl -X POST "http://localhost:8080/sample/milvus/multimodal/search/hybrid" \
  -H "Content-Type: application/json" \
  -d '{
    "denseVector": [0.12, 0.34, 0.56, 0.78],
    "sparseVector": {
      "101": 0.85,
      "202": 0.45
    },
    "topK": 5
  }'
```

---

## 4. 全真自动化回归与测试命令

### 4.1 离线自洽测试 (默认/CI 快速反馈)
```bash
mvn test -pl ateng-boot-samples/ateng-boot-sample-milvus -am -T 1C -nsu -B
```

### 4.2 本地真实连通测试 (激活 local Profile)
```bash
mvn test -pl ateng-boot-samples/ateng-boot-sample-milvus -Dspring.profiles.active=local -am -T 1C -nsu -B
```
