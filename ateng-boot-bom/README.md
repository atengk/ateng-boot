# ateng-boot-bom 依赖版本物料清单 (Bill of Materials)

> 集中式纯 POM 依赖物料清单（`<packaging>pom</packaging>`），以非侵入式 `import` 方式统一锁定 `ateng-boot` 全系 Starter 与配套核心第三方组件版本，消除多模块、多仓库微服务工程中的版本漂移与冲突烦恼。

---

## 1. 快速接入

### 1.1 导入 BOM 并按需引入 Starter（Maven）

在项目根 `pom.xml` 的 `<dependencyManagement>` 中导入 `ateng-boot-bom`（无需改变你原有的 `<parent>` 继承）：

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

随后在任意子模块或业务工程的 `<dependencies>` 中直接声明所需 Starter，**免配 `<version>`**：

```xml
<dependencies>
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

<details>
<summary><strong>使用 Gradle？展开查看 Kotlin / Groovy DSL 引入方式</strong></summary>

**Kotlin DSL (`build.gradle.kts`)**：

```kotlin
dependencies {
    implementation(platform("io.github.atengk:ateng-boot-bom:1.2.0"))
    implementation("io.github.atengk:ateng-boot-web")
    implementation("io.github.atengk:ateng-boot-redis")
}
```

**Groovy DSL (`build.gradle`)**：

```groovy
dependencies {
    implementation platform('io.github.atengk:ateng-boot-bom:1.2.0')
    implementation 'io.github.atengk:ateng-boot-web'
    implementation 'io.github.atengk:ateng-boot-redis'
}
```

</details>

> **单体应用提示**：如果是单体管理系统或后台应用，希望一行引入核心六大件（`core`、`web`、`mybatis-plus`、`redis`、`excel`、`satoken`），可直接使用 [`ateng-boot-all`](../ateng-boot-all/README.md)。

---

## 2. 按业务场景的推荐组装配方

导入 `ateng-boot-bom` 后，可按微服务职责自由裁剪依赖，避免每个服务背负无关重型包：

### 2.1 无状态网关 / BFF 聚合服务（无数据库与缓存）

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

### 2.2 核心有状态业务微服务（订单 / 用户 / 交易）

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

### 2.3 企业 AI 智能体与知识库微服务

```xml
<dependencies>
    <dependency>
        <groupId>io.github.atengk</groupId>
        <artifactId>ateng-boot-web</artifactId>
    </dependency>
    <dependency>
        <groupId>io.github.atengk</groupId>
        <artifactId>ateng-boot-ai</artifactId>
    </dependency>
    <dependency>
        <groupId>io.github.atengk</groupId>
        <artifactId>ateng-boot-milvus</artifactId>
    </dependency>
    <dependency>
        <groupId>io.github.atengk</groupId>
        <artifactId>ateng-boot-redis</artifactId>
    </dependency>
</dependencies>
```

### 2.4 独立文件文档 / 定时调度工作服务

将重型报表文档（`ateng-boot-excel`、`ateng-boot-document`）、对象存储（`ateng-boot-storage`）或定时调度（`ateng-boot-job-xxl` / `ateng-boot-job-snail`）按需拆分至独立 Worker 节点，与高并发在线交易链路物理隔离。

---

## 3. 版本覆盖、Parent 共存与冲突排障

### 3.1 零继承绑架：兼容 `spring-boot-starter-parent` 与企业母 POM

采用 `<scope>import</scope>` 意味着你的项目**不需要把 `<parent>` 改成 `ateng-boot`**，原有的公司内部母 POM、插件配置与构建参数均可原封保留；`ateng-boot-bom` 本身仅对外提供版本仲裁清单。

### 3.2 局部覆盖某款三方组件版本

遵循 Maven **“先声明优先于后导入 `import`”** 的规则。当业务需要临时升级或锁定某款三方库版本时，只需将其声明在 `ateng-boot-bom` **之前**：

```xml
<dependencyManagement>
    <dependencies>
        <!-- 1. 自定义覆盖版本放在 BOM 导入之前，优先命中 -->
        <dependency>
            <groupId>org.redisson</groupId>
            <artifactId>redisson</artifactId>
            <version>3.44.0</version>
        </dependency>

        <!-- 2. 其余组件仍由 ateng-boot-bom 统一锁定 -->
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

