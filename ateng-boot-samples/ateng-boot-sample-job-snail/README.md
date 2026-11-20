# ateng-boot-sample-job-snail 分布式任务调度与协同重试场景体验工程

`ateng-boot-sample-job-snail` 是 [`ateng-boot-job-snail`](../../ateng-boot-job-snail/README.md)（基于 SnailJob `1.10.0`）的 `1:1` 独立双模自验沙箱工程，按业务领域拆分为 **5 大 REST 控制器** 与 **9 个真实调度/重试执行器组件**，完整演示定时预热、JSR-303 参数防卫、独立 `@AtengSnailJob` 本地退避重试与告警粒度覆写、独立 `@AtengSnailRetry`（含 `value`/`scene` 别名与 `@Retryable` 兼容）、MapReduce 动态切片规约、分布式协同重试、多租户全模态沙箱（含 `Semaphore` 受管并发）、流式分片 `BatchFailureHandler` 死信补偿、三阶告警防风暴自愈、本地看门狗超时降级、运行时节点暂停/恢复与调度中心 OpenAPI 编排全链路能力。

> 本文档严格聚焦样例工程的启动、双模环境切换、场景接口一键体验与自动化回归命令；Starter 完整 API 契约、架构拓扑、配置属性字典与生产规范请查阅 [**ateng-boot-job-snail Starter 五维契约文档**](../../ateng-boot-job-snail/README.md)。

---

## 1. 快速启动与服务端口

在仓库根目录执行以下命令即可启动场景体验工程：

```bash
mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-job-snail
```

| 服务标识 / 配置项 | 默认值 | 说明 |
| :--- | :--- | :--- |
| 应用名（`spring.application.name`） | `ateng-boot-sample-job-snail` | 默认同步映射为 SnailJob 客户端默认分组与执行器身份 |
| Web REST & Actuator 端口 | `8080` | 承载 5 大场景演练控制器与 `/actuator/*` 监控端点 |
| SnailJob Netty/gRPC 客户端端口 | `17889` | 本地执行器监听调度中心下发调度/重试流量的通信端口 |
| SnailJob 调度中心默认地址 | `127.0.0.1:17888` | 可通过 `application-local.yml` 覆盖为真实单机或逗号分隔集群地址 |
| 重试上下文存储默认模式 | 单机有界 LRU 内存（`10000` 条） | 默认加载 `InMemorySnailRetryContextStore`；装配 `StringRedisTemplate` 后自动切换为 `RedisSnailRetryContextStore`（Fail-Open 降级） |
| 暴露 Actuator 端点 | `health,info,metrics,snailjobs` | 包含双擎非阻塞缓存健康探针、Micrometer 指标及免服务端运维断点 |

---

## 2. 运行环境与双模切换

本样例工程采用同一套控制器与测试用例实现 **离线自洽（`!local`）** 与 **真实服务端联调（`local`）** 双模无缝切换：

| 运行模式 | 激活方式 | 核心运行机制与适用场景 |
| :--- | :--- | :--- |
| **离线脱网自洽模式（默认）** | 默认 Profile（`!local`），直接运行 `mvn test` | `SampleJobSnailTestConfig` 自动为 `SnailJobAdminTemplate` 装配仿真 Mock 桩；重试上下文使用 `ReentrantLock` 保护的 `InMemorySnailRetryContextStore`；调度/重试体验端点由 `SnailJobScenarioService` 通过 `SnailJobTestHelper` 纯内存反射触发，零外部服务端依赖即可 100% 跑通全部场景与断言 |
| **真实调度中心 + Redis 集群联调模式** | 复制并填写本地配置后追加 `--spring.profiles.active=local` | 经由 `spring.config.import: "optional:classpath:application-local.yml"` 动态加载本地配置（涵盖 `ateng.boot.job.snail.*` 与 `spring.data.redis.*`），关闭 Mock 桩并对真实 SnailJob Server 执行 gRPC 心跳注册、日志镜像同步及 OpenAPI 远程管控；引入并配置 Redis 后，自动激活 `RedisSnailRetryContextStore`（跨 Pod 上下文保真，Redis 不可用时静默 Fail-Open 降级回内存） |

