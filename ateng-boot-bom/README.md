# ateng-boot-bom 企业级依赖物料清单 (Bill of Materials)

> 集中式纯 POM 依赖物料清单（`<packaging>pom</packaging>`），非侵入式统一锁定 `ateng-boot` 全系分发 Starter 及其配套第三方核心中间件版本，彻底消除微服务多仓库场景下的版本漂移与依赖地狱。

---

## 0. 全量纳管坐标与隔离边界全景速查矩阵 (维度零：100% 全覆盖)

本模块共显式纳管 **70 项 `<dependencyManagement>` 条目**（含 `2` 项基准 BOM 导入、`36` 项 `io.github.atengk` 分发构件与 `32` 项核心第三方中间件坐标），并通过 `ManagedDependencyBoundary` 严格屏蔽框架内部兼容与单测依赖。

### 0.1 顶层基准 BOM 传递导入 (2 项)

| 基准 BOM 坐标 (`groupId:artifactId`) | 锁定版本 | 导入方式 (`<type>` / `<scope>`) | 职责与对齐基线说明 |
| :--- | :---: | :---: | :--- |
| `org.springframework.boot:spring-boot-dependencies` | `3.3.13` | `pom` / `import` | 纳管 Spring Boot 3.3、Spring Framework 6.1、Jackson `2.17.3`、Netty、Micrometer 等全部基础运行时版本（[ADR-0006](../docs/adr/0006-dependency-baseline-and-upgrade-strategy.md)） |
| `software.amazon.awssdk:bom` | `2.25.70` | `pom` / `import` | 统一对齐 AWS SDK v2 S3 客户端与底层 HTTP / Auth / Regions 子构件版本 |

### 0.2 `ateng-boot` 全部分发构件坐标清单 (36 项 = 1 聚合 + 35 Starter)