### 3.3 与不同版本 `spring-boot-starter-parent` 共存

若你的工程继承了不同补丁/次版本的 `spring-boot-starter-parent`：

1. **优先采用 `ateng-boot-bom` 验证基线（推荐）**：将 `ateng-boot-bom` 放在其他 BOM 之前，或显式覆盖 `<spring-boot.version>3.3.13</spring-boot.version>`；
2. **优先跟随你自己的 Spring Boot 版本**：将你期望版本的 `org.springframework.boot:spring-boot-dependencies`（`import`）声明在 `ateng-boot-bom` **之前**。

### 3.4 高频依赖冲突定位命令

```bash
# 查看指定组件的实际版本来源与传递路径
mvn dependency:tree -Dincludes=com.fasterxml.jackson.core:jackson-annotations

# 查看最终合并生效的 dependencyManagement 与依赖解析树
mvn help:effective-pom -B
```

> 若引入了携带低版本 Netty、Servlet API 或旧版 Jackson 的历史内部 SDK，在对应 `<dependency>` 中使用 `<exclusions>` 剔除旧传递依赖，交由 BOM 锁定版本接管即可。

---

## 4. 基线兼容与全量纳管清单（附录）

### 4.1 核心运行时兼容基线

| 基础设施维度 | 锁定基线 | 说明 |
| :--- | :---: | :--- |
| **Java 编译与运行时** | **JDK 17 LTS**（兼容 **JDK 21+**） | 字节码目标 17；JDK 21+ 开启 `spring.threads.virtual.enabled=true` 即可启用虚拟线程 |
| **Spring Boot / Framework** | `3.3.13` / `6.1.x` | 全面采用 Jakarta EE 10（`jakarta.*`），不兼容 Spring Boot 2.x / `javax.*` 老旧依赖 |
| **Spring Cloud 生态兼容** | `2023.0.x` | 锁定 Spring Boot 3.3 LTS 基线，与 Spring Cloud 2023.0.x 及 Spring Cloud Alibaba 兼容 |
| **持久化与 SQL 解析** | MyBatis-Plus `3.5.17` | 对齐 `mybatis-plus-jsqlparser:3.5.17`，切勿手动引入低版本 `com.github.jsqlparser` |
| **鉴权与接口文档** | Sa-Token `1.46.0` / SpringDoc `2.6.0` | 原生适配 Spring Boot 3 与 Swagger UI 5.x |

### 4.2 基准导入 BOM 清单 (2 项)

| 基准 BOM 坐标 (`groupId:artifactId`) | 锁定版本 | 导入方式 (`<type>` / `<scope>`) | 对齐范围 |
| :--- | :---: | :---: | :--- |
| `org.springframework.boot:spring-boot-dependencies` | `3.3.13` | `pom` / `import` | 纳管 Spring Boot 3.3、Spring Framework 6.1、Jackson `2.17.3`、Netty、Micrometer 等基础运行时版本 |
| `software.amazon.awssdk:bom` | `2.25.70` | `pom` / `import` | 统一对齐 AWS SDK v2 S3 客户端与底层 HTTP / Auth / Regions 子构件版本 |

<details>
<summary><strong>展开查看全部 72 项显式纳管坐标（37 项 ateng-boot 模块 + 35 项第三方核心依赖）</strong></summary>

#### `ateng-boot` 全部分发构件 (37 项 = 1 聚合 + 36 Starter，版本随 BOM 同步)