开启 `local` 真实联调前，请先复制模板生成本地配置（`.gitignore` 已忽略 `**/application-local*.yml`，严禁将真实主机 IP 与密码凭据提交至 Git）：

```bash
cp ateng-boot-samples/ateng-boot-sample-job-snail/src/main/resources/application-local.example.yml \
   ateng-boot-samples/ateng-boot-sample-job-snail/src/main/resources/application-local.yml
```

[`application-local.example.yml`](./src/main/resources/application-local.example.yml) 已内建 Redis（`spring.data.redis.*`）、SnailJob 连接与重试参数（`ateng.boot.job.snail.retry.{enabled,context-ttl,local-max-attempts,local-backoff}`）大写占位符模板；按实际环境填充后，以 `local` Profile 启动：

```bash
mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-job-snail -Dspring-boot.run.profiles=local
```

---

## 3. 核心 Controller 接口与一键体验

> 3.1 ~ 3.5 中 `SnailJob*Controller` 业务体验接口均遵循 `Result<T>` 显式强类型响应契约（`code = 200` 表示受理/校验通过），绝不向外部暴露底层实体模型；3.5 节末 `/actuator/*` 运维探针遵循 Spring Boot Actuator 原生 JSON 响应契约。

### 3.1 调度触发与切片规约（`SnailJobTriggerController`：`/sample/job-snail/trigger`）

