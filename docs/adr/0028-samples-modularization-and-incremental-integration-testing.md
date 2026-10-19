# 28. 示例工程多模块解耦与精准增量集成测试架构决策 (Samples Modularization and Incremental Integration Testing)

## 背景与痛点

在框架演进早期，`ateng-boot` 将所有的特性演示与端到端集成测试统一收敛在单体模块 `ateng-boot-samples` 中，并直接依赖全家桶 Starter（`ateng-boot-all`）。随着框架支持的中间件组件（Web、MyBatis-Plus、Redis、Excel、Sa-Token 等）持续扩充，这种“单一胖样例（Fat Sample Module）”工程架构暴露出三大不可忽视的瓶颈与痛点：

1. **测试反馈周期急剧膨胀**：
   - 每次运行集成测试都需要加载包含全套中间件的“全家桶大容器”，本地与 CI 单次测试耗时突破 1 分多钟；对于单点模块的微小改动，开发者必须承受漫长的等待，严重破坏了日常开发的心流与快速迭代节奏。
2. **场景过度耦合与开发者认知负担过重**：
   - 演示用例彼此交织。当下游开发者只想参考“纯粹的 Redis 分布式锁”或“纯粹的 Excel 流式导出”时，必须连带引入 MySQL/H2、Sa-Token、OpenAPI 等不相关的类库、表结构和配置，无法获得开箱即用的“最小可用代码块（Minimum Viable Snippet）”。
3. **未来中间件横向扩展受阻**：
   - 随着后续 Storage (MinIO/OSS)、消息队列 (Kafka/RocketMQ)、分布式调度 (Job) 等新模块的规划孵化，若继续塞入单体样例，必将引发严峻的依赖地狱、类路径冲突、本地服务端口与资源争抢，甚至在 CI 自动化构建中诱发内存溢出（OOM）。

---

## 核心架构与决策内容

经由 `/grill-with-docs` 架构推演，正式确立**“示例工程多模块解耦拆分与精准增量集成测试”**架构方案。

```mermaid
flowchart TD
    subgraph RepoRoot["ateng-boot (Root Reactor)"]
        direction TB

        subgraph Aggregator["ateng-boot-samples (二级 Parent POM, packaging=pom)"]
            direction TB
            CORE_SMP["ateng-boot-sample-core<br/>(Core 原语: 序列化 / 脱敏 / 上下文 / 重试切片)"]
            WEB_SMP["ateng-boot-sample-web<br/>(Web 契约: Result / 校验 / 异常 / MDC / 验签)"]
            MP_SMP["ateng-boot-sample-mybatis-plus<br/>(持久层: 实体阶梯 / 租户 / 权限 / 动态查询)"]
            REDIS["ateng-boot-sample-redis<br/>(专一场景: 分布式锁 / 限流 / 延时队列)"]
            EXCEL["ateng-boot-sample-excel<br/>(专一场景: 流式导出 / 动态脱敏 / 批注回写)"]
            SATOKEN["ateng-boot-sample-satoken<br/>(专一场景: 认证鉴权 / 路由白名单 / 审计流转)"]
            STORAGE["ateng-boot-sample-storage<br/>(专一场景: 本地与S3存储 / 断点续传 / ZIP打包)"]
            SMOKE["ateng-boot-sample-smoke-test<br/>(CI 准出门禁: 全家桶装配 / 冒烟集成)"]
        end

        CORE_STARTER["ateng-boot-core"]
        WEB_STARTER["ateng-boot-web"]
        MP_STARTER["ateng-boot-mybatis-plus"]
        REDIS_STARTER["ateng-boot-redis"]
        EXCEL_STARTER["ateng-boot-excel"]
        SATOKEN_STARTER["ateng-boot-satoken"]
        STORAGE_STARTER["ateng-boot-storage"]
        ALL_STARTER["ateng-boot-all (全家桶 Starter)"]

        CORE_STARTER -->|1:1 专属自验| CORE_SMP
        WEB_STARTER -->|1:1 专属自验| WEB_SMP
        MP_STARTER -->|1:1 专属自验| MP_SMP
        REDIS_STARTER -->|1:1 专属自验| REDIS
        EXCEL_STARTER -->|1:1 专属自验| EXCEL
        SATOKEN_STARTER -->|1:1 专属自验| SATOKEN
        STORAGE_STARTER -->|1:1 专属自验| STORAGE
        ALL_STARTER -->|聚合装配| SMOKE
    end
```