| 领域分组 | 构件坐标 (`io.github.atengk:*`) | 当前版本 | 职责定位 | 相关文档 |
| :--- | :--- | :---: | :--- | :--- |
| **核心聚合** | `io.github.atengk:ateng-boot-all` | `1.2.0` | 六大核心 Starter 一键传递聚合 JAR | [README](../ateng-boot-all/README.md) |
| **基础底座** | `io.github.atengk:ateng-boot-core` | `1.2.0` | `Result<T>`、`PageResult<T>`、`AsyncContext`、脱敏/加解密/枚举等原语 | [README](../ateng-boot-core/README.md) |
| **基础底座** | `io.github.atengk:ateng-boot-web` | `1.2.0` | `TraceFilter`、全局异常、`@Idempotent`、`@RateLimiter`、`@ApiSign` | [README](../ateng-boot-web/README.md) |
| **基础底座** | `io.github.atengk:ateng-boot-mybatis-plus` | `1.2.0` | 审计填充、行级多租户、`@DataScope`、`@FieldEncrypt`、动态查询与批量增强 | [README](../ateng-boot-mybatis-plus/README.md) |
| **基础底座** | `io.github.atengk:ateng-boot-redis` | `1.2.0` | 双核 Redis、`@DistributedLock`、防击穿缓存、L1+L2 缓存、延时队列与 Stream | [README](../ateng-boot-redis/README.md) |
| **基础底座** | `io.github.atengk:ateng-boot-excel` | `1.2.0` | Apache Fesod 流式导出、`@ExcelSensitive`、行级校验回写、AES 工作簿加密 | [README](../ateng-boot-excel/README.md) |
| **基础底座** | `io.github.atengk:ateng-boot-satoken` | `1.2.0` | `LoginHelper`、双轨拦截、L1+L2 权限缓存、多账号与 Same-Token 零信任 | [README](../ateng-boot-satoken/README.md) |
| **存储文档** | `io.github.atengk:ateng-boot-storage` | `1.2.0` | Local 与 S3 统一对象存储、自适应分片流式上传、断点续传 | [README](../ateng-boot-storage/README.md) |
| **存储文档** | `io.github.atengk:ateng-boot-ftp` | `1.2.0` | FTP/FTPS 连接池治理、RFC 2640 防乱码、原子上传与目录同步 | [README](../ateng-boot-ftp/README.md) |
| **存储文档** | `io.github.atengk:ateng-boot-ssh` | `1.2.0` | SSH/SFTP 会话池、批量巡检、超时防孤儿进程、端口转发与 WebSSH | [README](../ateng-boot-ssh/README.md) |
| **存储文档** | `io.github.atengk:ateng-boot-document` | `1.2.0` | Word 模板引擎 (`poi-tl`) 与 PDF 水印/骑缝章/表单治理 (`PDFBox`) | [README](../ateng-boot-document/README.md) |
| **网络通信** | `io.github.atengk:ateng-boot-http` | `1.2.0` | Spring 6 `@HttpClient`、多命名客户端、`HttpTemplate`、SSE 与 mTLS | [README](../ateng-boot-http/README.md) |
| **网络通信** | `io.github.atengk:ateng-boot-realtime` | `1.2.0` | WebSocket 与 SSE 双规融合、分布式群组、离线重放与平滑停机 | [README](../ateng-boot-realtime/README.md) |
| **网络通信** | `io.github.atengk:ateng-boot-tcp` | `1.2.0` | Netty 4.1 双模长连接服务端/客户端、粘包拆包、mTLS 与跨节点桥接 | [README](../ateng-boot-tcp/README.md) |
| **网络通信** | `io.github.atengk:ateng-boot-mqtt` | `1.2.0` | MQTT v5 物联网双向通信、共享订阅、RPC 聚合应答与死信重放 | [README](../ateng-boot-mqtt/README.md) |
| **消息引擎** | `io.github.atengk:ateng-boot-rabbitmq` | `1.2.0` | RabbitMQ 可靠投递、`sendAfterCommit`、死信受控重放与 Quorum 拓扑 | [README](../ateng-boot-rabbitmq/README.md) |
| **消息引擎** | `io.github.atengk:ateng-boot-kafka` | `1.2.0` | Kafka 流批处理、死信重放、`@RetryableTopic` 与 Lag 位移观测 | [README](../ateng-boot-kafka/README.md) |
| **消息引擎** | `io.github.atengk:ateng-boot-rocketmq` | `1.2.0` | RocketMQ 5.x gRPC 客户端、2PC 分布式事务、SimpleConsumer 与死信治理 | [README](../ateng-boot-rocketmq/README.md) |
| **可观测运维** | `io.github.atengk:ateng-boot-observability` | `1.2.0` | K8s 探针硬分级隔离、`AbstractSafeHealthIndicator`、业务度量与端点守卫 | [README](../ateng-boot-observability/README.md) |
| **可观测运维** | `io.github.atengk:ateng-boot-openapi` | `1.2.0` | SpringDoc OpenAPI 3.0 契约治理、五模安全守卫、枚举转换与离线导出 | [README](../ateng-boot-openapi/README.md) |
| **可观测运维** | `io.github.atengk:ateng-boot-admin` | `1.2.0` | Spring Boot Admin 监控看板、Sa-Token 守卫、SSRF 注册防火墙 | [README](../ateng-boot-admin/README.md) |
| **通知触达** | `io.github.atengk:ateng-boot-mail` | `1.2.0` | 出站 SMTP 多账号、国际化模板、批量合并、测试沙箱与延迟补偿 | [README](../ateng-boot-mail/README.md) |
| **通知触达** | `io.github.atengk:ateng-boot-notification` | `1.2.0` | 企微/钉钉/飞书 Webhook 机器人推送、`@NotifyAlert` 防风暴与故障转移 | [README](../ateng-boot-notification/README.md) |
| **通知触达** | `io.github.atengk:ateng-boot-sms` | `1.2.0` | 零 SDK 第三方多渠道短信、负载均衡熔断与单日预算硬切断 | [README](../ateng-boot-sms/README.md) |
| **安全交易** | `io.github.atengk:ateng-boot-pay` | `1.2.0` | 微信支付 v3 与支付宝官方 SDK 统一/专有收单门面、异步通知防重网关 | [README](../ateng-boot-pay/README.md) |
| **安全交易** | `io.github.atengk:ateng-boot-social` | `1.2.0` | OAuth2 社会化登录、两阶段绑定票据与 Sa-Token 多端会话签发 | [README](../ateng-boot-social/README.md) |
| **安全交易** | `io.github.atengk:ateng-boot-datasource` | `1.2.0` | SaaS 动态多数据源、主从读写分离、从库自愈与事务假切源守卫 | [README](../ateng-boot-datasource/README.md) |
| **安全交易** | `io.github.atengk:ateng-boot-encrypt` | `1.2.0` | 配置透明加解密、AES/RSA/SM2/SM4、原地回写 CLI 与 `/env` 掩码 | [README](../ateng-boot-encrypt/README.md) |
| **调度流程** | `io.github.atengk:ateng-boot-statemachine` | `1.2.0` | 高性能无状态业务状态机 DSL、CAS 跃迁、事务同步与拓扑图导出 | [README](../ateng-boot-statemachine/README.md) |
| **调度流程** | `io.github.atengk:ateng-boot-liteflow` | `1.2.0` | LiteFlow 规则与 DAG 编排、受管并行线程池、静态预检与快照回滚 | [README](../ateng-boot-liteflow/README.md) |
| **调度流程** | `io.github.atengk:ateng-boot-job-local` | `1.2.0` | 单机定时任务、四阶重叠防卫、硬超时中断与轻量集群互斥锁 | [README](../ateng-boot-job-local/README.md) |
| **调度流程** | `io.github.atengk:ateng-boot-job-xxl` | `1.2.0` | XXL-Job 执行器加固、受管线程池与四维透传、大数据分片与排空治理 | [README](../ateng-boot-job-xxl/README.md) |
| **调度流程** | `io.github.atengk:ateng-boot-job-snail` | `1.2.0` | SnailJob 调度与分布式重试加固、本地看门狗熔断与脱网测试辅助 | [README](../ateng-boot-job-snail/README.md) |
| **搜索智能** | `io.github.atengk:ateng-boot-search` | `1.2.0` | Elasticsearch 8.x & OpenSearch 双引擎检索、流式批量与 Search-After | [README](../ateng-boot-search/README.md) |
| **搜索智能** | `io.github.atengk:ateng-boot-ai` | `1.2.0` | LangChain4j & LangGraph4j 智能体编排、RAG 溯源、HITL 审批与配额风控 | [README](../ateng-boot-ai/README.md) |
| **搜索智能** | `io.github.atengk:ateng-boot-milvus` | `1.2.0` | Milvus 向量库运维、多向量混合检索、多租户 PartitionKey 隔离 | [README](../ateng-boot-milvus/README.md) |

