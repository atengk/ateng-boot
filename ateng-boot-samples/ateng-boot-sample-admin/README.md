# ateng-boot-sample-admin

> `ateng-boot-admin` 的 1:1 生产级运维监控看板与安全防御体验工程（无重型持久化中间件，纯 JVM + 内嵌 Tomcat 默认端口 `8080` 即开即用，双模覆盖离线自测与真实 SBA Client `RANDOM_PORT` 端到端闭环）。

---

## 1. 场景体验清单

本样例引入 `ateng-boot-admin`、`ateng-boot-observability`、`ateng-boot-web`、`ateng-boot-notification`、`spring-data-redis`（类型定义，无 Redis 自动降级内存模式）及 `spring-boot-admin-starter-client`（[application.yml](./src/main/resources/application.yml) 默认配置 `spring.boot.admin.client.enabled: false` 关闭自动注册，由端到端测试按需激活），按业务职责拆分为 **5 大领域控制器** 与配套真实集成测试：

1. **安全准入与控制台认证域（`AdminAdmissionController` — `/sample/admin/admission`）**：
   - `POST /evaluate`：触发基于 `AdminInstanceAdmissionPolicy` SPI 的服务名黑名单、URL 白名单、协议头及私网/保留地址准入评估；
   - `POST /auth-verify`：演练 `SampleAdminAuthProvider` 冻结账号拦截与单字符用户名脱敏摘要返回；
   - `GET /normalize-path`：验证 `AdminPathMatcher.normalizeContextPath` 路径规范化防绕过语义。
2. **端点治理、出站凭据与日志域（`AdminGovernanceController` — `/sample/admin/governance`）**：
   - `GET /write-guard` & `POST /write-guard`：实时查询并切换单机沙箱 `enable-write-operations` 只读守卫状态与阻断预判；
   - `POST /headers/preview`：演练 `AtengActuatorHttpHeadersProvider` 按 `Basic -> Bearer/JWT -> 服务级 Token -> 全局兜底 Token` 级联推导标头（密文自动掩码）；
   - `GET /loggers` & `POST /loggers`：查询并动态调整指定 Logger 的运行时 Logback 日志级别。
3. **实例生命周期与僵尸清理域（`AdminLifecycleController` — `/sample/admin/lifecycle`）**：
   - `POST /instances/mock` & `GET /instances`：向内存仓库登记与查询脱敏 `AdminInstanceSummaryVO` 列表；
   - `POST /zombies/clean`：受 `ReentrantLock` 串行保护，触发现役 `ZombieInstanceCleanupScheduler` 驱逐超时 `OFFLINE`/`UNKNOWN` 僵尸实例。
4. **告警状态机与卡片回溯域（`AdminAlertController` — `/sample/admin/alert`）**：
   - `POST /status-transition`：模拟 `UP -> DOWN -> UP` 瞬时自愈消抖与确认故障卡片投递；
   - `POST /graceful-deregister`：验证 Kubernetes 优雅下线触发的 60 秒发版静默窗口；
   - `GET /state` & `GET /cards`：检视防抖待决队列、已告警实例、发版静默窗口与 `@Profile("!local")` 内存录制的脱敏通知卡片。
5. **控制台静默、审计检索、高危端点/RBAC 预检与自观测域（`AdminSecurityConsoleController` — `/sample/admin/console`）**：
   - `POST /silences`、`GET /silences`、`DELETE /silences/{id}`：动态创建、列举与撤销维护静默规则（创建人自动脱敏）并追加结构化操作审计；
   - `GET /audits`：按审计动作与条数上限检索脱敏审计轨迹；
   - `POST /endpoint-guard/evaluate`：联合预检 `blocked-endpoints`（如 `heapdump`）、`enable-write-operations` 及模拟控制台角色（如 `VIEWER` 对 `POST /loggers/ROOT`）；
   - `GET /observability/summary`：聚合返回各状态实例分布、最新心跳时间戳、防抖队列、雪崩折叠当前窗口数/阈值、持续重提醒开关与维护静默统计。
