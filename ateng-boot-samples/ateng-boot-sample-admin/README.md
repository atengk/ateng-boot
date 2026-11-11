# ateng-boot-sample-admin Spring Boot Admin 运维监控场景体验工程

> 专注于演示与自验 `ateng-boot-admin` 核心特性在真实微服务运维监控与安全管控场景下的开箱使用。

---

## 1. 快速启动与服务端口

- **默认 HTTP 端口**：`8080` (可在 `application.yml` 中修改)
- **监控看板入口**：`http://localhost:8080/admin`（默认登录账号/口令：`admin / admin`）
- **启动方式**：
  ```bash
  # 方式一：IDE 中运行 AdminSampleApplication.java 的 main 方法
  # 方式二：命令行启动
  mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-admin
  ```

---

## 2. 运行环境与双模切换

- **默认/CI 模式**：内置 `@Profile("!local")` 离线内存录制桩 `RecordingNotificationTemplate`，自动捕获并存储出站协同告警卡片，无需外部 Webhook 渠道或真实集群即可启动应用与跑通全量测试；
- **Local 真实模式**：若需连接真实企业微信/钉钉/飞书 Webhook 或探测本地已启动的外部客户端微服务，复制 `src/main/resources/application-local.example.yml` 为 `application-local.yml` 并替换大写占位符（已被 Git 忽略，CI 零风险），激活 `local` Profile 启动即可直连实测。

---

## 3. 核心 Controller 接口与一键体验

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **实例准入预检（SSRF 与命名规范）** | `AdminAdmissionController` | `POST /sample/admin/admission/evaluate` | `curl -X POST "http://localhost:8080/sample/admin/admission/evaluate" -H "Content-Type: application/json" -d '{"serviceName":"order-core-service","serviceUrl":"http://127.0.0.1:8080/","healthUrl":"http://127.0.0.1:8080/actuator/health","clientSecret":"CLUSTER_SECRET_DEMO_2026"}'` |
| **控制台认证 SPI 校验（脱敏返回）** | `AdminAdmissionController` | `POST /sample/admin/admission/auth-verify` | `curl -X POST "http://localhost:8080/sample/admin/admission/auth-verify" -H "Content-Type: application/json" -d '{"username":"admin","password":"admin"}'` |
| **看板上下文路径规范化** | `AdminAdmissionController` | `GET /sample/admin/admission/normalize-path` | `curl -X GET "http://localhost:8080/sample/admin/admission/normalize-path?path=admin///"` |
| **只读写守卫策略查询、切换与预判** | `AdminGovernanceController` | `GET` / `POST /sample/admin/governance/write-guard` | `curl -X POST "http://localhost:8080/sample/admin/governance/write-guard" -H "Content-Type: application/json" -d '{"enableWriteOperations":false,"probeMethod":"POST","probeUri":"/admin/instances/order-core-01/actuator/loggers/root"}'` |
| **出站 Actuator 标头级联脱敏预览** | `AdminGovernanceController` | `POST /sample/admin/governance/headers/preview` | `curl -X POST "http://localhost:8080/sample/admin/governance/headers/preview" -H "Content-Type: application/json" -d '{"serviceName":"payment-gateway-service","metadata":{"bearer-token":"eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.sample-jwt-token-8888"}}'` |
| **运行期查询与动态调整 Logback 级别** | `AdminGovernanceController` | `GET` / `POST /sample/admin/governance/loggers` | `curl -X POST "http://localhost:8080/sample/admin/governance/loggers" -H "Content-Type: application/json" -d '{"loggerName":"io.github.atengk.boot.sample.admin","level":"DEBUG"}'` |
| **登记演练微服务与僵尸实例** | `AdminLifecycleController` | `POST /sample/admin/lifecycle/instances/mock` | `curl -X POST "http://localhost:8080/sample/admin/lifecycle/instances/mock" -H "Content-Type: application/json" -d '{"instanceId":"sample-order-01","serviceName":"order-core-service","serviceUrl":"http://127.0.0.1:8080/","version":"v3.0.1","zone":"cn-shanghai-b","status":"UP"}'` |
| **查询受管实例脱敏摘要列表** | `AdminLifecycleController` | `GET /sample/admin/lifecycle/instances` | `curl -X GET "http://localhost:8080/sample/admin/lifecycle/instances"` |
| **手动触发僵尸实例驱逐清理** | `AdminLifecycleController` | `POST /sample/admin/lifecycle/zombies/clean` | `curl -X POST "http://localhost:8080/sample/admin/lifecycle/zombies/clean" -H "Content-Type: application/json" -d '{"zombieThresholdOverride":"PT0.01S"}'` |
| **触发故障跃迁与即时告警卡片** | `AdminAlertController` | `POST /sample/admin/alert/status-transition` | `curl -X POST "http://localhost:8080/sample/admin/alert/status-transition" -H "Content-Type: application/json" -d '{"instanceId":"sample-order-01","status":"DOWN","probeComponent":"diskSpace","probeError":"Free disk space is below threshold","immediate":true}'` |
| **演练 K8s 优雅注销 60 秒静默** | `AdminAlertController` | `POST /sample/admin/alert/graceful-deregister` | `curl -X POST "http://localhost:8080/sample/admin/alert/graceful-deregister" -H "Content-Type: application/json" -d '{"instanceId":"sample-order-01","followUpFaultStatus":"OFFLINE"}'` |
| **查询告警状态机快照与录制卡片** | `AdminAlertController` | `GET /sample/admin/alert/state` & `GET /sample/admin/alert/cards` | `curl -X GET "http://localhost:8080/sample/admin/alert/state"` |
| **SBA 原生注册防 SSRF 端点** | `AdminSsrfGuardFilter` (SBA `/admin/instances`) | `POST /admin/instances` | `curl -X POST "http://localhost:8080/admin/instances" -H "Content-Type: application/json" -H "X-Admin-Client-Secret: CLUSTER_SECRET_DEMO_2026" -d '{"name":"order-core-service","serviceUrl":"http://127.0.0.1:8080/","managementUrl":"http://127.0.0.1:8080/actuator","healthUrl":"http://127.0.0.1:8080/actuator/health"}'` |

---

## 4. 全真自动化回归命令

```bash
mvn test -pl ateng-boot-admin,ateng-boot-samples/ateng-boot-sample-admin -am -T 1C -nsu -B
```

> 详细原理机制、配置清单与扩展定制，请参阅核心 Starter 文档：[ateng-boot-admin README](../../ateng-boot-admin/README.md)。
