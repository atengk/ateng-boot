# ateng-boot-sample-social 第三方社会化登录与 OAuth2 体验工程

> 专注于演示与自验 `ateng-boot-social` 核心特性在真实业务场景下的开箱使用、四大业务领域控制器拆分与端到端集成。

---

## 1. 快速启动与服务端口

- **默认 HTTP 端口**：`8080` (可在 `application.yml` 中修改)
- **启动方式**：
  ```bash
  # 方式一：IDE 中运行 SampleSocialApplication.java 的 main 方法
  # 方式二：命令行启动
  mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-social
  ```

---

## 2. 运行环境与双模切换

- **默认/CI 模式**：内置基于 OkHttp MockWebServer 与原生 JVM 内存状态机存储，无需任何外部物理网络或真实凭据即可 100% 离线自洽启动运行与跑通端到端全量测试；
- **Local 真实模式**：若需连接真实第三方开放平台（如微信开放平台、GitHub OAuth、百度开放平台等），复制 `src/main/resources/application-local.example.yml` 为 `application-local.yml` 并填入真实凭据，系统将通过 Spring Boot `spring.config.import` 弹性机制自动加载（该文件已被全局 Git 忽略，CI 绝对零风险）。

---

## 3. 核心 Controller 接口与一键体验

应用启动后，工程按业务领域拆分提供了 4 个独立场景控制器，并保留开箱即用内置控制器，可通过以下 cURL 命令直接发起调用体验：

### 3.1 领域一：Web OAuth2 认证与令牌治理 (`SocialWebOAuthController`)

| 功能场景 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- |
| **PC 扫码授权地址渲染** | `GET /sample/social/oauth/render/{appKey}` | `curl "http://localhost:8080/sample/social/oauth/render/github_app?redirectUrl=https%3A%2F%2Fateng.example.com%2Fwelcome&tenantId=tenant_dev"` |
| **开放重定向白名单校验** | `GET /sample/social/oauth/render-whitelist` | `curl "http://localhost:8080/sample/social/oauth/render-whitelist?redirectUrl=https%3A%2F%2Fateng.example.com%2Fdashboard"` |
| **Web 回调换码认证** | `GET /sample/social/oauth/callback/{appKey}` | `curl "http://localhost:8080/sample/social/oauth/callback/github_app?code=MOCK_CODE&state=MOCK_STATE"` |
| **第三方令牌续期** | `POST /sample/social/oauth/refresh-token/{appKey}` | `curl -X POST "http://localhost:8080/sample/social/oauth/refresh-token/baidu_app" -H "Content-Type: application/json" -d "{\"refreshToken\":\"MOCK_REFRESH_TOKEN\"}"` |
| **第三方授权吊销** | `POST /sample/social/oauth/revoke-token/{appKey}` | `curl -X POST "http://localhost:8080/sample/social/oauth/revoke-token/baidu_app" -H "Content-Type: application/json" -d "{\"accessToken\":\"MOCK_ACCESS_TOKEN\"}"` |

### 3.2 领域二：移动端与小程序凭证直传 (`SocialMiniProgramAuthController`)

| 功能场景 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- |
| **小程序凭据直传登录** | `POST /sample/social/miniapp/direct-login/{appKey}` | `curl -X POST "http://localhost:8080/sample/social/miniapp/direct-login/wechat_mp_app" -H "Content-Type: application/json" -d "{\"code\":\"mock_wx_js_code\",\"deviceType\":\"MINI_PROGRAM\"}"` |
| **首次登录静默自动开户** | `POST /sample/social/miniapp/auto-signup-login/{appKey}` | `curl -X POST "http://localhost:8080/sample/social/miniapp/auto-signup-login/wechat_auto_app" -H "Content-Type: application/json" -d "{\"code\":\"mock_wx_auto_code\",\"deviceType\":\"MINI_PROGRAM\"}"` |

### 3.3 领域三：账号绑定、跨号防卫与解绑 (`SocialAccountBindController`)

