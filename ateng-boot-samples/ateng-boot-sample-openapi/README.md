# ateng-boot-sample-openapi OpenAPI 接口契约治理与安全守卫场景体验工程

> 专注于演示与自验 `ateng-boot-openapi` 核心特性在真实业务场景下的开箱使用，涵盖模型语义保真增强（`IBaseEnum` / `@InEnum` / `@FieldSensitive` / `ValidList`）、跨模块治理契约零耦合内省、多业务分组对称匹配与排除、五模生产安全防火墙及离线契约安全交付闭环。

---

## 1. 快速启动与服务端口

- **默认 HTTP 端口**：`8080`（可在 [application.yml](./src/main/resources/application.yml) 中修改）
- **启动方式**：
  ```bash
  # 方式一：IDE 中运行 OpenApiSampleApplication.java 的 main 方法
  # 方式二：命令行启动
  mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-openapi
  ```
- **核心契约访问入口**：
  - **Swagger UI 交互界面**：浏览器访问 `http://localhost:8080/docs` 或 `http://localhost:8080/doc`（自动 302 友好重定向至 `/swagger-ui/index.html`）；
  - **全量 OpenAPI 3.0 元数据**：`http://localhost:8080/v3/api-docs`；
  - **运营管理端业务分组元数据**：`http://localhost:8080/v3/api-docs/admin`（已配置对称排除内部包与公开心跳端点）；
  - **移动端服务业务分组元数据**：`http://localhost:8080/v3/api-docs/app`；
  - **契约治理与导出分组元数据**：`http://localhost:8080/v3/api-docs/governance`。

---

## 2. 运行环境与双模切换

- **环境特性**：纯内存与 WebMVC 原生扩展，**100% 离线自洽，无任何外部数据库或 Redis 等中间件依赖**，启动即可秒级完整体验（无 `application-local.example.yml` 需求，远程中间件双模切换适用 `[豁免：无外部依赖]`）；
- **五模生产安全守卫切换体验**（通过修改 `ateng.boot.openapi.guard.mode` 或单元测试 `@TestPropertySource` 切换）：
  - **默认开放模式 (`NONE`)**：样例工程默认配置 `guard.mode: NONE`，可直接免密浏览文档、体验 `OperationGovernanceCustomizer` 契约渲染与离线导出；
  - **HTTP Basic 认证守卫 (`BASIC`)**：设置 `guard.mode: BASIC`、`guard.basic.username` 与 `guard.basic.password`，访问 `/docs` 将触发标准 HTTP `401` `WWW-Authenticate: Basic` 弹窗；
  - **Sa-Token 会话与角色/权限分级守卫 (`SATOKEN`)**：设置 `guard.mode: SATOKEN`，未登录访问返回 HTTP `401`；进一步配置 `guard.satoken.roles`（如 `doc-admin`）或 `guard.satoken.permissions`（如 `openapi:view`）时，已登录但均不命中返回 HTTP `403`（OR 语义放行）；
  - **静态令牌标头守卫 (`TOKEN`)**：设置 `guard.mode: TOKEN` 与 `guard.token.secret`，访问需携带请求头 `X-Doc-Token` 或 URL Query 参数 `?doc-token=...`；
  - **CIDR 网络白名单与授信反代校验 (`IP_WHITELIST`)**：配置 `guard.ip-whitelist.cidrs`、`trusted-header: X-Forwarded-For` 与 `trusted-proxies`（如 `127.0.0.1,172.16.0.0/12`）；非授信物理来源伪造转发头直接忽略并拦截 HTTP `403`，支持 IPv4/IPv6；
  - **生产物理切断 (`enabled: false`)**：设置 `ateng.boot.openapi.enabled: false`，底层装配物理卸载，`/swagger-ui/**`、`/v3/api-docs/**`、`/docs` 与 `/docs/export/**` 统一返回 HTTP `404`，`OpenApiGovernanceController` 依托延迟装配保持容器正常启动。

---

## 3. 核心 Controller 接口与一键体验

