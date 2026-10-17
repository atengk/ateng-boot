# ateng-boot-samples 示例与自验集成工程矩阵

> 基于二级 Parent POM 聚合解耦、100% 自包含场景子模块与轻量内存闭环的端到端自动化测试金字塔与工程化最佳实践矩阵。

---

## 1. 架构定位与解耦矩阵

`ateng-boot-samples` 是 `ateng-boot` 框架的官方自验工程与消费端最佳实践示范矩阵。遵循 [ADR-0028](../docs/adr/0028-samples-modularization-and-incremental-integration-testing.md)，工程全面重构为多模块解耦架构：**坚决杜绝胖单体与公共抽离（Zero-Shared-Code），各场景子模块 100% 自包含、开箱即用、一键复制**。

| 子模块目录 | 核心定位与场景覆盖 | 关键技术栈 | 依赖数据库 | 专属说明 |
| :--- | :--- | :--- | :---: | :---: |
| **`ateng-boot-sample-basic`** | **基础企业级极简脚手架**<br/>CRUD、通用实体五级阶梯链、多租户、数据权限、分批插入、API加签验签、分层双防校验 | Web / MP / Core / H2 | H2 内存库 | [查看](./ateng-boot-sample-basic/README.md) |
| **`ateng-boot-sample-redis`** | **专一 Redis 分布式协同 Demo**<br/>`@DistributedLock` 分布式锁、`@RateLimiter` 接口限流、`@Idempotent` 幂等防重、延时队列 | Redis / Redisson / Web | **零 DB 依赖** | [查看](./ateng-boot-sample-redis/README.md) |
| **`ateng-boot-sample-excel`** | **专一流式 Excel 处理 Demo**<br/>Apache Fesod 流式导出、`@ExcelSensitive` 导出动态脱敏、导入行级校验与红字批注回写、防呆模板 | Excel / Fesod / Web | **零 DB 依赖** | [查看](./ateng-boot-sample-excel/README.md) |
| **`ateng-boot-sample-satoken`** | **专一 Sa-Token 鉴权与会话 Demo**<br/>登录注销、路由拦截白名单、方法级 `@SaCheckRole`、审计人/租户全链路自动打通 | Sa-Token / MP / H2 | H2 内存库 | [查看](./ateng-boot-sample-satoken/README.md) |
| **`ateng-boot-sample-smoke-test`** | **全家桶 CI 冒烟准出门禁**<br/>全量 Starter 无冲突自动装配校验与端到端核心链路冒烟验收（耗时 ≤ 8s） | All / H2 / OpenAPI | H2 内存库 | [查看](./ateng-boot-sample-smoke-test/README.md) |

---

## 2. 本地一键启动体验

每个场景子模块均为独立的 Spring Boot 应用，可按需单独启动：

```bash
# 启动 1：基础脚手架（CRUD、多租户与数据权限演示）
mvn -pl ateng-boot-samples/ateng-boot-sample-basic spring-boot:run

# 启动 2：Redis 协同演示（分布式锁、限流防重，需本地 Redis 服务）
mvn -pl ateng-boot-samples/ateng-boot-sample-redis spring-boot:run

# 启动 3：Excel 导入导出与批注回写演示（纯 Web 模式）
mvn -pl ateng-boot-samples/ateng-boot-sample-excel spring-boot:run

# 启动 4：Sa-Token 权限与审计流转演示
mvn -pl ateng-boot-samples/ateng-boot-sample-satoken spring-boot:run
```

---

## 3. 三阶梯精准增量测试门禁

日常开发遵循 `AGENTS.md` 规定的三阶梯测试铁律，**改动哪个中间件模块，仅定向联动回归对应的 Sample 子模块**，将验收耗时从 1 分多钟大幅压缩至 5~10 秒：

```bash
# 场景 A：修改了 ateng-boot-redis，定向联动自验
mvn test -pl ateng-boot-redis,ateng-boot-samples/ateng-boot-sample-redis -am -T 1C

# 场景 B：修改了 ateng-boot-excel，定向联动自验
mvn test -pl ateng-boot-excel,ateng-boot-samples/ateng-boot-sample-excel -am -T 1C

# 场景 C：修改了 ateng-boot-satoken，定向联动自验
mvn test -pl ateng-boot-satoken,ateng-boot-samples/ateng-boot-sample-satoken -am -T 1C

# 场景 D：修改了 core/web/mybatis-plus 等基础底座，定向联动基础脚手架
mvn test -pl ateng-boot-mybatis-plus,ateng-boot-samples/ateng-boot-sample-basic -am -T 1C

# 场景 E：发版前或全反应堆构建时，全家桶极速冒烟准出
mvn test -pl ateng-boot-samples/ateng-boot-sample-smoke-test -am -T 1C
```

---

## 4. 架构设计与发布隔离

- **零共享代码（Zero-Shared-Code）**：坚决杜绝 `sample-common` 公共抽离层，彻底杜绝跨场景隐式耦合，每个 Demo 都是完全自治的最小参考实现；
- **发布双层排除隔离**：二级 POM 统一注入 `<maven.deploy.skip>true</maven.deploy.skip>`，且根 `pom.xml` 发布插件在 `<excludeArtifacts>` 显式排除所有 sample 构件，保障 Maven Central 发布绝对安全（参见 [ADR-0007](../docs/adr/0007-maven-publishing-and-clean-ci-pipeline.md)、[ADR-0028](../docs/adr/0028-samples-modularization-and-incremental-integration-testing.md)）。