| 功能场景 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- |
| **未登录两阶段 Ticket 绑定** | `POST /sample/social/bind/two-phase-bind/{appKey}` | `curl -X POST "http://localhost:8080/sample/social/bind/two-phase-bind/github_app" -H "Content-Type: application/json" -d "{\"bindTicket\":\"MOCK_BIND_TICKET\",\"userId\":\"user_new_101\"}"` |
| **个人中心已登录直接绑定** | `POST /sample/social/bind/direct-bind/{appKey}` | `curl -X POST "http://localhost:8080/sample/social/bind/direct-bind/wechat_mp_app" -H "Content-Type: application/json" -d "{\"userId\":\"user_bound_1001\",\"code\":\"mock_wx_code\"}"` |
| **跨号抢占冲突拦截演示** | `POST /sample/social/bind/conflict-check/{appKey}` | `curl -X POST "http://localhost:8080/sample/social/bind/conflict-check/wechat_mp_app" -H "Content-Type: application/json" -d "{\"userId\":\"user_attacker\",\"code\":\"mock_wx_code_conflict\"}"` |
| **孤儿账号防卫安全解绑** | `POST /sample/social/bind/unbind/{appKey}` | `curl -X POST "http://localhost:8080/sample/social/bind/unbind/github_app" -H "Content-Type: application/json" -d "{\"userId\":\"user_orphan_2002\"}"` |
| **已绑定社交账号列表查询** | `GET /sample/social/bind/bindings` | `curl "http://localhost:8080/sample/social/bind/bindings?userId=user_bound_1001"` |

### 3.4 领域四：多租户切源与领域事件审计 (`SocialMultiTenantConfigController`)

| 功能场景 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- |
| **SaaS 动态配置切源提取** | `GET /sample/social/tenant/app-config` | `curl "http://localhost:8080/sample/social/tenant/app-config?appKey=tenant_saas_app"` |
| **多租户上下文自动恢复** | `GET /sample/social/tenant/verify-tenant-callback` | `curl "http://localhost:8080/sample/social/tenant/verify-tenant-callback?appKey=github_app&code=MOCK_CODE&state=MOCK_STATE"` |
| **领域事件四件套审计查询** | `GET /sample/social/tenant/audit-events` | `curl "http://localhost:8080/sample/social/tenant/audit-events"` |

### 3.5 Starter 开箱即用内置控制器 (`SocialAuthController`)

| 功能场景 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- |
| **开箱生成授权跳转** | `GET /auth/social/render/{appKey}` | `curl "http://localhost:8080/auth/social/render/github_app?redirectUrl=https%3A%2F%2Fateng.example.com%2Fwelcome"` |
| **开箱 Web 回调换码** | `GET /auth/social/callback/{appKey}` | `curl "http://localhost:8080/auth/social/callback/github_app?code=MOCK_CODE&state=MOCK_STATE"` |
| **开箱小程序直传换码** | `POST /auth/social/direct-login/{appKey}` | `curl -X POST "http://localhost:8080/auth/social/direct-login/wechat_mp_app" -H "Content-Type: application/json" -d "{\"code\":\"mock_js_code\"}"` |
| **开箱两阶段账号绑定** | `POST /auth/social/bind/{appKey}` | `curl -X POST "http://localhost:8080/auth/social/bind/github_app" -H "Content-Type: application/json" -d "{\"bindTicket\":\"TICKET_UUID\",\"userId\":\"user_1001\"}"` |
| **开箱已登录直接绑定** | `POST /auth/social/bind-direct/{appKey}` | `curl -X POST "http://localhost:8080/auth/social/bind-direct/wechat_mp_app" -H "Content-Type: application/json" -d "{\"userId\":\"user_1001\",\"code\":\"mock_code\"}"` |
| **开箱解除第三方绑定** | `POST /auth/social/unbind/{appKey}` | `curl -X POST "http://localhost:8080/auth/social/unbind/github_app" -H "Content-Type: application/json" -d "{\"userId\":\"user_bound_1001\"}"` |
| **开箱已绑定列表查询** | `GET /auth/social/bindings` | `curl "http://localhost:8080/auth/social/bindings?userId=user_bound_1001"` |

---

## 4. 全真自动化回归命令

```bash
mvn test -pl ateng-boot-social,ateng-boot-samples/ateng-boot-sample-social -am -T 1C -nsu -B
```

> 详细原理机制、配置清单与扩展定制，请参阅核心 Starter 文档：[ateng-boot-social README](../../ateng-boot-social/README.md)。
