# ateng-boot-sample-admin 企业级监控看板与安全防御体验工程

> 专注于演示与自验 `ateng-boot-admin` 核心特性在真实业务场景下的开箱使用（按职责拆分为 **5 大领域控制器** 与 `RANDOM_PORT` 真实 SBA Client 端到端闭环）。

---

## 1. 快速启动与服务端口

- **默认 HTTP 端口**：`8080`（SBA 监控看板挂载路径 `/admin`，可在 [application.yml](./src/main/resources/application.yml) 中调整）
- **启动方式**：
  ```bash
  # 方式一：IDE 中运行 AdminSampleApplication.java 的 main 方法
  # 方式二：命令行启动
  mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-admin
  ```

---

## 2. 运行环境与双模切换

- **默认/CI 模式**：内置 `@Profile("!local")` 离线内存录制桩 `RecordingNotificationTemplate`、`InMemoryAdminClusterCoordinator` 与可控测试时钟/地址接缝，无需外部 Redis 或群机器人即可完整启动与跑通全部测试；
- **Local 真实模式**：若需连接真实企业微信/钉钉 Webhook、外部客户端或独立 Redis，复制 [application-local.example.yml](./src/main/resources/application-local.example.yml) 为 `application-local.yml` 并填入真实连接信息（已被 `.gitignore` 忽略），激活 `--spring.profiles.active=local` 启动即可直连验证（默认关闭 `spring.boot.admin.client.enabled: false`，仅在 [AdminRealClientEndToEndTest](./src/test/java/io/github/atengk/boot/sample/admin/AdminRealClientEndToEndTest.java) 按需开启真实客户端注册）。

---