| 序号 | 场景说明 | HTTP 方法与路径 | 一键体验 `curl` 命令 |
| :--- | :--- | :--- | :--- |
| 1 | 系统冷启动热点缓存预热与健康巡检（无锁 MDC `traceId`） | `POST /sample/job-snail/trigger/warmup` | `curl -X POST http://localhost:8080/sample/job-snail/trigger/warmup` |
| 2 | 用户同步任务强类型入参 DTO 绑定与 `ValidatorUtil` 校验通过 | `POST /sample/job-snail/trigger/user-sync` | `curl -X POST http://localhost:8080/sample/job-snail/trigger/user-sync -H "Content-Type: application/json" -d '{"batchNo":"BATCH-20261111-01","batchSize":200,"departmentId":"DEPT-TECH"}'` |
| 3 | 用户同步任务触发 JSR-303 参数防卫 Fail-Fast（`batchSize=0` 拦截） | `POST /sample/job-snail/trigger/user-sync` | `curl -X POST http://localhost:8080/sample/job-snail/trigger/user-sync -H "Content-Type: application/json" -d '{"batchNo":"","batchSize":0}'` |
| 4 | 独立 `@AtengSnailJob(retryCount=2, retryInterval="25ms")` 正常执行与瞬态失败本地退避重试自愈（`departmentId="TRANSIENT-FAIL-01"` 触发第 1 次失败、第 2 次自愈） | `POST /sample/job-snail/trigger/local-retry` | `curl -X POST http://localhost:8080/sample/job-snail/trigger/local-retry -H "Content-Type: application/json" -d '{"batchNo":"LOCAL-RETRY-01","batchSize":50,"departmentId":"TRANSIENT-FAIL-01"}'` |
| 5 | 跨商户月度账单 MapReduce `splitTask` 动态范围切片分发（`doMap`） | `POST /sample/job-snail/trigger/bill-settlement/map` | `curl -X POST http://localhost:8080/sample/job-snail/trigger/bill-settlement/map -H "Content-Type: application/json" -d '{"dataRange":"1-1000","billingCycle":"2026-11"}'` |
| 6 | 跨商户月度账单 MapReduce 节点阶段 `@ReduceExecutor` 规约汇总 | `POST /sample/job-snail/trigger/bill-settlement/reduce` | `curl -X POST "http://localhost:8080/sample/job-snail/trigger/bill-settlement/reduce?param=summary"` |
| 7 | 跨商户月度账单 MapReduce 全局 `@MergeReduceExecutor` 最终合并对齐 | `POST /sample/job-snail/trigger/bill-settlement/merge-reduce` | `curl -X POST "http://localhost:8080/sample/job-snail/trigger/bill-settlement/merge-reduce?param=global-merge"` |
| 8 | 订单每日对账注入差错故障（激活 `SnailJobAlertAggregator` 首报防风暴聚合，纯入参无状态驱动） | `POST /sample/job-snail/trigger/reconciliation` | `curl -X POST "http://localhost:8080/sample/job-snail/trigger/reconciliation?faultInjected=true"` |
| 9 | 订单每日对账平账成功（触发防风暴聚合状态机 `RECOVERY` 自愈恢复通知） | `POST /sample/job-snail/trigger/reconciliation` | `curl -X POST "http://localhost:8080/sample/job-snail/trigger/reconciliation?faultInjected=false"` |
| 10 | 历史交易冷数据归档 80ms 硬看门狗超时强中断与同 Bean `fallbackMethod` 就地降级 | `POST /sample/job-snail/trigger/timeout-archive` | `curl -X POST "http://localhost:8080/sample/job-snail/trigger/timeout-archive?param=archive-batch-2026"` |
| 11 | VIP 专线金丝雀灰度拦截探测（命中 `SampleAuditJobInterceptor` 返回 `false` 跳过） | `POST /sample/job-snail/trigger/canary-probe` | `curl -X POST http://localhost:8080/sample/job-snail/trigger/canary-probe` |
| 12 | 物流轨迹广播分片游标批处理（`shardIndex=0, shardTotal=2`，无故障正常消费） | `POST /sample/job-snail/trigger/logistics-batch` | `curl -X POST "http://localhost:8080/sample/job-snail/trigger/logistics-batch?shardIndex=0&shardTotal=2&injectFault=false"` |
| 13 | 物流轨迹广播分片批处理故障演练（`injectFault=true` 触发 `BatchFailureHandler` 收集 `FailedBatch` 至死信清单） | `POST /sample/job-snail/trigger/logistics-batch` | `curl -X POST "http://localhost:8080/sample/job-snail/trigger/logistics-batch?shardIndex=0&shardTotal=1&injectFault=true"` |
| 14 | 单租户隔离模式调度（`JobTenantMode.PARAM` 自动提取并绑定租户上下文） | `POST /sample/job-snail/trigger/tenant/scoped` | `curl -X POST "http://localhost:8080/sample/job-snail/trigger/tenant/scoped?tenantId=tenant-shanghai"` |
| 15 | 多租户沙箱顺序遍历模式（`JobTenantMode.ITERATE` 联动 `TenantJobTemplate` 逐租户独立事务执行） | `POST /sample/job-snail/trigger/tenant/iterate` | `curl -X POST http://localhost:8080/sample/job-snail/trigger/tenant/iterate` |
| 16 | 多租户受管并发背压遍历模式（`Semaphore` 限流 + 四维上下文透传 + `param=FAIL_ONE` 容错回调） | `POST /sample/job-snail/trigger/tenant/parallel` | `curl -X POST "http://localhost:8080/sample/job-snail/trigger/tenant/parallel?param=FAIL_ONE"` |
| 17 | 全局平台忽略租户归档模式（`JobTenantMode.IGNORE` 标记全局忽略租户限制） | `POST /sample/job-snail/trigger/tenant/global` | `curl -X POST http://localhost:8080/sample/job-snail/trigger/tenant/global` |

### 3.2 协同重试演练（`SnailJobRetryController`：`/sample/job-snail/retry`）

