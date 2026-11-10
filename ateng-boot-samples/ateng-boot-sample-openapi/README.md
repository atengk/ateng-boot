# ateng-boot-sample-openapi OpenAPI 接口契约治理与安全守卫场景体验工程

> 专注于演示与自验 `ateng-boot-openapi` 核心特性在真实业务场景下的开箱使用，涵盖模型语义保真增强、多业务分组动态路由、全链路请求头精细化豁免、五模生产安全防火墙与受管离线契约交付。

---

## 1. 快速启动与服务端口

- **默认 HTTP 端口**：`8080` (可在 `application.yml` 中修改)
- **启动方式**：
  ```bash
  # 方式一：IDE 中运行 OpenApiSampleApplication.java 的 main 方法
  # 方式二：命令行启动
  mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-openapi
  ```
- **核心契约访问入口**：
  - **Swagger UI 交互界面**：浏览器访问 `http://localhost:8080/docs` 或 `http://localhost:8080/doc`（自动 302 友好重定向至 `/swagger-ui/index.html`）；
  - **全量 OpenAPI 3.0 元数据**：`http://localhost:8080/v3/api-docs`；
  - **运营管理端业务分组元数据**：`http://localhost:8080/v3/api-docs/admin`；
  - **移动端服务业务分组元数据**：`http://localhost:8080/v3/api-docs/app`。

---

## 2. 运行环境与双模切换

- **环境特性**：纯内存与 WebMVC 原生扩展，**100% 离线自洽，无任何外部数据库或中间件依赖**，启动即可秒级完整体验；
- **五模生产安全守卫体验**：
  - **默认开放模式 (`NONE`)**：样例工程默认配置 `guard.mode: NONE`，可直接免密浏览文档与交互调试；
  - **HTTP Basic 认证守卫 (`BASIC`)**：设置 `guard.mode: BASIC`、`guard.basic.username: admin`、`guard.basic.password: admin123`，浏览器访问 `/docs` 将触发标准 HTTP 401 `WWW-Authenticate: Basic` 弹窗；
  - **静态令牌标头守卫 (`TOKEN`)**：设置 `guard.mode: TOKEN`、`guard.token.secret: sample-doc-token`，访问需携带请求头 `X-Doc-Token: sample-doc-token` 或 URL Query 参数 `?doc-token=sample-doc-token`；
  - **CIDR 网络白名单防火墙 (`IP_WHITELIST`)**：设置 `guard.mode: IP_WHITELIST` 与 `guard.ip-whitelist.cidrs: ["127.0.0.1", "10.0.0.0/8"]`，非内网客户端拦截返回 403 Forbidden（配置 `guard.ip-whitelist.trusted-header: X-Forwarded-For` 支持受信任反向代理 IP 穿透）；
  - **生产物理切断 (`enabled: false`)**：设置 `ateng.boot.openapi.enabled: false`，底层组件物理卸载，Swagger UI、`/v3/api-docs` 与离线导出端点统一响应 HTTP 404，杜绝生产信息泄露。

---

## 3. 核心 Controller 接口与一键体验

应用启动后，可通过以下 cURL 命令或 HTTP 客户端直接发起调用体验：

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **创建管理端用户 (枚举/脱敏视图)** | `AdminUserController` | `POST /sample/openapi/admin/users` | `curl -X POST "http://localhost:8080/sample/openapi/admin/users" -H "Content-Type: application/json" -d '{"username":"zhangsan","realName":"张三","email":"zhangsan@example.com"}'` |
| **查询用户明细 (展示 @Sensitive 脱敏)** | `AdminUserController` | `GET /sample/openapi/admin/users/{id}` | `curl "http://localhost:8080/sample/openapi/admin/users/10001"` |
| **查询管理端用户列表** | `AdminUserController` | `GET /sample/openapi/admin/users` | `curl "http://localhost:8080/sample/openapi/admin/users"` |
| **批量创建用户 (ValidList 数组保真)** | `AdminUserController` | `POST /sample/openapi/admin/users/batch` | `curl -X POST "http://localhost:8080/sample/openapi/admin/users/batch" -H "Content-Type: application/json" -d '[{"username":"lisi","realName":"李四","email":"lisi@example.com"},{"username":"wangwu","realName":"王五","email":"wangwu@example.com"}]'` |
| **开放登录认证 (精准豁免 X-Tenant-Id)** | `AdminAuthController` | `POST /sample/openapi/admin/auth/login` | `curl -X POST "http://localhost:8080/sample/openapi/admin/auth/login" -H "Content-Type: application/json" -d '{"username":"admin","password":"password123"}'` |
| **公开系统心跳探针 (全量豁免请求头)** | `AdminAuthController` | `GET /sample/openapi/admin/auth/ping` | `curl "http://localhost:8080/sample/openapi/admin/auth/ping"` |
| **推荐资讯列表 (App 移动端分组)** | `AppArticleController` | `GET /sample/openapi/app/articles` | `curl "http://localhost:8080/sample/openapi/app/articles"` |
| **文章详情查询 (App 移动端分组)** | `AppArticleController` | `GET /sample/openapi/app/articles/{id}` | `curl "http://localhost:8080/sample/openapi/app/articles/20001"` |
| **离线契约导出 (默认全量 YAML)** | `OpenApiExportController` | `GET /docs/export/yaml` | `curl -OJ "http://localhost:8080/docs/export/yaml"` |
| **离线契约导出 (默认全量 JSON)** | `OpenApiExportController` | `GET /docs/export/json` | `curl -OJ "http://localhost:8080/docs/export/json"` |
| **管理端分组离线导出 (Admin JSON)** | `OpenApiExportController` | `GET /docs/export/admin.json` | `curl -OJ "http://localhost:8080/docs/export/admin.json"` |
| **移动端分组离线导出 (App JSON)** | `OpenApiExportController` | `GET /docs/export/app.json` | `curl -OJ "http://localhost:8080/docs/export/app.json"` |
| **友好路由验证 (302 重定向)** | `OpenApiAutoConfiguration` | `GET /docs` | `curl -i "http://localhost:8080/docs"` |

---

## 4. 全真自动化回归命令

```bash
mvn test -pl ateng-boot-openapi,ateng-boot-samples/ateng-boot-sample-openapi -am -T 1C -nsu -B
```

> 详细原理机制、配置清单与扩展定制，请参阅核心 Starter 文档：[ateng-boot-openapi README](../../ateng-boot-openapi/README.md)。
