# ateng-boot-sample-social 第三方社会化登录与 OAuth2 体验工程

> 专注于演示与自验 `ateng-boot-social` 核心特性在真实业务场景下的开箱使用与端到端集成。

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
- **Local 真实模式**：若需连接真实第三方开放平台（如微信开放平台、GitHub OAuth 等），复制 `src/main/resources/application-local.example.yml` 为 `application-local.yml` 并填入真实凭据，系统将通过 Spring Boot `spring.config.import` 弹性机制自动加载（该文件已被全局 Git 忽略，CI 绝对零风险）。

---

## 3. 核心 Controller 接口与一键体验

应用启动后，可通过以下 cURL 命令或浏览器直接发起调用体验：

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **开箱生成授权地址** | `SocialAuthController` | `GET /auth/social/render/{appKey}` | `curl "http://localhost:8080/auth/social/render/github_app?redirectUrl=http%3A%2F%2Flocalhost%3A8080%2Fwelcome"` |
| **开箱 Web 回调换码** | `SocialAuthController` | `GET /auth/social/callback/{appKey}` | `curl "http://localhost:8080/auth/social/callback/github_app?code=MOCK_CODE&state=MOCK_STATE"` |
| **开箱小程序凭据直传** | `SocialAuthController` | `POST /auth/social/direct-login/{appKey}` | `curl -X POST "http://localhost:8080/auth/social/direct-login/wechat_mp_app" -H "Content-Type: application/json" -d "{\"code\":\"wx_code_001\",\"deviceType\":\"MINI_PROGRAM\"}"` |
| **开箱两阶段用户绑定** | `SocialAuthController` | `POST /auth/social/bind/{appKey}` | `curl -X POST "http://localhost:8080/auth/social/bind/github_app" -H "Content-Type: application/json" -d "{\"bindTicket\":\"TICKET_MOCK_UUID\",\"userId\":\"user_1001\"}"` |
| **开箱解除第三方绑定** | `SocialAuthController` | `POST /auth/social/unbind/{appKey}` | `curl -X POST "http://localhost:8080/auth/social/unbind/github_app" -H "Content-Type: application/json" -d "{\"userId\":\"user_bound_1001\"}"` |
| **场景定制生成跳转** | `SampleSocialController` | `GET /sample/social/render/{appKey}` | `curl "http://localhost:8080/sample/social/render/github_app?tenantId=tenant_dev"` |
| **场景定制孤儿解绑防卫** | `SampleSocialController` | `POST /sample/social/unbind/{appKey}` | `curl -X POST "http://localhost:8080/sample/social/unbind/github_app" -H "Content-Type: application/json" -d "{\"userId\":\"user_orphan_2002\"}"` |

---

## 4. 全真自动化回归命令

```bash
mvn test -pl ateng-boot-social,ateng-boot-samples/ateng-boot-sample-social -am -T 1C -nsu -B
```

> 详细原理机制、配置清单与扩展定制，请参阅核心 Starter 文档：[ateng-boot-social README](../../ateng-boot-social/README.md)。