### 1. 二级 Parent POM 聚合与物理目录 1:1 对齐
- `ateng-boot-samples` 调整为二级聚合 Parent POM（`<packaging>pom</packaging>`），统一纳管子模块公共测试依赖（`spring-boot-starter-test`、`lombok`）与打包插件配置；
- 下属各场景子模块严格遵循 `ateng-boot-sample-<scenario>` 命名规范，物理目录名称与 `artifactId` 保持 1:1 强一致；
- 子模块继承 `ateng-boot-samples`，免去重复配置版本号与公共编译插件。

### 2. 坚持零共享代码（Zero-Shared-Code）不变量
- **严禁创建 `sample-common` 公共抽离子模块**：坚决杜绝因盲目抽象公共代码而重新引入隐式耦合；
- **100% 自包含（Self-Contained）**：各子模块拥有独立的 `Application` 启动入口、`application.yml` 配置、最小领域模型与专有集成测试用例，确保开发者对任意一个子模块均可“直接复制、零修改即用”。

### 3. 全模块 1:1 专属场景子模块矩阵与职责边界
彻底拆分并废除早期混合型 `ateng-boot-sample-basic` 大杂烩，确立业务 Starter 与自验 Sample 严格 1:1 映射矩阵：
1. **`ateng-boot-sample-core`（Core 底座原语场景）**：
   - **依赖基线**：`ateng-boot-core`；
   - **职责覆盖**：通用数据脱敏/掩码（`DesensitizeUtil`）、原生高精度序列化（`JsonUtil`）、四维跨线程上下文透传与快照（`AsyncContext` / `ContextCopyingTaskDecorator`）、通用业务语义断言（`BusinessAssert`）、退避重试（`RetryUtil`）、分批切片（`CollectionUtil` / `BatchUtil`）等，纯 Java 内存极速自验。
2. **`ateng-boot-sample-web`（Web 运行时契约场景）**：
   - **依赖基线**：`ateng-boot-web`；
   - **职责覆盖**：强类型 `Result<T>` 显式响应契约、分层 JSR-303 与 `ValidList<E>` 语法级 Fail-Fast 校验、全局异常转译拦截器、TraceFilter 与 MDC 全链路追踪、`@ApiSign` 双向加签验签防重放、虚拟线程无缝支持等，**实现零数据库依赖（Zero DB Dependency）**。
3. **`ateng-boot-sample-mybatis-plus`（持久层增强场景）**：
   - **依赖基线**：`ateng-boot-mybatis-plus`、`h2`；
   - **职责覆盖**：BaseEntity 五级实体继承阶梯链、JSqlParser 行级多租户安全隔离、细粒度数据权限过滤、持久层 `@FieldEncrypt` 透明加解密、`DynamicQuery` 动态条件查询与 URL 安全转译、动态表名、高性能分批批量插入与防死锁批量更新。
4. **`ateng-boot-sample-redis`（专一 Redis 协同场景）**：
   - **依赖基线**：`ateng-boot-redis`、`ateng-boot-web`；
   - **职责覆盖**：`@DistributedLock` 声明式分布式锁、`@RateLimiter` 接口限流防刷、`@Idempotent` 幂等防重、Redisson 双模延时队列、原子 RedisUtil 工具演示；**实现零数据库依赖（Zero DB Dependency）**。
5. **`ateng-boot-sample-excel`（专一流式 Excel 场景）**：
   - **依赖基线**：`ateng-boot-excel`、`ateng-boot-web`；
   - **职责覆盖**：基于 Apache Fesod 的大批量流式导出、`@ExcelSensitive` 视图导出动态脱敏（严守 ADR-0012 内存真明文）、JSR-303 导入行级校验与红字批注错误回写（`ExcelFeedbackUtil`）、智能防呆模板生成；**实现零数据库依赖（Zero DB Dependency）**。
6. **`ateng-boot-sample-satoken`（专一 Sa-Token 鉴权与会话治理场景）**：
   - **依赖基线**：`ateng-boot-satoken`、`ateng-boot-mybatis-plus`、`ateng-boot-web`、`h2`；
   - **职责覆盖**：用户登录注销、Sa-Token 路由拦截器与白名单放行、Service 层 `@SaCheckRole` / `@SaCheckPermission` 方法级鉴权、自适应内存存储降级，以及跨框架自动打通操作人与租户至底层 MyBatis-Plus 审计全链路。
