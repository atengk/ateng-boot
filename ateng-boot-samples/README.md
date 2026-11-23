# ateng-boot-samples 示例与自验集成工程矩阵

> 基于二级 Parent POM 聚合解耦、100% 自包含场景子模块与轻量内存闭环的端到端自动化测试金字塔与工程化最佳实践矩阵。

---

## 1. 架构定位与解耦矩阵（全量 37 个场景子模块）

`ateng-boot-samples` 是 `ateng-boot` 框架的官方自验工程与消费端最佳实践示范矩阵。遵循 [ADR-0028](../docs/adr/0028-samples-modularization-and-incremental-integration-testing.md)，工程全面采用多模块解耦架构：**坚决杜绝胖单体与公共抽离（Zero-Shared-Code），各场景子模块 100% 自包含、开箱即用、一键复制**，并严格满足 `ateng-boot-<module> ↔ ateng-boot-sample-<module>`（及 `ateng-boot-all ↔ ateng-boot-sample-smoke-test`）1:1 映射。

| 子模块目录 | 核心定位与场景覆盖 | 关键技术栈 | 依赖数据库 | 专属说明 |
| :--- | :--- | :--- | :---: | :---: |
| **`ateng-boot-sample-core`** | **通用原语与基础工具底座**<br/>脱敏注解/策略、Jackson 序列化、四维上下文透传、退避重试、切片分批、发号与日志引擎 | Core / Web | **零 DB 依赖** | [查看](./ateng-boot-sample-core/README.md) |
| **`ateng-boot-sample-web`** | **Web 契约层与全链路追踪**<br/>显式强类型契约、分层参数校验、异常脱敏、双向加签、自适应限流、幂等防重与操作日志 | Web / JSR-303 / Jackson | **零 DB 依赖** | [查看](./ateng-boot-sample-web/README.md) |
| **`ateng-boot-sample-mybatis-plus`** | **持久层增强双领域模型**<br/>双轨实体阶梯与正交契约、审计填充、占位软删、透明加密与明文保持、多租户、数据权限、批量 Upsert 与流式游标 | MyBatis-Plus / H2 / Web | H2 内存库 | [查看](./ateng-boot-sample-mybatis-plus/README.md) |
| **`ateng-boot-sample-datasource`** | **动态多数据源与物理隔离**<br/>SaaS 物理多租户分库、主从读写分离、从库故障熔断自愈、写后即读防延迟与热重载 | Datasource / Web / H2 | H2 多内存库 | [查看](./ateng-boot-sample-datasource/README.md) |
| **`ateng-boot-sample-flyway`** | **Flyway 版本化迁移与多源协同**<br/>主从锁主库迁移、静态多库隔离迁移、SaaS 物理租户并发与动态建表、`clean` 熔断与 SM4 加密凭据 | Flyway / Datasource / MP / Web | H2 5 内存库 | [查看](./ateng-boot-sample-flyway/README.md) |
| **`ateng-boot-sample-redis`** | **专一 Redis 分布式协同 Demo**<br/>`@DistributedLock` 分布式锁、`@RedisIdempotent` 幂等防重、防击穿缓存、L1+L2 缓存、延时队列与 Stream | Redis / Redisson / Web | **零 DB 依赖** | [查看](./ateng-boot-sample-redis/README.md) |
| **`ateng-boot-sample-search`** | **双引擎全文检索与分析 Demo**<br/>Elasticsearch 8.x & OpenSearch 统一检索、流式批量、高亮投影、Search-After 深度分页与零停机别名切换 | Search / Web | **零 DB 依赖** | [查看](./ateng-boot-sample-search/README.md) |
| **`ateng-boot-sample-milvus`** | **向量数据库与混合检索 Demo**<br/>`MilvusClientV2` 集合与分区运维、稠密/稀疏多向量混合检索（RRF/Weighted）与租户物理隔离 | Milvus / Web | **零 DB 依赖** | [查看](./ateng-boot-sample-milvus/README.md) |
| **`ateng-boot-sample-satoken`** | **专一 Sa-Token 鉴权与会话 Demo**<br/>登录注销、路由白名单、`@SaCheckRole`、L1+L2 权限缓存、多账号与审计人/租户全链路打通 | Sa-Token / Redis / MP / H2 | H2 内存库 | [查看](./ateng-boot-sample-satoken/README.md) |
| **`ateng-boot-sample-social`** | **第三方社会化登录与 OAuth2 Demo**<br/>多平台 OAuth2 授权跳转、两阶段绑定票据、`directLogin`、多租户应用配置与 Sa-Token 会话联动 | Social / Sa-Token / Web | **零 DB 依赖** | [查看](./ateng-boot-sample-social/README.md) |
| **`ateng-boot-sample-encrypt`** | **配置安全加解密 Demo**<br/>环境属性透明懒解密、AES/RSA/SM2/SM4 编程式加解密、多 Key 别名路由与 `/env` 掩码防窥探 | Encrypt / Web | **零 DB 依赖** | [查看](./ateng-boot-sample-encrypt/README.md) |
| **`ateng-boot-sample-http`** | **声明式与命令式 HTTP 客户端 Demo**<br/>`@HttpClient` 声明式代理、`fallback` 降级、`@HttpTimeout`、`RestClientRegistry`、SSE 流消费与 mTLS | Http / Web | **零 DB 依赖** | [查看](./ateng-boot-sample-http/README.md) |
| **`ateng-boot-sample-realtime`** | **WebSocket 与 SSE 实时通信 Demo**<br/>全双工 WebSocket、声明式 `@RealtimeAction` RPC、分布式群组治理、离线重放与平滑停机 | Realtime / Redis / Web | **零 DB 依赖** | [查看](./ateng-boot-sample-realtime/README.md) |
| **`ateng-boot-sample-tcp`** | **工业物联网 Netty TCP 长连接 Demo**<br/>Server/Client 双模长连接、粘包拆包、双向 RPC、`@TcpMapping`、高低水位背压与跨节点桥接 | Tcp / Redis / Web | **零 DB 依赖** | [查看](./ateng-boot-sample-tcp/README.md) |
| **`ateng-boot-sample-mqtt`** | **物联网 MQTT 5.0 通信 Demo**<br/>主题通配订阅、`$share` 共享订阅、MQTT 5.0 同步 RPC、死信受控重放与设备上下线观测 | Mqtt / Web | **零 DB 依赖** | [查看](./ateng-boot-sample-mqtt/README.md) |
| **`ateng-boot-sample-rabbitmq`** | **RabbitMQ 可靠投递与死信治理 Demo**<br/>Confirms/Returns、`sendAfterCommit`、批量并发发信、死信重放、动态流控与 Quorum 拓扑 | RabbitMQ / Web | **零 DB 依赖** | [查看](./ateng-boot-sample-rabbitmq/README.md) |
| **`ateng-boot-sample-kafka`** | **Kafka 高吞吐流批与位移治理 Demo**<br/>可靠发信、`sendAfterCommit`、`@RetryableTopic` 非阻塞重试、死信重放、Lag 监控与批量多租户隔离 | Kafka / Web | **零 DB 依赖** | [查看](./ateng-boot-sample-kafka/README.md) |
| **`ateng-boot-sample-rocketmq`** | **RocketMQ 5.x 原生 2PC 事务与 gRPC Demo**<br/>`RocketmqTemplate`、2PC 半消息回查、`sendAfterCommit`、延时撤回、`SimpleConsumer` 与死信治理 | RocketMQ / Web | **零 DB 依赖**（测试含 H2 事务桩） | [查看](./ateng-boot-sample-rocketmq/README.md) |
| **`ateng-boot-sample-storage`** | **多源对象存储与文件清洗 Demo**<br/>Local 与 S3/MinIO 适配、魔数嗅探、1-based 流式分片上传、断点续传、ZIP 打包与预签名直链 | Storage / Web | **零 DB 依赖** | [查看](./ateng-boot-sample-storage/README.md) |
| **`ateng-boot-sample-excel`** | **流式 Excel 处理与安全加密 Demo**<br/>Apache Fesod 流式导出、`@ExcelSensitive` 脱敏、导入校验红字批注回写、下拉模板、多 Sheet 与 AES 加密 | Excel / Web | **零 DB 依赖** | [查看](./ateng-boot-sample-excel/README.md) |
| **`ateng-boot-sample-document`** | **Word 模板与 PDF 综合治理 Demo**<br/>`poi-tl` Word 渲染拼装与只读锁、PDF 暗水印、关键字盖章、跨页骑缝章、物理涂黑脱敏与 AcroForm 扁平化 | Document / Web | **零 DB 依赖** | [查看](./ateng-boot-sample-document/README.md) |
| **`ateng-boot-sample-ftp`** | **FTP/FTPS 远程文件传输与切源 Demo**<br/>连接池化、双重目录复位、`FtpContextHolder` 动态切源、`.tmp` 原子上传、断点续传与全目录同步 | Ftp / Web | **零 DB 依赖** | [查看](./ateng-boot-sample-ftp/README.md) |
| **`ateng-boot-sample-ssh`** | **SSH 远程执行与 SFTP 传输 Demo**<br/>高危命令拦截、有界输出、多节点并发巡检、sudo 提权、SFTP 目录同步、端口转发与 WebSSH | Ssh / Web | **零 DB 依赖** | [查看](./ateng-boot-sample-ssh/README.md) |
| **`ateng-boot-sample-job-local`** | **单机定时任务与轻量集群锁 Demo**<br/>声明式 `@LocalJob`、四阶重叠并发防卫、硬超时中断降级、多租户并发与轻量互斥锁 | Job-Local / Notification / Web | **零 DB 依赖** | [查看](./ateng-boot-sample-job-local/README.md) |
| **`ateng-boot-sample-job-xxl`** | **XXL-Job 分布式调度加固 Demo**<br/>`@AtengXxlJob`、四维上下文透传、日志镜像、分片流式批处理、看门狗熔断与 K8s 三阶排空 | Job-XXL / Notification / Web | **零 DB 依赖** | [查看](./ateng-boot-sample-job-xxl/README.md) |
| **`ateng-boot-sample-job-snail`** | **SnailJob 调度与协同重试双擎 Demo**<br/>脱网单测助手 `SnailJobTestHelper`、分片流式切片、`TenantJobTemplate`、`SnailRetryTemplate` 与三阶排空 | Job-Snail / Notification / Web | **零 DB 依赖** | [查看](./ateng-boot-sample-job-snail/README.md) |
| **`ateng-boot-sample-statemachine`** | **无状态业务状态机 DSL Demo**<br/>Fluent DSL 跃迁拓扑、只读预检、CAS 并发安全流转、`performAfterCommit` 与 Mermaid 拓扑图导出 | StateMachine / MP / Web / H2 | H2 内存库 | [查看](./ateng-boot-sample-statemachine/README.md) |
| **`ateng-boot-sample-liteflow`** | **LiteFlow 规则编排与流程管道 Demo**<br/>电商算价与金融风控 DAG 编排、受管 `WHEN` 并行池、静态 EL 预检、历史回滚与 Saga 反向补偿 | LiteFlow / Web | **零 DB 依赖** | [查看](./ateng-boot-sample-liteflow/README.md) |
| **`ateng-boot-sample-pay`** | **企业级多渠道支付收单 Demo**<br/>微信支付 v3 / 支付宝双规驱动、统一收单门面 `PayTemplate`、分账与全生命周期 Webhook 验签防重 | Pay / Web | **零 DB 依赖** | [查看](./ateng-boot-sample-pay/README.md) |
| **`ateng-boot-sample-sms`** | **多渠道零 SDK 短信与配额防线 Demo**<br/>15 大内建驱动路由、平滑加权熔断、测试沙箱、单渠道日配额熔断、延时发送与验证码防暴破 | Sms / Web | **零 DB 依赖** | [查看](./ateng-boot-sample-sms/README.md) |
| **`ateng-boot-sample-mail`** | **工业级邮件与批量合并通知 Demo**<br/>多账号路由、国际化模板、片内连接复用批量合并、测试沙箱、`testConnection` 与事务后补偿 | Mail / Observability / Web | **零 DB 依赖** | [查看](./ateng-boot-sample-mail/README.md) |
| **`ateng-boot-sample-notification`** | **企业协同机器人 Webhook 推送 Demo**<br/>企微/钉钉/飞书跨平台卡片转译、`@NotifyAlert` 三阶防风暴聚合、多通道并发广播与容灾切换 | Notification / Web | **零 DB 依赖** | [查看](./ateng-boot-sample-notification/README.md) |
| **`ateng-boot-sample-ai`** | **AI 智能体与 RAG 认知图 Demo**<br/>`@AiAgent` 工具沙箱、双级语义缓存、动态提示词、RAG 混合检索溯源、MCP/OpenAPI、人工审批与并行业务流 | AI / Milvus / Storage / Document / Web | **零 DB 依赖** | [查看](./ateng-boot-sample-ai/README.md) |
| **`ateng-boot-sample-openapi`** | **OpenAPI 接口契约治理与安全守卫 Demo**<br/>SpringDoc 3.0 枚举字典增强、`ValidList` 保真、五模访问守卫、CIDR 防火墙与离线契约导出 | OpenAPI / Web | **零 DB 依赖** | [查看](./ateng-boot-sample-openapi/README.md) |
| **`ateng-boot-sample-observability`** | **云原生可观测性与探针熔断 Demo**<br/>K8s Liveness/Readiness 硬隔离、异步安全探针、`MetricsTemplate` / `@ObservedMetric` 与端点防护 | Observability / MP / Storage / Web / H2 | H2 内存库 | [查看](./ateng-boot-sample-observability/README.md) |
| **`ateng-boot-sample-admin`** | **Spring Boot Admin 运维监控看板 Demo**<br/>Sa-Token 登录 RBAC 守卫、双层 SSRF 防火墙、端点守卫、防抖告警静默与真实 Client 闭环 | Admin / Observability / Notification / Web | **零 DB 依赖** | [查看](./ateng-boot-sample-admin/README.md) |
| **`ateng-boot-sample-smoke-test`** | **全家桶 CI 冒烟准出门禁**<br/>`ateng-boot-all` 核心全家桶与代表性扩展 Starter 零冲突装配及跨模块闭环验真 | All / OpenAPI / Storage / Http / Realtime / Encrypt / StateMachine / Tcp / H2 | H2 内存库 | [查看](./ateng-boot-sample-smoke-test/README.md) |

