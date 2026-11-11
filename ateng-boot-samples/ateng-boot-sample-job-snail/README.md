# ateng-boot-sample-job-snail 分布式任务调度与协同重试场景体验工程

`ateng-boot-sample-job-snail` 是 [`ateng-boot-job-snail`](../../ateng-boot-job-snail/README.md)（基于 SnailJob `1.10.0`）的 `1:1` 独立双模自验沙箱工程，按业务领域拆分为 **5 大 REST 控制器** 与 **8 个真实调度/重试执行器**，完整演示定时预热、JSR-303 参数防卫、MapReduce 动态切片规约、分布式协同重试、多租户全模态沙箱、流式分片死信补偿、三阶告警防风暴自愈、本地看门狗超时降级、运行时节点暂停/恢复与调度中心 OpenAPI 编排全链路能力。

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
| SnailJob 调度中心默认地址 | `127.0.0.1:17888` | 可通过 `application-local.yml` 覆盖为真实集群地址 |
| 暴露 Actuator 端点 | `health,info,metrics,snailjobs` | 包含双擎健康探针、Micrometer 指标及免服务端运维断点 |

---

## 2. 运行环境与双模切换

本样例工程采用同一套控制器与测试用例实现 **离线自洽（`!local`）** 与 **真实调度中心联调（`local`）** 双模无缝切换：

| 运行模式 | 激活方式 | 核心运行机制与适用场景 |
| :--- | :--- | :--- |
| **离线脱网自洽模式（默认）** | 默认 Profile（`!local`），直接运行 `mvn test` | `SampleJobSnailTestConfig` 自动为 `SnailJobAdminTemplate` 装配仿真 Mock 桩；调度/重试体验端点由 `SnailJobScenarioService` 通过 `SnailJobTestHelper` 纯内存反射触发，零外部服务端依赖即可 100% 跑通全部场景与断言 |
| **真实调度中心联调模式** | 复制并填写本地配置后追加 `--spring.profiles.active=local` | 经由 `spring.config.import: "optional:classpath:application-local.yml"` 动态加载本地配置，关闭 Mock 桩并对真实 SnailJob Server 执行 gRPC 心跳注册、日志镜像同步及 OpenAPI 远程管控 |

开启 `local` 真实联调前，请先复制模板生成本地配置（`.gitignore` 已忽略 `**/application-local*.yml`，严禁将真实主机 IP 与鉴权 Token 提交至仓库）：

```bash
cp ateng-boot-samples/ateng-boot-sample-job-snail/src/main/resources/application-local.example.yml \
   ateng-boot-samples/ateng-boot-sample-job-snail/src/main/resources/application-local.yml
```

按实际环境填充模板占位符后，以 `local` Profile 启动：

```bash
mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-job-snail -Dspring-boot.run.profiles=local
```

---

## 3. 核心 Controller 接口与一键体验

> 所有业务体验接口均遵循 `Result<T>` 显式强类型响应契约（`code = 200` 表示受理/校验通过），绝不向外部暴露底层实体模型。

### 3.1 调度触发与切片规约（`SnailJobTriggerController`：`/sample/job-snail/trigger`）