| 序号 | 场景说明 | HTTP 方法与路径 | 一键体验 `curl` 命令 |
| :--- | :--- | :--- | :--- |
| 18 | 独立声明式 `@AtengSnailRetry(scene="orderPayRetry")` 订单支付正常成功路径 | `POST /sample/job-snail/retry/pay` | `curl -X POST http://localhost:8080/sample/job-snail/retry/pay -H "Content-Type: application/json" -d '{"orderId":"ORDER-20261111-01","amount":199.50}'` |
| 19 | 声明式订单支付模拟网关超时故障（`orderId="FAIL_ORDER"` 触发异常并转入重试管道） | `POST /sample/job-snail/retry/pay` | `curl -X POST http://localhost:8080/sample/job-snail/retry/pay -H "Content-Type: application/json" -d '{"orderId":"FAIL_ORDER","amount":88.00}'` |
| 20 | 独立声明式 `@AtengSnailRetry(value="orderRefundRetry")` `value` 别名退款重试演示 | `POST /sample/job-snail/retry/refund` | `curl -X POST "http://localhost:8080/sample/job-snail/retry/refund?refundNo=REFUND-ALIAS-1001&amount=66.00"` |
| 21 | `@AtengSnailRetry` 与原生 `@Retryable` 混用向后兼容（显式 `@Retryable` 场景优先） | `POST /sample/job-snail/retry/legacy-compat` | `curl -X POST "http://localhost:8080/sample/job-snail/retry/legacy-compat?orderId=ORD-COMPAT-1001"` |
| 22 | 编程式 `SnailRetryTemplate.executeWithIdempotent` 带业务编号幂等闭包重试 | `POST /sample/job-snail/retry/pay/programmatic` | `curl -X POST "http://localhost:8080/sample/job-snail/retry/pay/programmatic?orderId=ORDER-PROG-1001"` |
| 23 | 异步/脱机失败场景通过 `SnailRetryTemplate.reportManualRetry` 对称手动上报 | `POST /sample/job-snail/retry/pay/manual-report` | `curl -X POST "http://localhost:8080/sample/job-snail/retry/pay/manual-report?orderId=ORDER-MANUAL-2001"` |
| 24 | 查询当前节点 `orderPayRetry` 重试场景累计尝试统计 | `GET /sample/job-snail/retry/stats` | `curl -X GET http://localhost:8080/sample/job-snail/retry/stats` |

### 3.3 节点级运行时动态治理（`SnailJobGovernorController`：`/sample/job-snail/governor`）

| 序号 | 场景说明 | HTTP 方法与路径 | 一键体验 `curl` 命令 |
| :--- | :--- | :--- | :--- |
| 25 | 单节点动态暂停调度器或冒号分级切片（如暂停 `billSettlementJob` 或 `billSettlementJob:splitTask`） | `POST /sample/job-snail/governor/pause/{jobName}` | `curl -X POST http://localhost:8080/sample/job-snail/governor/pause/billSettlementJob` |
| 26 | 单节点动态恢复指定调度任务放行执行 | `POST /sample/job-snail/governor/resume/{jobName}` | `curl -X POST http://localhost:8080/sample/job-snail/governor/resume/billSettlementJob` |
| 27 | 查询当前节点内存已暂停的任务清单与总数 | `GET /sample/job-snail/governor/paused-list` | `curl -X GET http://localhost:8080/sample/job-snail/governor/paused-list` |
| 28 | 一键恢复当前节点全部被暂停的调度与切片任务 | `POST /sample/job-snail/governor/resume-all` | `curl -X POST http://localhost:8080/sample/job-snail/governor/resume-all` |

### 3.4 调度中心 OpenAPI 编程式管理（`SnailJobAdminController`：`/sample/job-snail/admin`）