---

## 2. 本地一键启动体验

每个场景子模块均为独立的 Spring Boot Web 应用，可按需单独启动（内置离线桩，无需外部中间件即可运行；如需连接真实中间件，参考各自 `application-local.example.yml` 复制配置后附带 `-Dspring-boot.run.profiles=local` 启动）：

```bash
# 示例 1：全家桶多 Starter 协同与契约冒烟演示（内置 H2 + OpenAPI /docs）
mvn -pl ateng-boot-samples/ateng-boot-sample-smoke-test spring-boot:run

# 示例 2：Web 契约层与全链路追踪演示
mvn -pl ateng-boot-samples/ateng-boot-sample-web spring-boot:run

# 示例 3：持久层增强双领域模型演示（内置 H2 内存库）
mvn -pl ateng-boot-samples/ateng-boot-sample-mybatis-plus spring-boot:run

# 示例 4：AI 智能体、RAG 混合检索与认知图演示
mvn -pl ateng-boot-samples/ateng-boot-sample-ai spring-boot:run

# 示例 5：任意其他模块（将 <module> 替换为上表中的模块名）
mvn -pl ateng-boot-samples/ateng-boot-sample-<module> spring-boot:run
```

---

## 3. 三阶梯精准增量测试门禁

日常开发遵循 `AGENTS.md` 规定的三阶梯测试铁律，**改动哪个中间件模块，仅定向联动回归对应的 1:1 Sample 子模块**：