应用启动后，可通过以下 cURL 命令体验 4 大领域控制器与离线导出端点：

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **创建管理端用户 (`@InEnum` / `@Idempotent` / `@SaCheckPermission`)** | `AdminUserController` | `POST /sample/openapi/admin/users` | `curl -X POST "http://localhost:8080/sample/openapi/admin/users" -H "Content-Type: application/json" -d '{"username":"zhangsan","realName":"张三","email":"zhangsan@example.com","statusCode":1}'` |
| **查询用户明细 (展示 `@FieldSensitive` 脱敏标签)** | `AdminUserController` | `GET /sample/openapi/admin/users/{id}` | `curl "http://localhost:8080/sample/openapi/admin/users/10001"` |
| **查询管理端用户全量列表** | `AdminUserController` | `GET /sample/openapi/admin/users` | `curl "http://localhost:8080/sample/openapi/admin/users"` |
| **分页查询用户 (`PageQuery` / `PageResult` / 方法参数 `@InEnum` / `@RateLimiter`)** | `AdminUserController` | `GET /sample/openapi/admin/users/page` | `curl "http://localhost:8080/sample/openapi/admin/users/page?current=1&size=1&status=1"` |
| **批量创建用户 (`ValidList` 原生数组契约保真)** | `AdminUserController` | `POST /sample/openapi/admin/users/batch` | `curl -X POST "http://localhost:8080/sample/openapi/admin/users/batch" -H "Content-Type: application/json" -d '[{"username":"lisi","realName":"李四","email":"lisi@example.com","statusCode":1}]'` |
| **开放同步回调 (`@SaIgnore` + `@ApiSign` + `@IpAccessControl` + `@ApiVersion("2")`)** | `AdminUserController` | `POST /sample/openapi/admin/users/sync-callback` | `curl -X POST "http://localhost:8080/sample/openapi/admin/users/sync-callback" -H "Content-Type: application/json" -H "X-API-Version: 2" -d '{"username":"sync_bot","realName":"同步机器人","email":"bot@example.com","statusCode":1}'` |
| **开放登录认证 (`@SaIgnore` + 精准豁免 `X-Tenant-Id`)** | `AdminAuthController` | `POST /sample/openapi/admin/auth/login` | `curl -X POST "http://localhost:8080/sample/openapi/admin/auth/login" -H "Content-Type: application/json" -d '{"username":"admin","password":"YOUR_PASSWORD"}'` |
| **公开心跳探针 (`@IgnoreGlobalHeaders` 全量豁免 & `admin` 路径排除靶点)** | `AdminAuthController` | `GET /sample/openapi/admin/auth/ping` | `curl "http://localhost:8080/sample/openapi/admin/auth/ping"` |
| **内部运维指标 (`admin` 分组 `packages-to-exclude` 排除靶点)** | `AdminInternalController` | `GET /sample/openapi/admin/internal/metrics` | `curl "http://localhost:8080/sample/openapi/admin/internal/metrics"` |
| **推荐资讯列表 (`app` 移动端分组)** | `AppArticleController` | `GET /sample/openapi/app/articles` | `curl "http://localhost:8080/sample/openapi/app/articles"` |
| **文章详情查询 (`app` 移动端分组)** | `AppArticleController` | `GET /sample/openapi/app/articles/{id}` | `curl "http://localhost:8080/sample/openapi/app/articles/20001"` |
| **契约摘要预览 (`governance` 分组 & `OpenApiExportHelper`)** | `OpenApiGovernanceController` | `GET /sample/openapi/governance/preview` | `curl "http://localhost:8080/sample/openapi/governance/preview?group=admin&format=JSON"` |
| **受管临时目录安全落盘校验 (`exportFile` 防穿越)** | `OpenApiGovernanceController` | `POST /sample/openapi/governance/export-check` | `curl -X POST "http://localhost:8080/sample/openapi/governance/export-check" -H "Content-Type: application/json" -d '{"group":"admin","format":"YAML","relativePath":"exports/admin-contract.yaml"}'` |
| **系统与 SPI 状态码全景字典检索** | `OpenApiGovernanceController` | `GET /sample/openapi/governance/result-codes` | `curl "http://localhost:8080/sample/openapi/governance/result-codes?keyword=8001"` |
| **离线契约导出 (默认全量 JSON / YAML)** | `OpenApiExportController` | `GET /docs/export/json`<br>`GET /docs/export/yaml` | `curl -OJ "http://localhost:8080/docs/export/json"` |
| **业务分组离线导出 (`{group}` 白名单拦截非法穿越)** | `OpenApiExportController` | `GET /docs/export/{group}.json`<br>`GET /docs/export/{group}.yaml` | `curl -OJ "http://localhost:8080/docs/export/admin.json"` |
| **友好路由重定向 (302 跳转 Swagger UI)** | `OpenApiAutoConfiguration` | `GET /docs` | `curl -i "http://localhost:8080/docs"` |

---

## 4. 全真自动化回归命令

```bash
mvn test -pl ateng-boot-openapi,ateng-boot-samples/ateng-boot-sample-openapi -am -T 1C -nsu -B
```

> 详细原理机制、完整 YAML 属性清单与扩展定制指南，请参阅核心 Starter 文档：[ateng-boot-openapi README](../../ateng-boot-openapi/README.md)。
