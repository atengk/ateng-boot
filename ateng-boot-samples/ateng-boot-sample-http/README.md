# ateng-boot-sample-http HTTP 客户端场景体验工程

> 专注于演示与自验 `ateng-boot-http` 声明式契约客户端（`@HttpClient` / `@HttpTimeout` / `fallback`）、命令式 `HttpTemplate` 全动词与流式异步门面，以及出站治理防线在真实业务场景下的开箱使用。

---

## 1. 快速启动与服务端口

- **默认 HTTP 端口**：`8080`（可在 `application.yml` 中修改）
- **启动方式**：
  ```bash
  # 方式一：IDE 中运行 SampleHttpApplication.java 的 main 方法
  # 方式二：命令行启动（默认激活离线回环仿真模式）
  mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-http

  # 方式三：激活 local 模式直连真实外部/内网服务
  mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-http -Dspring-boot.run.profiles=local
  ```

---

## 2. 运行环境与双模切换

- **默认/CI 离线回环模式（`@Profile("!local")`）**：内置离线回环仿真服务端 `MockServerProviderController`（挂载于 `/mock/provider/*`，已标注 `@Profile("!local")`）。各声明式客户端与 `HttpTemplate` 通过真实网络 Socket 环回请求本机端口，全链路穿越 Apache HttpClient 5 池化连接池、四维上下文透传拦截器、脱敏日志拦截器、重试拦截器与统一异常处理器，实现 100% 离线自洽；集成测试统一依托动态随机端口（`RANDOM_PORT` 与 `TestSocketUtils.findAvailableTcpPort()`）执行回环自验，彻底消除固定 `8080` 端口冲突；
- **Local 真实联调模式（`--spring.profiles.active=local`）**：复制 `src/main/resources/application-local.example.yml` 为 `application-local.yml`（已在 `.gitignore` 中忽略）并填入真实目标服务地址后，使用 `local` Profile 启动；此时 `MockServerProviderController` 自动静默退出加载，请求直接路由至配置的真实服务端点。

---

## 3. 核心 Controller 接口与一键体验（共 25 个演示端点）

应用启动后，可在终端通过 cURL 命令直接体验三大演示控制器的全部 25 个标准化 `Result<T>` 端点（14 个命令式通信 + 6 个声明式客户端 + 5 个出站治理与异常探针）：