| 领域分组 | 构件坐标 (`io.github.atengk:*`) | 职责定位 | 文档 |
| :--- | :--- | :--- | :--- |
| **核心聚合** | `io.github.atengk:ateng-boot-all` | 六大核心 Starter 一键传递聚合 JAR | [README](../ateng-boot-all/README.md) |
| **基础底座** | `io.github.atengk:ateng-boot-core` | `Result<T>`、`PageResult<T>`、`AsyncContext`、脱敏/加解密/枚举等原语 | [README](../ateng-boot-core/README.md) |
| **基础底座** | `io.github.atengk:ateng-boot-web` | `TraceFilter`、全局异常、`@Idempotent`、`@RateLimiter`、`@ApiSign` | [README](../ateng-boot-web/README.md) |
| **基础底座** | `io.github.atengk:ateng-boot-mybatis-plus` | 审计填充、行级多租户、`@DataScope`、`@FieldEncrypt`、动态查询与批量增强 | [README](../ateng-boot-mybatis-plus/README.md) |
| **基础底座** | `io.github.atengk:ateng-boot-redis` | 双核 Redis、`@DistributedLock`、防击穿缓存、L1+L2 缓存、延时队列与 Stream | [README](../ateng-boot-redis/README.md) |
| **基础底座** | `io.github.atengk:ateng-boot-excel` | Apache Fesod 流式导出、`@ExcelSensitive`、行级校验回写、AES 工作簿加密 | [README](../ateng-boot-excel/README.md) |
| **基础底座** | `io.github.atengk:ateng-boot-satoken` | `LoginHelper`、双轨拦截、L1+L2 权限缓存、多账号与 Same-Token 零信任 | [README](../ateng-boot-satoken/README.md) |
| **数据持久** | `io.github.atengk:ateng-boot-datasource` | SaaS 动态多数据源、主从读写分离、从库自愈与事务假切源守卫 | [README](../ateng-boot-datasource/README.md) |
| **数据持久** | `io.github.atengk:ateng-boot-flyway` | Flyway 10.x 数据库版本化迁移、生产守卫、加密隔离凭据与多源租户协同 | [README](../ateng-boot-flyway/README.md) |
| **存储文档** | `io.github.atengk:ateng-boot-storage` | Local 与 S3 统一对象存储、自适应分片流式上传、断点续传 | [README](../ateng-boot-storage/README.md) |
| **存储文档** | `io.github.atengk:ateng-boot-ftp` | FTP/FTPS 连接池治理、RFC 2640 防乱码、原子上传与目录同步 | [README](../ateng-boot-ftp/README.md) |
| **存储文档** | `io.github.atengk:ateng-boot-ssh` | SSH/SFTP 会话池、批量巡检、超时防孤儿进程、端口转发与 WebSSH | [README](../ateng-boot-ssh/README.md) |
| **存储文档** | `io.github.atengk:ateng-boot-document` | Word 模板引擎 (`poi-tl`) 与 PDF 水印/骑缝章/表单治理 (`PDFBox`) | [README](../ateng-boot-document/README.md) |
| **网络通信** | `io.github.atengk:ateng-boot-http` | Spring 6 `@HttpClient`、多命名客户端、`HttpTemplate`、SSE 与 mTLS | [README](../ateng-boot-http/README.md) |
| **网络通信** | `io.github.atengk:ateng-boot-realtime` | WebSocket 与 SSE 双规融合、分布式群组、离线重放与平滑停机 | [README](../ateng-boot-realtime/README.md) |
| **网络通信** | `io.github.atengk:ateng-boot-tcp` | Netty 4.1 双模长连接服务端/客户端、粘包拆包、mTLS 与跨节点桥接 | [README](../ateng-boot-tcp/README.md) |
| **网络通信** | `io.github.atengk:ateng-boot-mqtt` | MQTT v5 物联网双向通信、共享订阅、RPC 聚合应答与死信重放 | [README](../ateng-boot-mqtt/README.md) |
| **消息引擎** | `io.github.atengk:ateng-boot-rabbitmq` | RabbitMQ 可靠投递、`sendAfterCommit`、死信受控重放与 Quorum 拓扑 | [README](../ateng-boot-rabbitmq/README.md) |
| **消息引擎** | `io.github.atengk:ateng-boot-kafka` | Kafka 流批处理、死信重放、`@RetryableTopic` 与 Lag 位移观测 | [README](../ateng-boot-kafka/README.md) |
| **消息引擎** | `io.github.atengk:ateng-boot-rocketmq` | RocketMQ 5.x gRPC 客户端、2PC 分布式事务、SimpleConsumer 与死信治理 | [README](../ateng-boot-rocketmq/README.md) |
| **可观测运维** | `io.github.atengk:ateng-boot-observability` | K8s 探针硬分级隔离、`AbstractSafeHealthIndicator`、业务度量与端点守卫 | [README](../ateng-boot-observability/README.md) |
| **可观测运维** | `io.github.atengk:ateng-boot-openapi` | SpringDoc OpenAPI 3.0 契约治理、五模安全守卫、枚举转换与离线导出 | [README](../ateng-boot-openapi/README.md) |
| **可观测运维** | `io.github.atengk:ateng-boot-admin` | Spring Boot Admin 监控看板、Sa-Token 守卫、SSRF 注册防火墙 | [README](../ateng-boot-admin/README.md) |
| **通知触达** | `io.github.atengk:ateng-boot-mail` | 出站 SMTP 多账号、国际化模板、批量合并、测试沙箱与延迟补偿 | [README](../ateng-boot-mail/README.md) |
| **通知触达** | `io.github.atengk:ateng-boot-notification` | 企微/钉钉/飞书 Webhook 机器人推送、`@NotifyAlert` 防风暴与故障转移 | [README](../ateng-boot-notification/README.md) |
| **通知触达** | `io.github.atengk:ateng-boot-sms` | 零 SDK 第三方多渠道短信、负载均衡熔断与单日预算硬切断 | [README](../ateng-boot-sms/README.md) |
| **安全交易** | `io.github.atengk:ateng-boot-pay` | 微信支付 v3 与支付宝官方 SDK 统一/专有收单门面、异步通知防重网关 | [README](../ateng-boot-pay/README.md) |
| **安全交易** | `io.github.atengk:ateng-boot-social` | OAuth2 社会化登录、两阶段绑定票据与 Sa-Token 多端会话签发 | [README](../ateng-boot-social/README.md) |
| **安全交易** | `io.github.atengk:ateng-boot-encrypt` | 配置透明加解密、AES/RSA/SM2/SM4、原地回写 CLI 与 `/env` 掩码 | [README](../ateng-boot-encrypt/README.md) |
| **调度流程** | `io.github.atengk:ateng-boot-statemachine` | 高性能无状态业务状态机 DSL、CAS 跃迁、事务同步与拓扑图导出 | [README](../ateng-boot-statemachine/README.md) |
| **调度流程** | `io.github.atengk:ateng-boot-liteflow` | LiteFlow 规则与 DAG 编排、受管并行线程池、静态预检与快照回滚 | [README](../ateng-boot-liteflow/README.md) |
| **调度流程** | `io.github.atengk:ateng-boot-job-local` | 单机定时任务、四阶重叠防卫、硬超时中断与轻量集群互斥锁 | [README](../ateng-boot-job-local/README.md) |
| **调度流程** | `io.github.atengk:ateng-boot-job-xxl` | XXL-Job 执行器加固、受管线程池与四维透传、大数据分片与排空治理 | [README](../ateng-boot-job-xxl/README.md) |
| **调度流程** | `io.github.atengk:ateng-boot-job-snail` | SnailJob 调度与分布式重试加固、本地看门狗熔断与脱网测试辅助 | [README](../ateng-boot-job-snail/README.md) |
| **搜索智能** | `io.github.atengk:ateng-boot-search` | Elasticsearch 8.x & OpenSearch 双引擎检索、流式批量与 Search-After | [README](../ateng-boot-search/README.md) |
| **搜索智能** | `io.github.atengk:ateng-boot-ai` | LangChain4j & LangGraph4j 智能体编排、RAG 溯源、HITL 审批与配额风控 | [README](../ateng-boot-ai/README.md) |
| **搜索智能** | `io.github.atengk:ateng-boot-milvus` | Milvus 向量库运维、多向量混合检索与多租户 PartitionKey 隔离 | [README](../ateng-boot-milvus/README.md) |