6. **真实 SBA Client 端到端全链路闭环（`AdminRealClientEndToEndTest`）**：
   - 启动 `RANDOM_PORT` 真实 Servlet 容器，启用 `spring.boot.admin.client.enabled=true` 并动态绑定 `http://127.0.0.1:<port>`；
   - 验证真实 Client 凭 `X-Admin-Client-Secret` 自动完成 HTTP 注册与 `actuator-token` 元数据注入、未授权直连 `/actuator/env` 被拦截、SBA 反代穿透 `/actuator/env`、`heapdump` 返回 `403`、`VIEWER` 禁写（`403`）、`ADMIN` 维护静默与审计查询，以及 `/actuator/health`（`adminServer`）与 `/actuator/metrics/ateng.admin.*` 闭环。

---

## 2. 一键启动与运行测试（默认端口 `8080` / 测试 `RANDOM_PORT`）

### 2.1 运行全量二阶回归测试（100% 离线自洽）

在仓库根目录执行（Windows 与 CI 环境建议加 `-T 2`）：

```bash
mvn test -pl ateng-boot-admin,ateng-boot-samples/ateng-boot-sample-admin -am -T 2 -nsu -B
```

或单独运行真实端到端闭环与样例控制器测试（PowerShell 下 `-D` 参数需加双引号）：

```powershell
mvn test -pl ateng-boot-samples/ateng-boot-sample-admin -am "-Dtest=AdminRealClientEndToEndTest,SampleAdminWebControllerTest" "-Dsurefire.failIfNoSpecifiedTests=false" -T 2 -nsu -B
```

### 2.2 启动独立样例进程与本地可选联调配置

启动 `AdminSampleApplication` 后（默认 HTTP 端口 `8080`，SBA 看板挂载路径 `/admin`）：
- 如需接入本地真实 Webhook 机器人或外部 Redis，复制模板 [application-local.example.yml](./src/main/resources/application-local.example.yml) 为 `application-local.yml` 并激活 `--spring.profiles.active=local`（`application-local.yml` 已被 `.gitignore` 忽略）。

---

## 3. 核心端点与 cURL 体验速览（默认基准 `http://127.0.0.1:8080`）