| 序号 | 场景说明 | HTTP 方法与路径 | 一键体验 `curl` 命令 |
| :--- | :--- | :--- | :--- |
| 29 | 编程式向调度中心新增调度任务（`@Valid AdminJobSaveDTO`） | `POST /sample/job-snail/admin/jobs` | `curl -X POST http://localhost:8080/sample/job-snail/admin/jobs -H "Content-Type: application/json" -d '{"jobName":"dailySettlementJob","executorInfo":"billSettlementJob","triggerType":1,"triggerInterval":"0 0 2 * * ?","routeKey":1,"description":"每日跨商户账单结算"}'` |
| 30 | 编程式更新调度中心任务配置（需携带 `id`） | `PUT /sample/job-snail/admin/jobs` | `curl -X PUT http://localhost:8080/sample/job-snail/admin/jobs -H "Content-Type: application/json" -d '{"id":1001,"jobName":"dailySettlementJob","executorInfo":"billSettlementJob","triggerType":1,"triggerInterval":"0 0 3 * * ?","routeKey":1,"description":"每日跨商户账单结算更新"}'` |
| 31 | 查询指定任务 ID 元数据详情 | `GET /sample/job-snail/admin/jobs/{jobId}` | `curl -X GET http://localhost:8080/sample/job-snail/admin/jobs/1001` |
| 32 | 即时手动触发指定调度任务 | `POST /sample/job-snail/admin/jobs/{jobId}/trigger` | `curl -X POST http://localhost:8080/sample/job-snail/admin/jobs/1001/trigger` |
| 33 | 切换指定调度任务运行状态码（`0=禁用`、`1=启用`） | `POST /sample/job-snail/admin/jobs/{jobId}/status` | `curl -X POST "http://localhost:8080/sample/job-snail/admin/jobs/1001/status?status=1"` |
| 34 | 对称启用指定调度任务 | `POST /sample/job-snail/admin/jobs/{jobId}/enable` | `curl -X POST http://localhost:8080/sample/job-snail/admin/jobs/1001/enable` |
| 35 | 对称停用指定调度任务 | `POST /sample/job-snail/admin/jobs/{jobId}/disable` | `curl -X POST http://localhost:8080/sample/job-snail/admin/jobs/1001/disable` |
| 36 | 删除指定调度任务 | `DELETE /sample/job-snail/admin/jobs/{jobId}` | `curl -X DELETE http://localhost:8080/sample/job-snail/admin/jobs/1001` |
| 37 | 查询工作流 DAG 批次执行详情 | `GET /sample/job-snail/admin/workflows/batch/{batchId}` | `curl -X GET http://localhost:8080/sample/job-snail/admin/workflows/batch/2001` |
| 38 | 即时触发指定工作流 DAG | `POST /sample/job-snail/admin/workflows/{workflowId}/trigger` | `curl -X POST http://localhost:8080/sample/job-snail/admin/workflows/2001/trigger` |
| 39 | 启用指定工作流 DAG | `POST /sample/job-snail/admin/workflows/{workflowId}/enable` | `curl -X POST http://localhost:8080/sample/job-snail/admin/workflows/2001/enable` |
| 40 | 停用指定工作流 DAG | `POST /sample/job-snail/admin/workflows/{workflowId}/disable` | `curl -X POST http://localhost:8080/sample/job-snail/admin/workflows/2001/disable` |
| 41 | 删除指定工作流 DAG | `DELETE /sample/job-snail/admin/workflows/{workflowId}` | `curl -X DELETE http://localhost:8080/sample/job-snail/admin/workflows/2001` |
| 42 | 查询指定重试任务元数据详情 | `GET /sample/job-snail/admin/retry/{retryId}` | `curl -X GET http://localhost:8080/sample/job-snail/admin/retry/888` |
| 43 | 运维手动触发重试任务 | `POST /sample/job-snail/admin/retry/{retryId}/trigger` | `curl -X POST http://localhost:8080/sample/job-snail/admin/retry/888/trigger` |
| 44 | 运维变更重试任务状态码（如启用/暂停） | `POST /sample/job-snail/admin/retry/{retryId}/status` | `curl -X POST "http://localhost:8080/sample/job-snail/admin/retry/888/status?status=1"` |

### 3.5 死信切片运维与 Actuator 探针（`SnailJobDeadLetterController` & `/actuator`）