### 0.3 纳管核心第三方中间件坐标清单 (32 项)

| 归属 Starter | 第三方坐标 (`groupId:artifactId`) | 锁定版本 | 特殊处理 / `<exclusions>` 策略 |
| :--- | :--- | :---: | :--- |
| `mybatis-plus` | `com.baomidou:mybatis-plus-spring-boot3-starter` | `3.5.17` | 与 Core / Web / Datasource 基线强对齐 |
| `mybatis-plus` | `com.baomidou:mybatis-plus-jsqlparser` | `3.5.17` | 支撑行级多租户、数据权限与逻辑删除 AST 改写 |
| `redis` | `org.redisson:redisson` | `3.44.0` | 支撑分布锁、布隆、延时队列与 Stream |
| `excel` | `org.apache.fesod:fesod-sheet` | `2.1.0-incubating` | Apache Fesod 流式表格内核 |
| `satoken` | `cn.dev33:sa-token-spring-boot3-starter` | `1.46.0` | Jakarta EE / Spring Boot 3 核心鉴权底座 |
| `satoken` | `cn.dev33:sa-token-spring-aop` | `1.46.0` | 注解鉴权切面支撑 |
| `openapi` | `org.springdoc:springdoc-openapi-starter-webmvc-ui` | `2.6.0` | OpenAPI 3.0 Schema 与 Swagger UI 5.x |
| `storage` | `software.amazon.awssdk:s3` | `2.25.70` | 显式排除 `software.amazon.awssdk:netty-nio-client`（零冗余 Netty 冲突） |
| `ftp` | `commons-net:commons-net` | `3.11.1` | 纯净 FTP/FTPS 传输协议栈 |
| `ssh` | `org.apache.sshd:sshd-core` | `2.14.0` | Apache MINA SSHD 协议核心 |
| `ssh` | `org.apache.sshd:sshd-sftp` | `2.14.0` | Apache MINA SSHD SFTP 子系统 |
| `document` | `com.deepoove:poi-tl` | `1.12.2` | Word 模板 AST 渲染引擎 |
| `document` | `org.apache.pdfbox:pdfbox` | `3.0.4` | PDF 水印、切片与表单治理内核 |
| `mqtt` | `org.eclipse.paho:org.eclipse.paho.mqttv5.client` | `1.2.5` | Eclipse Paho MQTT 5.0 官方客户端 |
| `rocketmq` | `org.apache.rocketmq:rocketmq-client-java` | `5.2.2` | RocketMQ 5.x gRPC 瘦客户端 |
| `datasource` | `com.baomidou:dynamic-datasource-spring-boot3-starter` | `4.6.0` | 动态数据源多库路由核心 |
| `pay` | `com.github.wechatpay-apiv3:wechatpay-java` | `0.2.17` | 微信支付 API v3 官方 Java SDK |
| `pay` | `com.alipay.sdk:alipay-sdk-java` | `4.40.1044.ALL` | 支付宝开放平台官方 Java SDK |
| `admin` | `de.codecentric:spring-boot-admin-starter-server` | `3.3.4` | Spring Boot Admin 管控端 |
| `admin` | `de.codecentric:spring-boot-admin-starter-client` | `3.3.4` | Spring Boot Admin 被监控客户端 |
| `social` | `org.dromara.anduin:dromara-anduin` | `2.2.0` | OAuth2 多平台授权统一底层 |
| `liteflow` | `com.yomahub:liteflow-spring-boot-starter` | `2.16.3.1` | 规则编排引擎核心 |
| `job-xxl` | `com.xuxueli:xxl-job-core` | `3.5.0` | XXL-Job 执行器官方核心 |
| `job-snail` | `com.aizuda:snail-job-client-starter` | `1.10.0` | SnailJob 客户端 Starter |
| `job-snail` | `com.aizuda:snail-job-client-job-core` | `1.10.0` | SnailJob 分布式任务核心 |
| `job-snail` | `com.aizuda:snail-job-client-retry-core` | `1.10.0` | SnailJob 分布式重试核心 |
| `job-snail` | `com.aizuda:snail-job-common` | `1.10.0` | SnailJob 公共模型与协议 |
| `ai` | `dev.langchain4j:langchain4j` | `0.36.2` | LangChain4j 大模型抽象核 |
| `ai` | `dev.langchain4j:langchain4j-open-ai` | `0.36.2` | OpenAI / DeepSeek 兼容协议适配器 |
| `ai` | `org.bsc.langgraph4j:langgraph4j-core` | `1.3.1` | LangGraph4j 状态图编排核 |
| `ai` | `org.bsc.langgraph4j:langgraph4j-langchain4j` | `1.3.1` | LangGraph4j × LangChain4j 适配桥 |
| `milvus` | `io.milvus:milvus-sdk-java` | `2.4.4` | Milvus 向量数据库官方 SDK |