| 序号 | 场景说明 | HTTP 方法与路径 | 一键体验 `curl` 命令 |
| :--- | :--- | :--- | :--- |
| 1 | 系统冷启动热点缓存预热与健康巡检（无锁 MDC `traceId`） | `POST /sample/job-snail/trigger/warmup` | `curl -X POST http://localhost:8080/sample/job-snail/trigger/warmup` |
| 2 | 用户同步任务强类型入参 DTO 绑定与 `ValidatorUtil` 校验通过 | `POST /sample/job-snail/trigger/user-sync` | `curl -X POST http://localhost:8080/sample/job-snail/trigger/user-sync -H "Content-Type: application/json" -d '{"batchNo":"BATCH-20261111-01","batchSize":200,"departmentId":"DEPT-TECH"}'` |
| 3 | 用户同步任务触发 JSR-303 参数防卫 Fail-Fast（`batchSize=0` 拦截） | `POST /sample/job-snail/trigger/user-sync` | `curl -X POST http://localhost:8080/sample/job-snail/trigger/user-sync -H "Content-Type: application/json" -d '{"batchNo":"","batchSize":0}'` |
| 4 | 跨商户月度账单 MapReduce `splitTask` 动态范围切片分发（`doMap`） | `POST /sample/job-snail/trigger/bill-settlement/map` | `curl -X POST http://localhost:8080/sample/job-snail/trigger/bill-settlement/map -H "Content-Type: application/json" -d '{"dataRange":"1-1000","billingCycle":"2026-11"}'` |
| 5 | 跨商户月度账单 MapReduce 节点阶段 `@ReduceExecutor` 规约汇总 | `POST /sample/job-snail/trigger/bill-settlement/reduce` | `curl -X POST "http://localhost:8080/sample/job-snail/trigger/bill-settlement/reduce?param=summary"` |
| 6 | 跨商户月度账单 MapReduce 全局 `@MergeReduceExecutor` 最终合并对齐 | `POST /sample/job-snail/trigger/bill-settlement/merge-reduce` | `curl -X POST "http://localhost:8080/sample/job-snail/trigger/bill-settlement/merge-reduce?param=global-merge"` |
| 7 | 订单每日对账注入差错故障（激活 `SnailJobAlertAggregator` 首报防风暴聚合） | `POST /sample/job-snail/trigger/reconciliation` | `curl -X POST "http://localhost:8080/sample/job-snail/trigger/reconciliation?faultInjected=true"` |
| 8 | 订单每日对账平账成功（触发防风暴聚合状态机 `RECOVERY` 自愈恢复通知） | `POST /sample/job-snail/trigger/reconciliation` | `curl -X POST "http://localhost:8080/sample/job-snail/trigger/reconciliation?faultInjected=false"` |
| 9 | 历史交易冷数据归档 80ms 硬看门狗超时强中断与同 Bean `fallbackMethod` 就地降级 | `POST /sample/job-snail/trigger/timeout-archive` | `curl -X POST "http://localhost:8080/sample/job-snail/trigger/timeout-archive?param=archive-batch-2026"` |
| 10 | VIP 专线金丝雀灰度拦截探测（命中 `SampleAuditJobInterceptor` 返回 `false` 跳过） | `POST /sample/job-snail/trigger/canary-probe` | `curl -X POST http://localhost:8080/sample/job-snail/trigger/canary-probe` |
| 11 | 物流轨迹广播分片游标批处理（`shardIndex=0, shardTotal=2`，无故障正常消费） | `POST /sample/job-snail/trigger/logistics-batch` | `curl -X POST "http://localhost:8080/sample/job-snail/trigger/logistics-batch?shardIndex=0&shardTotal=2&injectFault=false"` |
| 12 | 物流轨迹广播分片批处理故障演练（`injectFault=true` 收集 `FailedBatch` 至死信清单） | `POST /sample/job-snail/trigger/logistics-batch` | `curl -X POST "http://localhost:8080/sample/job-snail/trigger/logistics-batch?shardIndex=0&shardTotal=1&injectFault=true"` |
| 13 | 单租户隔离模式调度（`JobTenantMode.PARAM` 自动提取并绑定租户上下文） | `POST /sample/job-snail/trigger/tenant/scoped` | `curl -X POST "http://localhost:8080/sample/job-snail/trigger/tenant/scoped?tenantId=tenant-shanghai"` |
| 14 | 多租户沙箱遍历模式（`JobTenantMode.ITERATE` 联动 `TenantJobTemplate` 逐租户独立执行） | `POST /sample/job-snail/trigger/tenant/iterate` | `curl -X POST http://localhost:8080/sample/job-snail/trigger/tenant/iterate` |
| 15 | 全局平台忽略租户归档模式（`JobTenantMode.IGNORE` 标记全局忽略租户限制） | `POST /sample/job-snail/trigger/tenant/global` | `curl -X POST http://localhost:8080/sample/job-snail/trigger/tenant/global` |

### 3.2 协同重试演练（`SnailJobRetryController`：`/sample/job-snail/retry`）