| 序号 | 场景说明 | HTTP 方法与路径 | 一键体验 `curl` 命令 |
| :--- | :--- | :--- | :--- |
| 45 | 回查流式批处理收集的 `FailedBatch` 死信切片清单 | `GET /sample/job-snail/dead-letter/batches` | `curl -X GET http://localhost:8080/sample/job-snail/dead-letter/batches` |
| 46 | 受控原子摘取并重放全部死信切片（保留仍失败切片） | `POST /sample/job-snail/dead-letter/replay` | `curl -X POST http://localhost:8080/sample/job-snail/dead-letter/replay` |
| 47 | 手动清空当前节点缓存的死信切片队列 | `DELETE /sample/job-snail/dead-letter/batches` | `curl -X DELETE http://localhost:8080/sample/job-snail/dead-letter/batches` |
| 48 | Actuator 查询已装配任务、重试场景、在途计数与暂停清单（原生 JSON） | `GET /actuator/snailjobs` | `curl -X GET http://localhost:8080/actuator/snailjobs` |
| 49 | Actuator 脱机手动触发指定任务并回显 `traceId` 与耗时（原生 JSON） | `POST /actuator/snailjobs/{jobName}` | `curl -X POST http://localhost:8080/actuator/snailjobs/systemWarmupJob -H "Content-Type: application/json" -d '{"action":"trigger","args":"manual-check"}'` |
| 50 | Actuator 执行节点级治理（暂停指定任务或全量恢复，原生 JSON） | `POST /actuator/snailjobs` | `curl -X POST http://localhost:8080/actuator/snailjobs -H "Content-Type: application/json" -d '{"action":"resumeAll"}'` |
| 51 | Actuator 双擎非阻塞缓存健康探针（多活节点降级透出 `degraded`、在线/离线列表与停机排空态，原生 JSON） | `GET /actuator/health` | `curl -X GET http://localhost:8080/actuator/health` |

---

## 4. 全真自动化回归命令

### 4.1 定向构建与离线全真测试（CI 默认模式）

在仓库根目录执行阶梯二协同准出命令，联动验证 Starter 单元测试（**190** 项，`20` 个测试类）与 Sample 场景测试（**42** 项，合计 **232** 项）：

```bash
mvn test -pl ateng-boot-job-snail,ateng-boot-samples/ateng-boot-sample-job-snail -am -T 1C -nsu -B
```

- [`SampleJobSnailIntegrationTest.java`](./src/test/java/io/github/atengk/boot/sample/job/snail/SampleJobSnailIntegrationTest.java)（**17** 项）：覆盖 `systemWarmupJob`、`timeoutGuardedJob`、JSR-303 防卫、独立 `@AtengSnailJob` 本地退避重试（含 JSR-303 快速失败零重试与重试耗尽清理）、物流流式分片（含 `BatchFailureHandler`）、多租户三模与受管并发背压（`tenantParallelBatchJob`）、独立 `@AtengSnailRetry`（`scene`/`value` 别名与 `@Retryable` 混用显式优先）、告警自愈、MapReduce、灰度拦截、节点治理、`SnailJobScenarioService` 与 Actuator 端点。
- [`SampleJobSnailWebControllerTest.java`](./src/test/java/io/github/atengk/boot/sample/job/snail/SampleJobSnailWebControllerTest.java)（**25** 项）：基于 `MockMvc` 覆盖 5 大控制器全部 HTTP 端点（含 `/trigger/local-retry`、`/trigger/tenant/parallel`、`/retry/refund`、`/retry/legacy-compat`、`/admin/jobs/{jobId}/status|enable|disable`）、JSR-303 快速失败与 OpenAPI 异常透明抛出契约。

### 4.2 真实服务端（`local` Profile）联调回归

当本地已配置好有效 `application-local.yml` 并连通真实 SnailJob 调度服务端（与可选 Redis）时，可激活 `local` 模式复测 HTTP 契约（自动绕过 `@Profile("!local")` 离线 Mock 桩）：

```bash
mvn test -pl ateng-boot-samples/ateng-boot-sample-job-snail -Dtest=SampleJobSnailWebControllerTest -Dspring.profiles.active=local -nsu -B
```

> 延伸阅读：[**ateng-boot-job-snail Starter 五维契约文档**](../../ateng-boot-job-snail/README.md) ｜ [**ateng-boot-job-snail 领域词汇表**](../../ateng-boot-job-snail/CONTEXT.md) ｜ [**ADR-0104 架构决策记录**](../../docs/adr/0104-snail-job-sample-multi-controller-and-documentation-specification-architecture.md)