```bash
# 通用定向回归模板（将 <module> 替换为实际改动模块名，如 core / web / redis / ai）
mvn test -pl ateng-boot-<module>,ateng-boot-samples/ateng-boot-sample-<module> -am -T 1C -nsu -B

# 底层核心（core / web）改动时，同步追加全家桶冒烟门禁回归
mvn test -pl ateng-boot-core,ateng-boot-samples/ateng-boot-sample-core,ateng-boot-samples/ateng-boot-sample-smoke-test -am -T 1C -nsu -B

# 发版前或全家桶校验
mvn test -pl ateng-boot-all,ateng-boot-samples/ateng-boot-sample-smoke-test -am -T 1C -nsu -B
```

---

## 4. 架构设计与发布隔离

- **零共享代码（Zero-Shared-Code）**：坚决杜绝 `sample-common` 公共抽离层，彻底杜绝跨场景隐式耦合，每个 Demo 都是完全自治的最小参考实现；
- **发布双层排除隔离**：二级 POM 统一注入 `<maven.deploy.skip>true</maven.deploy.skip>`，且根 `pom.xml` 发布插件在 `<excludeArtifacts>` 显式排除所有 sample 构件，保障 Maven Central 发布绝对安全（参见 [ADR-0007](../docs/adr/0007-maven-publishing-and-clean-ci-pipeline.md)、[ADR-0028](../docs/adr/0028-samples-modularization-and-incremental-integration-testing.md)）。