| 所属分组 | 方法与路由 | cURL 示例 | 核心能力说明 |
| :--- | :--- | :--- | :--- |
| 准入域 | `POST /sample/admin/admission/evaluate` | ``curl -X POST http://127.0.0.1:8080/sample/admin/admission/evaluate -H "Content-Type: application/json" -d "{\"serviceName\":\"order-svc\",\"serviceUrl\":\"http://127.0.0.1:8080/\",\"clientSecret\":\"YOUR_SECRET\"}"`` | SSRF 与准入策略预检 |
| 准入域 | `POST /sample/admin/admission/auth-verify` | ``curl -X POST http://127.0.0.1:8080/sample/admin/admission/auth-verify -H "Content-Type: application/json" -d "{\"username\":\"admin\",\"password\":\"admin\"}"`` | 控制台认证 SPI 与脱敏回显 |
| 治理域 | `GET / POST /sample/admin/governance/write-guard` | ``curl -X POST http://127.0.0.1:8080/sample/admin/governance/write-guard -H "Content-Type: application/json" -d "{\"enableWriteOperations\":false}"`` | 查询与演练全局写守卫开关 |
| 治理域 | `POST /sample/admin/governance/headers/preview` | ``curl -X POST http://127.0.0.1:8080/sample/admin/governance/headers/preview -H "Content-Type: application/json" -d "{\"serviceName\":\"pay-svc\"}"`` | 出站凭据级联推导（掩码输出） |
| 治理域 | `GET / POST /sample/admin/governance/loggers` | ``curl "http://127.0.0.1:8080/sample/admin/governance/loggers?loggerName=io.github.atengk"`` | 查询与调整 Logback 级别 |
| 周期域 | `POST /sample/admin/lifecycle/instances/mock` | ``curl -X POST http://127.0.0.1:8080/sample/admin/lifecycle/instances/mock -H "Content-Type: application/json" -d "{\"instanceId\":\"demo-1\",\"serviceName\":\"pay-svc\",\"serviceUrl\":\"http://127.0.0.1:8080/\",\"status\":\"OFFLINE\"}"`` | 登记模拟业务/僵尸实例 |
| 周期域 | `POST /sample/admin/lifecycle/zombies/clean` | ``curl -X POST http://127.0.0.1:8080/sample/admin/lifecycle/zombies/clean`` | 触发僵尸实例扫描与驱逐 |
| 告警域 | `POST /sample/admin/alert/status-transition` | ``curl -X POST http://127.0.0.1:8080/sample/admin/alert/status-transition -H "Content-Type: application/json" -d "{\"instanceId\":\"demo-1\",\"status\":\"DOWN\",\"immediate\":true}"`` | 触发状态跃迁与防抖/卡片通知 |
| 告警域 | `POST /sample/admin/alert/graceful-deregister` | ``curl -X POST http://127.0.0.1:8080/sample/admin/alert/graceful-deregister -H "Content-Type: application/json" -d "{\"instanceId\":\"demo-1\"}"`` | 演练 60s 优雅下线发版静默 |
| 控制台 | `GET/POST/DELETE /sample/admin/console/silences` | ``curl -X POST http://127.0.0.1:8080/sample/admin/console/silences -H "Content-Type: application/json" -d "{\"pattern\":\"order-*\",\"ttlSeconds\":300}"`` | 动态维护静默 CRUD 与审计 |
| 控制台 | `GET /sample/admin/console/audits` | ``curl "http://127.0.0.1:8080/sample/admin/console/audits?action=SILENCE_CREATED&limit=10"`` | 检索结构化脱敏操作审计 |
| 控制台 | `POST /sample/admin/console/endpoint-guard/evaluate` | ``curl -X POST http://127.0.0.1:8080/sample/admin/console/endpoint-guard/evaluate -H "Content-Type: application/json" -d "{\"method\":\"POST\",\"endpointUri\":\"/admin/instances/demo-1/actuator/loggers/ROOT\",\"role\":\"VIEWER\"}"`` | 联合预检高危端点与 RBAC |
| 控制台 | `GET /sample/admin/console/observability/summary` | ``curl http://127.0.0.1:8080/sample/admin/console/observability/summary`` | 心跳时间戳、状态分布与风暴摘要 |
| 原生控制台 | `GET/POST/DELETE /admin/console/silences` 等 | 需先经 `POST /admin/login` 获取 Cookie | 框架内置 RBAC 受控 REST 端点 |
| 自观测 | `GET /actuator/health` & `/actuator/metrics/ateng.admin.*` | ``curl -H "X-Actuator-Token: YOUR_ACTUATOR_TOKEN" http://127.0.0.1:8080/actuator/metrics/ateng.admin.instances.registered`` | `adminServer` 探针与低基数指标 |

---

## 4. 排错与隔离注意事项

- **为什么引入了 `spring-boot-admin-starter-client` 但日常启动不会报错连接 8080？**：默认 [application.yml](./src/main/resources/application.yml) 显式配置了 `spring.boot.admin.client.enabled: false` 与 `spring.boot.admin.context-path: /admin`，仅在 [AdminRealClientEndToEndTest](./src/test/java/io/github/atengk/boot/sample/admin/AdminRealClientEndToEndTest.java) 中按需开启 `RANDOM_PORT` 客户端闭环。
- **为什么在宿主机存在虚拟网卡或代理 IP（如 `100.64.x.x`）时真实端到端测试依然稳定？**：端到端测试显式将客户端 `serviceUrl`/`managementUrl`/`healthUrl` 锚定为 `http://127.0.0.1:<serverPort>` 环回地址，避免触发 `DefaultAdminInstanceAdmissionPolicy` 对 CGNAT `100.64.0.0/10` 网卡段的安全阻断。
- **扩展阅读**：完整 Starter 配置矩阵与生产实战说明见 [ateng-boot-admin README](../../ateng-boot-admin/README.md)，领域词汇与架构决策见 [CONTEXT.md](../../ateng-boot-admin/CONTEXT.md) 与 [ADR-0055](../../docs/adr/0055-spring-boot-admin-production-monitoring-and-security-architecture.md)。