| 序号 | 场景说明 | HTTP 方法与路径 | 一键体验 `curl` 命令 |
| :--- | :--- | :--- | :--- |
| 16 | 声明式 `@AtengSnailRetry` 订单支付正常成功路径 | `POST /sample/job-snail/retry/pay` | `curl -X POST http://localhost:8080/sample/job-snail/retry/pay -H "Content-Type: application/json" -d '{"orderId":"ORDER-20261111-01","amount":199.50}'` |
| 17 | 声明式订单支付模拟网关超时故障（`orderId="FAIL_ORDER"` 触发异常并转入重试管道） | `POST /sample/job-snail/retry/pay` | `curl -X POST http://localhost:8080/sample/job-snail/retry/pay -H "Content-Type: application/json" -d '{"orderId":"FAIL_ORDER","amount":88.00}'` |
| 18 | 编程式 `SnailRetryTemplate.executeWithIdempotent` 带业务编号幂等闭包重试 | `POST /sample/job-snail/retry/pay/programmatic` | `curl -X POST "http://localhost:8080/sample/job-snail/retry/pay/programmatic?orderId=ORDER-PROG-1001"` |
| 19 | 异步/脱机失败场景通过 `SnailRetryTemplate.reportManualRetry` 对称手动上报 | `POST /sample/job-snail/retry/pay/manual-report` | `curl -X POST "http://localhost:8080/sample/job-snail/retry/pay/manual-report?orderId=ORDER-MANUAL-2001"` |
| 20 | 查询当前节点 `orderPayRetry` 重试场景累计尝试统计 | `GET /sample/job-snail/retry/stats` | `curl -X GET http://localhost:8080/sample/job-snail/retry/stats` |

### 3.3 节点级运行时动态治理（`SnailJobGovernorController`：`/sample/job-snail/governor`）

| 序号 | 场景说明 | HTTP 方法与路径 | 一键体验 `curl` 命令 |
| :--- | :--- | :--- | :--- |
| 21 | 单节点动态暂停调度器或冒号分级切片（如暂停 `billSettlementJob` 或 `billSettlementJob:splitTask`） | `POST /sample/job-snail/governor/pause/{jobName}` | `curl -X POST http://localhost:8080/sample/job-snail/governor/pause/billSettlementJob` |
| 22 | 单节点动态恢复指定调度任务放行执行 | `POST /sample/job-snail/governor/resume/{jobName}` | `curl -X POST http://localhost:8080/sample/job-snail/governor/resume/billSettlementJob` |
| 23 | 查询当前节点内存已暂停的任务清单与总数 | `GET /sample/job-snail/governor/paused-list` | `curl -X GET http://localhost:8080/sample/job-snail/governor/paused-list` |
| 24 | 一键恢复当前节点全部被暂停的调度与切片任务 | `POST /sample/job-snail/governor/resume-all` | `curl -X POST http://localhost:8080/sample/job-snail/governor/resume-all` |

### 3.4 调度中心 OpenAPI 编程式管理（`SnailJobAdminController`：`/sample/job-snail/admin`）

| 序号 | 场景说明 | HTTP 方法与路径 | 一键体验 `curl` 命令 |
| :--- | :--- | :--- | :--- |
| 25 | 编程式向调度中心新增调度任务（`@Valid AdminJobSaveDTO`） | `POST /sample/job-snail/admin/jobs` | `curl -X POST http://localhost:8080/sample/job-snail/admin/jobs -H "Content-Type: application/json" -d '{"jobName":"dailySettlementJob","executorInfo":"billSettlementJob","triggerType":1,"triggerInterval":"0 0 2 * * ?","routeKey":1,"description":"每日跨商户账单结算"}'` |
| 26 | 查询指定任务 ID 元数据详情 | `GET /sample/job-snail/admin/jobs/{jobId}` | `curl -X GET http://localhost:8080/sample/job-snail/admin/jobs/1001` |
| 27 | 即时手动触发指定调度任务 | `POST /sample/job-snail/admin/jobs/{jobId}/trigger` | `curl -X POST http://localhost:8080/sample/job-snail/admin/jobs/1001/trigger` |
| 28 | 删除指定调度任务 | `DELETE /sample/job-snail/admin/jobs/{jobId}` | `curl -X DELETE http://localhost:8080/sample/job-snail/admin/jobs/1001` |
| 29 | 查询工作流 DAG 批次执行详情 | `GET /sample/job-snail/admin/workflows/batch/{batchId}` | `curl -X GET http://localhost:8080/sample/job-snail/admin/workflows/batch/2001` |
| 30 | 即时触发指定工作流 DAG（同理支持 `/enable` 与 `/disable` 启停工作流） | `POST /sample/job-snail/admin/workflows/{workflowId}/trigger` | `curl -X POST http://localhost:8080/sample/job-snail/admin/workflows/2001/trigger` |
| 31 | 查询指定重试任务元数据详情 | `GET /sample/job-snail/admin/retry/{retryId}` | `curl -X GET http://localhost:8080/sample/job-snail/admin/retry/888` |
| 32 | 运维手动触发重试任务（同理支持 `POST /retry/{retryId}/status?status=1` 变更状态） | `POST /sample/job-snail/admin/retry/{retryId}/trigger` | `curl -X POST http://localhost:8080/sample/job-snail/admin/retry/888/trigger` |