### 0.4 `ManagedDependencyBoundary` 内部防泄漏隔离清单

经 `mvn help:effective-pom -f ateng-boot-bom/pom.xml` 验证，以下仅供单模块兼容、单元测试或 Sample 验证的依赖**已从 BOM 物理隔离**，严禁向下游业务应用暴露或覆盖 Spring Boot 基线版本：

| 被隔离坐标 | 实际收敛位置 | 隔离原因说明 |
| :--- | :--- | :--- |
| `com.fasterxml.jackson.core:jackson-annotations` (`2.22`) | `ateng-boot-social/pom.xml` | 防止全局覆盖 `spring-boot-dependencies` 锁定的 `2.17.3` |
| `tools.jackson.core:jackson-databind` (`3.2.4`) | `ateng-boot-social/pom.xml` | 仅供 `ateng-boot-social` 运行期兼容 `dromara-anduin` |
| `org.apache.commons:commons-lang3` (`3.18.0` 覆盖) / `commons-collections4` / `com.google.guava:guava` | `ateng-boot-social/pom.xml` | 保持 BOM 对外保留 Spring Boot `commons-lang3:3.14.0`，零臃肿泄露 |
| `org.mockftpserver:MockFtpServer` / `com.icegreen:greenmail-junit5` | `ateng-boot-ftp`、`ateng-boot-mail` 与 `ateng-boot-samples` | 仅限仓内离线单测替身（Test Double）复用 |
| `dev.langchain4j:langchain4j-redis` / `langchain4j-pgvector` | `ateng-boot-samples/pom.xml` | 仅供样例可选扩展向量存储演示使用 |