| # | 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :---: | :--- | :--- | :--- | :--- |
| 1 | **命令式 GET 查询用户** | `HttpTemplateController` | `GET /sample/http/template/user/{id}` | `curl -s -X GET "http://localhost:8080/sample/http/template/user/1001"` |
| 2 | **命令式 POST 创建用户** | `HttpTemplateController` | `POST /sample/http/template/user` | `curl -s -X POST "http://localhost:8080/sample/http/template/user" -H "Content-Type: application/json" -d '{"username":"ateng","role":"ADMIN","status":"ACTIVE"}'` |
| 3 | **命令式 PUT 全量更新** | `HttpTemplateController` | `PUT /sample/http/template/user/{id}` | `curl -s -X PUT "http://localhost:8080/sample/http/template/user/1001" -H "Content-Type: application/json" -d '{"username":"ateng-updated","role":"ADMIN","status":"ACTIVE"}'` |
| 4 | **命令式 DELETE 删除** | `HttpTemplateController` | `DELETE /sample/http/template/user/{id}` | `curl -s -X DELETE "http://localhost:8080/sample/http/template/user/1001"` |
| 5 | **命令式定制 Options** | `HttpTemplateController` | `POST /sample/http/template/user/options` | `curl -s -X POST "http://localhost:8080/sample/http/template/user/options" -H "Content-Type: application/json" -d '{"id":2002,"customHeader":"TRACE-CUSTOM-999","customQueryParam":"vip_channel","readTimeoutMillis":3000}'` |
| 6 | **命令式标准表单提交** | `HttpTemplateController` | `POST /sample/http/template/user/form` | `curl -s -X POST "http://localhost:8080/sample/http/template/user/form" -H "Content-Type: application/json" -d '{"username":"formUser","email":"form@example.com","role":"FORM_USER"}'` |
| 7 | **命令式 SSE 事件消费** | `HttpTemplateController` | `GET /sample/http/template/stream/sse` | `curl -s -X GET "http://localhost:8080/sample/http/template/stream/sse"` |
| 8 | **命令式大文件零缓冲下载** | `HttpTemplateController` | `GET /sample/http/template/download` | `curl -s -X GET "http://localhost:8080/sample/http/template/download"` |
| 9 | **命令式 PATCH 局部更新** | `HttpTemplateController` | `PATCH /sample/http/template/user/{id}` | `curl -s -X PATCH "http://localhost:8080/sample/http/template/user/1009" -H "Content-Type: application/json" -d '{"username":"PatchUser09","status":"LOCKED"}'` |
| 10 | **HEAD/OPTIONS 元数据探测** | `HttpTemplateController` | `GET /sample/http/template/metadata/{id}` | `curl -s -X GET "http://localhost:8080/sample/http/template/metadata/1010"` |
| 11 | **空安全 getOptional (404 降级)** | `HttpTemplateController` | `GET /sample/http/template/get-optional/{id}` | `curl -s -X GET "http://localhost:8080/sample/http/template/get-optional/404"` |
| 12 | **getEntity 响应头提取** | `HttpTemplateController` | `GET /sample/http/template/get-entity/{id}` | `curl -s -X GET "http://localhost:8080/sample/http/template/get-entity/1012"` |
| 13 | **零缓冲 Multipart 上传** | `HttpTemplateController` | `POST /sample/http/template/upload` | `curl -s -X POST "http://localhost:8080/sample/http/template/upload" -F "file=@pom.xml" -F "bizType=contract-pdf"` |
| 14 | **异步四维上下文透传** | `HttpTemplateController` | `GET /sample/http/template/async-context` | `curl -s -X GET "http://localhost:8080/sample/http/template/async-context"` |
| 15 | **声明式客户端查用户** | `HttpDeclarativeClientController` | `GET /sample/http/declarative/user/{id}` | `curl -s -X GET "http://localhost:8080/sample/http/declarative/user/1002"` |
| 16 | **声明式客户端创用户** | `HttpDeclarativeClientController` | `POST /sample/http/declarative/user` | `curl -s -X POST "http://localhost:8080/sample/http/declarative/user" -H "Content-Type: application/json" -d '{"username":"declarativeUser","role":"USER","status":"ACTIVE"}'` |
| 17 | **声明式客户端查订单** | `HttpDeclarativeClientController` | `GET /sample/http/declarative/order/{orderId}` | `curl -s -X GET "http://localhost:8080/sample/http/declarative/order/ORD_20261109_001"` |
| 18 | **声明式客户端查状态** | `HttpDeclarativeClientController` | `GET /sample/http/declarative/order/{orderId}/status` | `curl -s -X GET "http://localhost:8080/sample/http/declarative/order/ORD_20261109_001/status"` |
| 19 | **声明式超时 Fallback 降级** | `HttpDeclarativeClientController` | `GET /sample/http/declarative/order/fallback/timeout/{orderId}` | `curl -s -X GET "http://localhost:8080/sample/http/declarative/order/fallback/timeout/slow-order-123"` |
| 20 | **声明式异常 Fallback 降级** | `HttpDeclarativeClientController` | `GET /sample/http/declarative/order/fallback/error/{orderId}` | `curl -s -X GET "http://localhost:8080/sample/http/declarative/order/fallback/error/fail-order-456"` |
| 21 | **外部非信任域头隔离** | `HttpGovernanceController` | `POST /sample/http/governance/wechat/pay` | `curl -s -X POST "http://localhost:8080/sample/http/governance/wechat/pay" -H "Content-Type: application/json" -d '{"outTradeNo":"PAY_WX_20261109_001","description":"会员充值","totalFee":9900}'` |
| 22 | **瞬态 503 退避重试演练** | `HttpGovernanceController` | `GET /sample/http/governance/retry/flaky` | `curl -s -X GET "http://localhost:8080/sample/http/governance/retry/flaky?failTimes=2"` |
| 23 | **出站 cURL 脱敏日志演示** | `HttpGovernanceController` | `GET /sample/http/governance/logging/curl-demo` | `curl -s -X GET "http://localhost:8080/sample/http/governance/logging/curl-demo?token=YOUR_SAMPLE_TOKEN&password=YOUR_SAMPLE_PASSWORD"` |
| 24 | **结构化错误体因果转译** | `HttpGovernanceController` | `GET /sample/http/governance/error-translate` | `curl -s -X GET "http://localhost:8080/sample/http/governance/error-translate?code=422"` |
| 25 | **多实例/mTLS/代理自省探针** | `HttpGovernanceController` | `GET /sample/http/governance/registry-inspect` | `curl -s -X GET "http://localhost:8080/sample/http/governance/registry-inspect"` |

---

## 4. 全真自动化回归命令

```bash
mvn test -pl ateng-boot-http,ateng-boot-samples/ateng-boot-sample-http -am -T 1C -nsu -B
```

> 详细架构设计、三要素闭环代码与全量配置速查附录，请参阅 Starter 权威文档：[ateng-boot-http README](../../ateng-boot-http/README.md)。
