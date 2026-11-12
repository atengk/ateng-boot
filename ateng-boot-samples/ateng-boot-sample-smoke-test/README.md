# ateng-boot-sample-smoke-test 全家桶装配冒烟与集成体验工程

> 专注于 1:1 演示与端到端门禁验证 [`ateng-boot-all`](../../ateng-boot-all/README.md) 核心全家桶（6 大基础 Starter）与代表性按需扩展 Starter（`openapi`、`storage`、`http`、`realtime`、`encrypt`、`statemachine`、`tcp`）在同一 Spring 容器下的零冲突共存与跨模块联动能力。

---

## 1. 快速启动与服务端口

- **默认 HTTP 端口**：`8080`（可在 `src/main/resources/application.yml` 中修改）
- **启动方式**：
  ```bash
  # 方式一：IDE 中直接运行 SampleSmokeTestApplication.java 的 main 方法
  # 方式二：命令行启动（使用 H2 内存库与离线降级配置）
  mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-smoke-test
  ```

---

## 2. 运行环境与双模切换

- **默认 / CI 离线模式 (`!local`)**：使用内置 H2 内存数据库（自动执行 `schema.sql`），通过 `spring.autoconfigure.exclude` 排除 `RedisAutoConfiguration` / `RedisRepositoriesAutoConfiguration` 且设置 `ateng.boot.redis.enabled: false`，实现 **100% 离线零外部依赖**快速启动与集成门禁校验。
- **Local 真实联调模式 (`local`)**：如需验证真实 Redis 分布式会话与键值探测往返，请将 `src/main/resources/application-local.example.yml` 复制为 `src/main/resources/application-local.yml`（已被 `.gitignore` 忽略），填入 Redis 地址与密码后激活 `local` Profile 启动：
  ```bash
  mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-smoke-test -Dspring-boot.run.profiles=local
  ```

---

## 3. 核心 Controller 接口与一键体验

> 本地启动后可直接访问 OpenAPI 文档 Schema：`http://localhost:8080/v3/api-docs` 或 Swagger UI：`http://localhost:8080/doc.html` / `/swagger-ui.html`。

| 功能场景 | Controller 类 / 端点组件 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **冒烟探活与防失真长整型契约** | `SmokeContractController` | `GET /sample/smoke/contract/ping` | `curl -i "http://localhost:8080/sample/smoke/contract/ping"` |
| **批量 `ValidList` 校验与视图脱敏** | `SmokeContractController` | `POST /sample/smoke/contract/batch-validate` | `curl -X POST -H "Content-Type: application/json" -d "[{\"title\":\"核心网关\",\"status\":\"PUBLISHED\",\"mobile\":\"13800138000\"}]" "http://localhost:8080/sample/smoke/contract/batch-validate"` |
| **Sa-Token 登录与上下文桥接** | `SmokeSecurityCoordinationController` | `POST /sample/smoke/security/login` | `curl -X POST -H "Content-Type: application/json" -d "{\"userId\":\"smoke-user-9999\",\"userAccount\":\"ateng\",\"userName\":\"阿腾\",\"tenantId\":\"TENANT_SMOKE\"}" "http://localhost:8080/sample/smoke/security/login"` |
| **受 `@SaCheckLogin` 保护会话探查** | `SmokeSecurityCoordinationController` | `GET /sample/smoke/security/me` | `curl -H "satoken: YOUR_TOKEN_VALUE" "http://localhost:8080/sample/smoke/security/me"` |
| **Redis 双模状态探针** | `SmokeSecurityCoordinationController` | `GET /sample/smoke/security/redis-status` | `curl "http://localhost:8080/sample/smoke/security/redis-status"` |
| **多 Starter 共存装配状态查询** | `SmokeSecurityCoordinationController` | `GET /sample/smoke/security/coexistence` | `curl "http://localhost:8080/sample/smoke/security/coexistence"` |
| **新增记录与审计自动填充** | `SmokePersistenceExcelController` | `POST /sample/smoke/record/create` | `curl -X POST -H "Content-Type: application/json" -H "satoken: YOUR_TOKEN_VALUE" -d "{\"title\":\"全家桶冒烟记录单\",\"status\":\"PUBLISHED\",\"amount\":12345.67,\"mobile\":\"13912345678\"}" "http://localhost:8080/sample/smoke/record/create"` |
| **标准 `PageResult` 分页与脱敏输出** | `SmokePersistenceExcelController` | `POST /sample/smoke/record/page` | `curl -X POST -H "Content-Type: application/json" -d "{\"current\":1,\"size\":10,\"status\":\"PUBLISHED\"}" "http://localhost:8080/sample/smoke/record/page"` |
| **Apache Fesod 脱敏流式 Excel 导出** | `SmokePersistenceExcelController` | `GET /sample/smoke/record/export` | `curl -o smoke-records.xlsx "http://localhost:8080/sample/smoke/record/export?status=PUBLISHED"` |
| **OpenAPI 3.0 Schema 输出校验** | `ateng-boot-openapi` | `GET /v3/api-docs` | `curl "http://localhost:8080/v3/api-docs"` |

---

## 4. 全真自动化回归命令

```bash
mvn test -pl ateng-boot-all,ateng-boot-samples/ateng-boot-sample-smoke-test -am -T 1C -nsu -B
```

> 详细依赖选型边界、聚合架构与局部排除配方，请参阅核心模块文档：[ateng-boot-all README](../../ateng-boot-all/README.md)。