---

## 1. 模块定位与快速起步 (维度一：最小可用引入)

### 1.1 一句话痛点与设计原则

* **解决痛点**：在微服务多工程或已有企业母 POM（如公司内部 Parent、`spring-boot-starter-parent`）中引入基础组件时，强行要求 `<parent>` 继承会导致既有构建插件与公共属性被破坏。
* **设计原则**：通过 Maven `<type>pom</type><scope>import</scope>` 机制将 `ateng-boot-bom` 非侵入式引入 `<dependencyManagement>`，实现“**主版本唯一对齐、下游模块按需免配版本、父 POM 架构零绑架**”。

### 1.2 导入 BOM 与下游按需引入 (Maven)

在您的工程根 `pom.xml` 中导入 `ateng-boot-bom`：

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>io.github.atengk</groupId>
            <artifactId>ateng-boot-bom</artifactId>
            <version>1.2.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

随后，在任意子模块 `<dependencies>` 中即可免配 `<version>` 精准按需引入：

```xml
<dependencies>
    <!-- 仅需声明 groupId 与 artifactId，版本号由 ateng-boot-bom 统一锁定 -->
    <dependency>
        <groupId>io.github.atengk</groupId>
        <artifactId>ateng-boot-web</artifactId>
    </dependency>
    <dependency>
        <groupId>io.github.atengk</groupId>
        <artifactId>ateng-boot-redis</artifactId>
    </dependency>
</dependencies>
```

> **快速上手提示**：若是**单体型系统或快速原型工程**，且希望一次性引入核心 6 大基础设施，可在导入 BOM 后直接引入 [`ateng-boot-all`](../ateng-boot-all/README.md)。

---

## 2. 依赖边界、隔离机制与定制覆盖策略 (维度二)

### 2.1 Parent POM vs BOM Import 解耦契约

* **彻底释放继承权**：下游业务应用可继续继承 `spring-boot-starter-parent` 或企业自研母 POM，互不冲突（详见 [ADR-0003](../docs/adr/0003-dependency-management-via-bom-import.md)）。
* **单一版本流保障**：`ateng-boot-bom` 在仓库构建期保留内部 `<parent>` 以继承发布配置与 `mvn versions:set` 单一版本推进流程；但发布的 BOM 工件中仅对外提供 `<dependencyManagement>` 声明与纯净基线属性。

### 2.2 版本覆盖与先声明优先原则 (Maven Dependency Management Precedence)

当下游业务因特殊历史兼容原因需要升级或回退 BOM 中某款三方组件（如临时覆盖 `redisson` 版本）时，遵循 Maven **“局部声明先于 `import` 生效”** 原则：

```xml
<dependencyManagement>
    <dependencies>
        <!-- 1. 显式定制版本必须声明在 import BOM 之前（优先命中） -->
        <dependency>
            <groupId>org.redisson</groupId>
            <artifactId>redisson</artifactId>
            <version>3.44.0</version>
        </dependency>

        <!-- 2. 再导入 ateng-boot-bom 其余全部锁定版本 -->
        <dependency>
            <groupId>io.github.atengk</groupId>
            <artifactId>ateng-boot-bom</artifactId>
            <version>1.2.0</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

### 2.3 与 `spring-boot-starter-parent` 共存时的属性覆盖注意事项

若您的项目继承了 `spring-boot-starter-parent` 且版本号与 `3.3.13` 不同：
1. 若希望跟随您自己 Parent 的 Spring Boot 版本，请将您的 Parent 或对应版本的 `spring-boot-dependencies`（`import`）声明在 `ateng-boot-bom` **之前**；
2. 若希望严格采用 `ateng-boot-bom` 验证通过的 `3.3.13` 基线，请将 `ateng-boot-bom` 的 `<scope>import</scope>` 声明放置在其他 BOM **之前**，或显式覆盖 `<spring-boot.version>` 属性。

---

## 3. 按业务拓扑组装 Starter 配方 (维度三)

### 3.1 配方 A：纯无状态微服务 / 网关聚合端 (Zero-DB)

适用于无直连数据库与缓存诉求的边缘 API 服务或 BFF 聚合层：

```xml
<dependencies>
    <dependency>
        <groupId>io.github.atengk</groupId>
        <artifactId>ateng-boot-web</artifactId>
    </dependency>
    <dependency>
        <groupId>io.github.atengk</groupId>
        <artifactId>ateng-boot-http</artifactId>
    </dependency>
    <dependency>
        <groupId>io.github.atengk</groupId>
        <artifactId>ateng-boot-openapi</artifactId>
    </dependency>
    <dependency>
        <groupId>io.github.atengk</groupId>
        <artifactId>ateng-boot-observability</artifactId>
    </dependency>