### 3.5 死信切片运维与 Actuator 探针（`SnailJobDeadLetterController` & `/actuator`）

| 序号 | 场景说明 | HTTP 方法与路径 | 一键体验 `curl` 命令 |
| :--- | :--- | :--- | :--- |
| 33 | 回查流式批处理收集的 `FailedBatch` 死信切片清单 | `GET /sample/job-snail/dead-letter/batches` | `curl -X GET http://localhost:8080/sample/job-snail/dead-letter/batches` |
| 34 | 受控补偿重放全部死信切片并清理现场 | `POST /sample/job-snail/dead-letter/replay` | `curl -X POST http://localhost:8080/sample/job-snail/dead-letter/replay` |
| 35 | 手动清空当前节点缓存的死信切片队列 | `DELETE /sample/job-snail/dead-letter/batches` | `curl -X DELETE http://localhost:8080/sample/job-snail/dead-letter/batches` |
| 36 | Actuator 查询已装配任务、重试场景、在途计数与暂停清单 | `GET /actuator/snailjobs` | `curl -X GET http://localhost:8080/actuator/snailjobs` |
| 37 | Actuator 脱机手动触发指定任务并回显 `traceId` 与耗时 | `POST /actuator/snailjobs/{jobName}` | `curl -X POST http://localhost:8080/actuator/snailjobs/systemWarmupJob -H "Content-Type: application/json" -d '{"action":"trigger","args":"manual-check"}'` |
| 38 | Actuator 执行节点级治理（暂停指定任务或全量恢复） | `POST /actuator/snailjobs` | `curl -X POST http://localhost:8080/actuator/snailjobs -H "Content-Type: application/json" -d '{"action":"resumeAll"}'` |
| 39 | Actuator 双擎健康探针（检测服务端可达性、gRPC 通道与停机排空状态） | `GET /actuator/health` | `curl -X GET http://localhost:8080/actuator/health` |

---

## 4. 全真自动化回归命令

### 4.1 定向构建与离线全真测试（CI 默认模式）

在仓库根目录执行阶梯二协同准出命令，联动构建并运行 Starter 单元测试（**146** 项用例）与 Sample 全场景 Web/集成测试（**34** 项用例）：

```bash
mvn test -pl ateng-boot-job-snail,ateng-boot-samples/ateng-boot-sample-job-snail -am -T 1C -nsu -B
```

测试构成清单：

- [`SampleJobSnailIntegrationTest.java`](./src/test/java/io/github/atengk/boot/sample/job/snail/SampleJobSnailIntegrationTest.java)：14 项端到端全真场景实测（预热巡检、看门狗降级、JSR-303 参数防卫、物流流式分片、多租户三模、声明式/编程式/手动上报重试、防风暴自愈、MapReduce、责任链灰度拦截、运行时治理、`SnailJobScenarioService`、Actuator 端点）。
- [`SampleJobSnailWebControllerTest.java`](./src/test/java/io/github/atengk/boot/sample/job/snail/SampleJobSnailWebControllerTest.java)：20 项 `MockMvc` 全链 HTTP 契约实测，覆盖 5 大领域控制器全部端点的 `Result<T>` 契约校验、异常状态拦截与 JSR-303 快速失败校验。

### 4.2 真实服务端（`local` Profile）联调回归

当本地已配置好有效 `application-local.yml` 并连通真实 SnailJob 调度服务端时，可激活 `local` 模式复测 HTTP 契约（自动绕过 `@Profile("!local")` 离线 Mock 桩）：

```bash
mvn test -pl ateng-boot-samples/ateng-boot-sample-job-snail -Dtest=SampleJobSnailWebControllerTest -Dspring.profiles.active=local -nsu -B
```

> 延伸阅读：[**ateng-boot-job-snail Starter 五维契约文档**](../../ateng-boot-job-snail/README.md) ｜ [**ateng-boot-job-snail 领域词汇表**](../../ateng-boot-job-snail/CONTEXT.md)
