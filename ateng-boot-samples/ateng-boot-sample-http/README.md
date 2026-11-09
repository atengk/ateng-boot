# ateng-boot-sample-http HTTP 客户端场景体验工程

> 专注于演示与自验 `ateng-boot-http` 声明式契约客户端、命令式 `HttpTemplate` 门面及出站治理防线在真实业务场景下的开箱使用。

---

## 1. 快速启动与服务端口

- **默认 HTTP 端口**：`8080`（可在 `application.yml` 中修改）
- **启动方式**：
  ```bash
  # 方式一：IDE 中运行 SampleHttpApplication.java 的 main 方法
  # 方式二：命令行启动
  mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-http
  ```

---

## 2. 运行环境与双模切换

- **默认/CI 模式**：内置零网络依赖的离线回环仿真服务（`MockServerProviderController`，挂载于 `/mock/provider/*`）。各通信客户端默认通过真实网络 Socket 环回请求本机 8080 端口，全链路穿越 Apache HttpClient 5 池化连接池、出站追踪拦截器与重试拦截器，实现 100% 离线自洽秒级自验；
- **Local 真实模式**：若需直连真实微服务集群或第三方外部网关，复制 `src/main/resources/application-local.example.yml` 为 `application-local.yml` 并配置目标服务地址（已被 Git 忽略，CI 零风险），激活 `local` 环境即可直连调试。

---

## 3. 核心 Controller 接口与一键体验

应用启动后，可在终端中通过 cURL 命令快速体验各类 HTTP 通信与出站治理能力：

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **命令式 GET 查询用户** | `HttpTemplateController` | `GET /sample/http/template/user/{id}` | `curl -s -X GET "http://localhost:8080/sample/http/template/user/1001"` |
| **命令式 POST 创建用户** | `HttpTemplateController` | `POST /sample/http/template/user` | `curl -s -X POST "http://localhost:8080/sample/http/template/user" -H "Content-Type: application/json" -d '{"username":"ateng","role":"ADMIN","status":"ACTIVE"}'` |
| **命令式定制 Options** | `HttpTemplateController` | `POST /sample/http/template/options` | `curl -s -X POST "http://localhost:8080/sample/http/template/options" -H "Content-Type: application/json" -d '{"userId":2002,"customTraceHeader":"TRACE-CUSTOM-999","customQueryParam":"vip_channel"}'` |
| **命令式表单提交** | `HttpTemplateController` | `POST /sample/http/template/form` | `curl -s -X POST "http://localhost:8080/sample/http/template/form" -H "Content-Type: application/json" -d '{"username":"formUser","category":"security","newsletter":true}'` |
| **命令式流式下载** | `HttpTemplateController` | `GET /sample/http/template/download` | `curl -s -X GET "http://localhost:8080/sample/http/template/download"` |
| **命令式 SSE 消费** | `HttpTemplateController` | `GET /sample/http/template/sse` | `curl -s -X GET "http://localhost:8080/sample/http/template/sse"` |
| **声明式客户端查用户** | `HttpDeclarativeClientController` | `GET /sample/http/declarative/user/{id}` | `curl -s -X GET "http://localhost:8080/sample/http/declarative/user/1002"` |
| **声明式客户端创用户** | `HttpDeclarativeClientController` | `POST /sample/http/declarative/user` | `curl -s -X POST "http://localhost:8080/sample/http/declarative/user" -H "Content-Type: application/json" -d '{"username":"declarativeUser","role":"USER","status":"ACTIVE"}'` |
| **声明式客户端查订单** | `HttpDeclarativeClientController` | `GET /sample/http/declarative/order/sync/{id}` | `curl -s -X GET "http://localhost:8080/sample/http/declarative/order/sync/ORD_20261109_001"` |
| **声明式客户端超时探测** | `HttpDeclarativeClientController` | `GET /sample/http/declarative/order/timeout/{id}` | `curl -s -X GET "http://localhost:8080/sample/http/declarative/order/timeout/ORD_20261109_SLOW"` |
| **声明式客户端 Fallback 降级** | `HttpDeclarativeClientController` | `GET /sample/http/declarative/order/fallback/{id}` | `curl -s -X GET "http://localhost:8080/sample/http/declarative/order/fallback/ORD_20261109_FAIL"` |
| **外部非信任域头隔离** | `HttpGovernanceController` | `POST /sample/http/governance/wechat/pay` | `curl -s -X POST "http://localhost:8080/sample/http/governance/wechat/pay" -H "Content-Type: application/json" -d '{"outTradeNo":"PAY_WX_20261109_001","description":"会员充值","totalFee":9900}'` |
| **瞬态 503 退避重试演练** | `HttpGovernanceController` | `GET /sample/http/governance/retry/flaky` | `curl -s -X GET "http://localhost:8080/sample/http/governance/retry/flaky?failTimes=2"` |
| **出站 cURL 脱敏日志演示** | `HttpGovernanceController` | `GET /sample/http/governance/logging/curl-demo` | `curl -s -X GET "http://localhost:8080/sample/http/governance/logging/curl-demo?token=sk-topsecret-bearer-token&password=superSecret123"` |

---

## 4. 全真自动化回归命令

```bash
mvn test -pl ateng-boot-http,ateng-boot-samples/ateng-boot-sample-http -am -T 1C -nsu -B
```

> 详细架构设计、三要素闭环代码与配置速查附录，请参阅 Starter 权威文档：[ateng-boot-http README](../../ateng-boot-http/README.md)。