## 3. 核心 Controller 接口与一键体验

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **SSRF 与准入策略预检** | `AdminAdmissionController` | `POST /sample/admin/admission/evaluate` | ``curl -X POST http://127.0.0.1:8080/sample/admin/admission/evaluate -H "Content-Type: application/json" -d "{\"serviceName\":\"order-svc\",\"serviceUrl\":\"http://127.0.0.1:8080/\",\"clientSecret\":\"YOUR_SECRET\"}"`` |
| **控制台认证 SPI 校验** | `AdminAdmissionController` | `POST /sample/admin/admission/auth-verify` | ``curl -X POST http://127.0.0.1:8080/sample/admin/admission/auth-verify -H "Content-Type: application/json" -d "{\"username\":\"admin\",\"password\":\"admin\"}"`` |
| **路径归一化防御验证** | `AdminAdmissionController` | `GET /sample/admin/admission/normalize-path` | ``curl "http://127.0.0.1:8080/sample/admin/admission/normalize-path?rawPath=/admin//instances;v=1"`` |
| **只读写守卫查询与切换** | `AdminGovernanceController` | `GET / POST /sample/admin/governance/write-guard` | ``curl -X POST http://127.0.0.1:8080/sample/admin/governance/write-guard -H "Content-Type: application/json" -d "{\"enableWriteOperations\":false}"`` |
| **出站凭据级联推导预览** | `AdminGovernanceController` | `POST /sample/admin/governance/headers/preview` | ``curl -X POST http://127.0.0.1:8080/sample/admin/governance/headers/preview -H "Content-Type: application/json" -d "{\"serviceName\":\"pay-svc\"}"`` |
| **在线 Logback 日志调控** | `AdminGovernanceController` | `GET / POST /sample/admin/governance/loggers` | ``curl "http://127.0.0.1:8080/sample/admin/governance/loggers?loggerName=io.github.atengk"`` |
| **模拟演练/僵尸实例登记** | `AdminLifecycleController` | `POST / GET /sample/admin/lifecycle/instances/mock` | ``curl -X POST http://127.0.0.1:8080/sample/admin/lifecycle/instances/mock -H "Content-Type: application/json" -d "{\"instanceId\":\"demo-1\",\"serviceName\":\"pay-svc\",\"serviceUrl\":\"http://127.0.0.1:8080/\",\"status\":\"OFFLINE\"}"`` |
| **触发僵尸实例驱逐清理** | `AdminLifecycleController` | `POST /sample/admin/lifecycle/zombies/clean` | ``curl -X POST http://127.0.0.1:8080/sample/admin/lifecycle/zombies/clean`` |
| **告警状态跃迁与卡片回溯** | `AdminAlertController` | `POST /sample/admin/alert/status-transition` & `GET /cards` | ``curl -X POST http://127.0.0.1:8080/sample/admin/alert/status-transition -H "Content-Type: application/json" -d "{\"instanceId\":\"demo-1\",\"status\":\"DOWN\",\"immediate\":true}"`` |
| **K8s 优雅下线发版静默** | `AdminAlertController` | `POST /sample/admin/alert/graceful-deregister` & `GET /state` | ``curl -X POST http://127.0.0.1:8080/sample/admin/alert/graceful-deregister -H "Content-Type: application/json" -d "{\"instanceId\":\"demo-1\"}"`` |
| **维护静默 CRUD 与审计** | `AdminSecurityConsoleController` | `GET / POST / DELETE /sample/admin/console/silences` | ``curl -X POST http://127.0.0.1:8080/sample/admin/console/silences -H "Content-Type: application/json" -d "{\"pattern\":\"order-*\",\"ttlSeconds\":300}"`` |
| **结构化脱敏操作审计检索** | `AdminSecurityConsoleController` | `GET /sample/admin/console/audits` | ``curl "http://127.0.0.1:8080/sample/admin/console/audits?action=SILENCE_CREATED&limit=10"`` |
| **高危端点与 RBAC 联合预检** | `AdminSecurityConsoleController` | `POST /sample/admin/console/endpoint-guard/evaluate` | ``curl -X POST http://127.0.0.1:8080/sample/admin/console/endpoint-guard/evaluate -H "Content-Type: application/json" -d "{\"method\":\"POST\",\"endpointUri\":\"/admin/instances/demo-1/actuator/loggers/ROOT\",\"role\":\"VIEWER\"}"`` |
| **服务端心跳与风暴指标摘要** | `AdminSecurityConsoleController` | `GET /sample/admin/console/observability/summary` | ``curl http://127.0.0.1:8080/sample/admin/console/observability/summary`` |
| **内置 RBAC 控制台端点** | `AdminConsoleController` | `GET / POST / DELETE /admin/console/{me,audits,silences}` | 需先经 `POST /admin/login` 获取 `satoken` Cookie 访问 |
| **自观测探针与低基数指标** | `AdminServerHealthIndicator` / `AdminMetricsBinder` | `GET /actuator/health` & `/actuator/metrics/ateng.admin.*` | ``curl -H "X-Actuator-Token: YOUR_ACTUATOR_TOKEN" http://127.0.0.1:8080/actuator/metrics/ateng.admin.instances.registered`` |

---

## 4. 全真自动化回归命令

```bash
# 阶梯二全量二阶回归（Windows 与 CI 环境建议加 -T 2）
mvn test -pl ateng-boot-admin,ateng-boot-samples/ateng-boot-sample-admin -am -T 2 -nsu -B
```

- **单独执行端到端闭环测试（PowerShell `-D` 参数需加双引号）**：
  ```powershell
  mvn test -pl ateng-boot-samples/ateng-boot-sample-admin -am "-Dtest=AdminRealClientEndToEndTest,SampleAdminWebControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false" -T 2 -nsu -B
  ```
- **宿主机虚拟网卡提示**：若宿主机存在 CGNAT 代理虚拟网卡（`100.64.x.x`），端到端测试已将客户端地址显式锚定为 `http://127.0.0.1:<port>`，确保不受 `100.64.0.0/10` 元数据防线误杀。

> 详细原理机制、全量配置字典与扩展定制，请参阅核心 Starter 文档：[ateng-boot-admin README](../../ateng-boot-admin/README.md)、[领域词汇表 (CONTEXT.md)](../../ateng-boot-admin/CONTEXT.md) 与 [架构决策记录 (ADR-0055)](../../docs/adr/0055-spring-boot-admin-production-monitoring-and-security-architecture.md)。