</dependencies>
```

### 3.2 配方 B：核心有状态订单 / 用户业务微服务

适用于带持久化、分布式锁防超卖与跨服务鉴权的标准微服务：

```xml
<dependencies>
    <dependency>
        <groupId>io.github.atengk</groupId>
        <artifactId>ateng-boot-web</artifactId>
    </dependency>
    <dependency>
        <groupId>io.github.atengk</groupId>
        <artifactId>ateng-boot-mybatis-plus</artifactId>
    </dependency>
    <dependency>
        <groupId>io.github.atengk</groupId>
        <artifactId>ateng-boot-redis</artifactId>
    </dependency>
    <dependency>
        <groupId>io.github.atengk</groupId>
        <artifactId>ateng-boot-satoken</artifactId>
    </dependency>
    <dependency>
        <groupId>io.github.atengk</groupId>
        <artifactId>ateng-boot-rabbitmq</artifactId>
    </dependency>
</dependencies>
```

### 3.3 配方 C：独立文档 / 对象存储 / 后台调度服务

将重型附件解析（`document` / `excel`）、大文件上传（`storage` / `ftp`）或定时任务调度（`job-xxl` / `job-snail`）物理拆分至独立工作微服务，与在线交易主链路通过 `bom` 按需隔离装配，避免单容器依赖膨胀。

---

## 4. 基线兼容性红线与高频冲突排障 (维度四)

### 4.1 核心运行时与组件兼容性基线

| 基础设施维度 | 强制锁定基线 | 说明 |
| :--- | :---: | :--- |
| **Java 编译与运行时** | **JDK 17 LTS**（兼容 **JDK 21+** 虚拟线程） | 字节码目标 17；开启 `spring.threads.virtual.enabled=true` 可直接激活 JDK 21 虚拟线程适配（[ADR-0029](../docs/adr/0029-virtual-threads-and-adaptive-async-runtime.md)） |
| **Spring Boot / Framework** | `3.3.13` / `6.1.x` | 全链路 Jakarta EE 10（`jakarta.*`）命名空间，**严禁混入 Spring Boot 2.x / `javax.*` 老旧依赖** |
| **持久化与解析** | MyBatis-Plus `3.5.17` | 内置 JSqlParser 已对齐至 `mybatis-plus-jsqlparser:3.5.17`，切勿手动引入低版本 `com.github.jsqlparser` |
| **鉴权与文档** | Sa-Token `1.46.0` + SpringDoc `2.6.0` | 原生对接 Spring Boot 3 与 Swagger UI 5.x，严禁与停更老旧文档框架混用 |

### 4.2 高频依赖冲突定位与排障命令

1. **查看特定组件的实际仲裁来源树**：
   ```bash
   mvn dependency:tree -Dincludes=com.fasterxml.jackson.core:jackson-annotations
   ```
2. **核验最终合成有效 POM 中生效的 `<dependencyManagement>` 条目**：
   ```bash
   mvn help:effective-pom -B
   ```
3. **验证仓内 BOM 本身未发生内部依赖泄漏**：
   ```bash
   mvn help:effective-pom -f ateng-boot-bom/pom.xml -B
   ```
4. **若外部历史 SDK 携带了低版本 Netty 或旧版 Servlet API**：
   在引入该外部 SDK 的 `<dependency>` 内使用 `<exclusions>` 剔除旧版本，交由 `ateng-boot-bom` 继承自 Spring Boot `3.3.13` 的对齐版本接管。