7. **`ateng-boot-sample-storage`（专一多源对象存储场景）**：
   - **依赖基线**：`ateng-boot-storage`、`ateng-boot-web`；
   - **职责覆盖**：本地文件驱动与 S3 兼容协议适配、路径遍历清洗防卫、Spring Resource 极简断点续传、纯流式 ZIP 打包与大文件分片状态机自验。
8. **`ateng-boot-sample-smoke-test`（全家桶 CI 冒烟准出门禁）**：
   - **依赖基线**：`ateng-boot-all`、`h2`、`springdoc-openapi`；
   - **职责覆盖**：专职负责检验全量 Starter 无冲突自动装配与典型跨模块端到端主链路，测试执行耗时严格压制在 8 秒以内。

### 4. 功能必有样例与 Sample-First 研发闭环铁律 (Sample-Before-Docs Invariant)
1. **功能 100% 覆盖刚性铁律**：模块实现的每一个对外公开功能（门面 API、注解、配置项、SPI），必须 100% 在归属的 `ateng-boot-sample-<scenario>` 中提供真实、自洽、端到端可运行的演示用例，严禁只在单测中 Mock 而在 Sample 中缺位；
   - **分层呈现规范**：Web 边界特性暴露 `@RestController` + `MockMvc`；组件底座与持久层特性编写 `ScenarioService` + `@SpringBootTest` 端到端集成测试，杜绝反模式包装；
2. **Sample-First 研发流程**：在编写或修改子模块 `README.md` 前，**必须且只能先在对应 Sample 中写出端到端场景并全部跑通测试**；
3. **全真逆向提取**：文档中的 Java 示例、YAML 片段与预期输出，必须 100% 逆向提取自已跑通的 Sample 源码与控制台日志，杜绝凭空手敲文档。

### 5. 三阶梯测试准出与精准增量集成测试规约
结合全模块 1:1 矩阵，在 `AGENTS.md` 中升级阶梯二测试准出规约：
- **定向联动公式**：`mvn test -pl <changed-module>,ateng-boot-samples/ateng-boot-sample-<scenario> -am -T 1C -nsu`；
- **智能分流机制**：修改单一中间件（如 `ateng-boot-redis`）仅需联动回归对应的 `ateng-boot-sample-redis`，将单次准出验收耗时从 1 分多钟大幅压缩至 5~10 秒；仅在涉及全家桶打包或底层重大重构时，触发 `ateng-boot-sample-smoke-test` 冒烟验收。

### 6. Maven Central 发布与打包双层防御性隔离
- **生命周期跳过**：在 `ateng-boot-samples/pom.xml` 中配置 `<properties><maven.deploy.skip>true</maven.deploy.skip></properties>`，所有场景子模块自动继承；
- **反应堆打包排除**：在根 `pom.xml` 的 `central-publishing-maven-plugin` 中将 `ateng-boot-samples` 以及所有 `ateng-boot-sample-*` 显式加入 `<excludeArtifacts>`，彻底杜绝自验子模块意外进入 Staging Bundle 导致发布校验被拒。

---

## 后果与权衡 (Consequences & Trade-offs)

### 正向收益 (Positive)
1. **测试反馈秒级达成**：日常特性变更测试由 1 分多钟缩减至 5~10 秒，构建吞吐量提升近 10 倍；
2. **极简开发者上手体验**：每个样例子工程均为极简独立沙箱，零无关依赖，直接阅读运行，开箱即用；
3. **无限平滑的中间件扩展性**：未来新增 Storage、MQ、Job 等任意新 Starter 时，仅需同级挂载一个专属场景子模块即可，彼此零耦合、零互扰；
4. **发布管道安全自洽**：双层排除守卫确保中央仓库发布 100% 纯净化。

### 妥协成本 (Trade-offs)
1. **模块与 POM 数量增加**：拆分为 5 个子工程后，工程内 POM 数量有所增加；通过二级 Parent POM 统一纳管通用依赖与插件配置，有效抑制了维护成本；
2. **自包含带来的微量重复代码**：为保障零耦合（Zero-Shared-Code），部分简单测试实体（如极简的 Order/User）在个别子模块中独立声明；这种微量重复是保障“复制即用”和“模块彻底隔离”所付出的可控且必要的成本。