#### 纳管核心第三方中间件坐标清单 (35 项)

| 归属 Starter | 第三方坐标 (`groupId:artifactId`) | 锁定版本 | 特殊处理 / 说明 |
| :--- | :--- | :---: | :--- |
| `mybatis-plus` | `com.baomidou:mybatis-plus-spring-boot3-starter` | `3.5.17` | 与 Core / Web / Datasource 基线强对齐 |
| `mybatis-plus` | `com.baomidou:mybatis-plus-jsqlparser` | `3.5.17` | 支撑行级多租户、数据权限与逻辑删除 AST 改写 |
| `redis` | `org.redisson:redisson` | `3.44.0` | 支撑分布式锁、布隆过滤器、延时队列与 Stream |
| `excel` | `org.apache.fesod:fesod-sheet` | `2.1.0-incubating` | Apache Fesod 流式表格内核 |
| `satoken` | `cn.dev33:sa-token-spring-boot3-starter` | `1.46.0` | Jakarta EE / Spring Boot 3 核心鉴权底座 |
| `satoken` | `cn.dev33:sa-token-spring-aop` | `1.46.0` | 注解鉴权切面支撑 |
| `openapi` | `org.springdoc:springdoc-openapi-starter-webmvc-ui` | `2.6.0` | OpenAPI 3.0 Schema 与 Swagger UI 5.x |
| `storage` | `software.amazon.awssdk:s3` | `2.25.70` | 显式排除 `software.amazon.awssdk:netty-nio-client`（避免冗余 Netty 冲突） |
| `ftp` | `commons-net:commons-net` | `3.11.1` | 纯净 FTP/FTPS 传输协议栈 |
| `ssh` | `org.apache.sshd:sshd-core` | `2.14.0` | Apache MINA SSHD 协议核心 |
| `ssh` | `org.apache.sshd:sshd-sftp` | `2.14.0` | Apache MINA SSHD SFTP 子系统 |
| `document` | `com.deepoove:poi-tl` | `1.12.2` | Word 模板 AST 渲染引擎 |
| `document` | `org.apache.pdfbox:pdfbox` | `3.0.4` | PDF 水印、切片与表单治理内核 |
| `document` / `excel` / `ai` | `commons-io:commons-io` | `2.20.0` | 底层流式与办公文档操作支持，统一版本消除 POI 与旧依赖冲突 |
| `mqtt` | `org.eclipse.paho:org.eclipse.paho.mqttv5.client` | `1.2.5` | Eclipse Paho MQTT 5.0 官方客户端 |
| `rocketmq` | `org.apache.rocketmq:rocketmq-client-java` | `5.2.2` | RocketMQ 5.x gRPC 瘦客户端 |
| `datasource` | `com.baomidou:dynamic-datasource-spring-boot3-starter` | `4.6.0` | 动态数据源多库路由核心 |
| `pay` | `com.github.wechatpay-apiv3:wechatpay-java` | `0.2.17` | 微信支付 API v3 官方 Java SDK |
| `pay` | `com.alipay.sdk:alipay-sdk-java` | `4.40.1044.ALL` | 支付宝开放平台官方 Java SDK |
| `admin` | `de.codecentric:spring-boot-admin-starter-server` | `3.3.4` | Spring Boot Admin 管控端 |
| `admin` | `de.codecentric:spring-boot-admin-starter-client` | `3.3.4` | Spring Boot Admin 被监控客户端 |
| `social` | `org.dromara.anduin:dromara-anduin` | `2.3.0` | OAuth2 多平台授权统一底层 |
| `social` / `bom` | `org.apache.commons:commons-lang3` | `3.18.0` | 覆写 Spring Boot 默认基线 3.14.0，满足 `dromara-anduin` 运行期要求（向下全兼容） |
| `social` / `bom` | `com.fasterxml.jackson.core:jackson-annotations` | `2.22` | 覆写 Spring Boot 默认基线 2.17.3，兼容注解解析（向下全兼容） |
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

</details>
