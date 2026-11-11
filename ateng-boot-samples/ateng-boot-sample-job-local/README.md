# ateng-boot-sample-job-local 单机定时任务体验工程

> 专注于演示与自验 `ateng-boot-job-local` 核心特性（声明式 `@LocalJob` 注解、四阶重叠并发防卫 `OverlappingStrategy`、硬超时强中断熔断与指数退避重试、强类型 DTO 参数校验、SaaS 多租户全模态隔离、轻量集群互斥防重锁、Spring 领域事件与群机器人告警桥接、云原生 Micrometer 指标度量、以及三大高内聚领域 Controller 与 Actuator 运维管控端点）在真实企业场景下的开箱使用。

---

## 1. 快速启动与服务端口

- **默认 HTTP 端口**：`8080`（可在 `application.yml` 中修改）
- **启动方式**：
  ```bash
  # 方式一：IDE 中运行 SampleJobLocalApplication.java 的 main 方法
  # 方式二：命令行启动
  mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-job-local
  ```

---

## 2. 运行环境与双模切换

- **默认 / CI 模式**：内置默认配置 `ateng.boot.job.local.cluster-lock.type: LOCAL` 单机内存互斥锁保底与自洽配置，无需任何外部中间件即可一键启动并 100% 离线跑通全部测试；
- **Local 真实模式**：若需验证 Redis 轻量集群互斥锁与原子 Lua 属主释放，复制 `src/main/resources/application-local.example.yml` 为 `application-local.yml` 并填入真实 Redis 连接信息（已由 `.gitignore` 忽略，CI 零污染风险），启动应用即可直连真实 Redis 联调。

---

## 3. 核心 Controller 接口与一键体验

能通过 HTTP 调用的功能已按业务领域拆分为三大高内聚 `@RestController`（严格恪守 Zero-Entity-Leakage，出参统一显式包装强类型 `Result<T>`）：

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **手动即时触发任务** | `LocalJobScheduleController` | `POST /sample/job-local/schedule/trigger/{jobName}` | `curl -X POST "http://localhost:8080/sample/job-local/schedule/trigger/dailyReconciliationJob"` |
| **带参触发数据归档 (合规入参)** | `LocalJobScheduleController` | `POST /sample/job-local/schedule/trigger/archiving` | `curl -X POST "http://localhost:8080/sample/job-local/schedule/trigger/archiving" -H "Content-Type: application/json" -d '{"tableName":"t_order_history","retentionDays":90,"batchSize":500,"dryRun":true}'` |
| **带参触发归档 (JSR-303 快速失败)** | `LocalJobScheduleController` | `POST /sample/job-local/schedule/trigger/archiving` | `curl -X POST "http://localhost:8080/sample/job-local/schedule/trigger/archiving" -H "Content-Type: application/json" -d '{"tableName":"","retentionDays":-5}'` |
| **多租户账单轮询 (ITERATE 模态)** | `LocalJobScheduleController` | `POST /sample/job-local/schedule/trigger/billing` | `curl -X POST "http://localhost:8080/sample/job-local/schedule/trigger/billing"` |
| **轻量集群互斥防重锁触发** | `LocalJobScheduleController` | `POST /sample/job-local/schedule/trigger/cluster-lock` | `curl -X POST "http://localhost:8080/sample/job-local/schedule/trigger/cluster-lock"` |
| **暂停任务周期自动调度** | `LocalJobManageController` | `POST /sample/job-local/manage/pause/{jobName}` | `curl -X POST "http://localhost:8080/sample/job-local/manage/pause/dailyReconciliationJob"` |
| **恢复任务周期自动调度** | `LocalJobManageController` | `POST /sample/job-local/manage/resume/{jobName}` | `curl -X POST "http://localhost:8080/sample/job-local/manage/resume/dailyReconciliationJob"` |
| **动态更新 Cron 表达式** | `LocalJobManageController` | `POST /sample/job-local/manage/update/cron` | `curl -X POST "http://localhost:8080/sample/job-local/manage/update/cron" -H "Content-Type: application/json" -d '{"jobName":"dailyReconciliationJob","cronExpression":"0 0 3 * * ?"}'` |
| **动态更新固定频率 (FixedRate)** | `LocalJobManageController` | `POST /sample/job-local/manage/update/rate` | `curl -X POST "http://localhost:8080/sample/job-local/manage/update/rate" -H "Content-Type: application/json" -d '{"jobName":"systemMetricsSamplingJob","fixedRate":"10s"}'` |
| **动态更新固定延迟 (FixedDelay)** | `LocalJobManageController` | `POST /sample/job-local/manage/update/delay` | `curl -X POST "http://localhost:8080/sample/job-local/manage/update/delay" -H "Content-Type: application/json" -d '{"jobName":"orderTimeoutCancelJob","fixedDelay":"15s"}'` |
| **编程式动态注册新 Cron 任务** | `LocalJobManageController` | `POST /sample/job-local/manage/dynamic/register` | `curl -X POST "http://localhost:8080/sample/job-local/manage/dynamic/register" -H "Content-Type: application/json" -d '{"jobName":"dynamicCleanJob","cron":"0 0 4 * * ?","description":"动态清理任务"}'` |
| **动态注销并移除受管任务** | `LocalJobManageController` | `DELETE /sample/job-local/manage/{jobName}` | `curl -X DELETE "http://localhost:8080/sample/job-local/manage/dynamicCleanJob"` |
| **巡检全量受管任务清单** | `LocalJobAuditController` | `GET /sample/job-local/audit/jobs` | `curl -X GET "http://localhost:8080/sample/job-local/audit/jobs"` |
| **查询单任务详细状态与配置** | `LocalJobAuditController` | `GET /sample/job-local/audit/jobs/{jobName}` | `curl -X GET "http://localhost:8080/sample/job-local/audit/jobs/dailyReconciliationJob"` |
| **回查最近执行审计历史日志** | `LocalJobAuditController` | `GET /sample/job-local/audit/jobs/{jobName}/logs` | `curl -X GET "http://localhost:8080/sample/job-local/audit/jobs/dailyReconciliationJob/logs?limit=5"` |
| **Cron 语法校验与时钟预演** | `LocalJobAuditController` | `POST /sample/job-local/audit/cron/preview` | `curl -X POST "http://localhost:8080/sample/job-local/audit/cron/preview" -H "Content-Type: application/json" -d '{"cron":"0 0 2 * * ?","count":3}'` |
| **滚动清理过期历史日志** | `LocalJobAuditController` | `DELETE /sample/job-local/audit/logs/expired` | `curl -X DELETE "http://localhost:8080/sample/job-local/audit/logs/expired?retention=7d"` |
| **Actuator 全量巡检端点** | `LocalJobEndpoint` | `GET /actuator/localjobs` | `curl -X GET "http://localhost:8080/actuator/localjobs"` |
| **Actuator 时序预演端点** | `LocalJobEndpoint` | `GET /actuator/localjobs?cron=...&count=...` | `curl -X GET "http://localhost:8080/actuator/localjobs?cron=0+0+2+*+*+?&count=3"` |
| **Actuator 即时触发/启停管控** | `LocalJobEndpoint` | `POST /actuator/localjobs/{jobName}` | `curl -X POST "http://localhost:8080/actuator/localjobs/dailyReconciliationJob" -H "Content-Type: application/json" -d '{"action":"trigger"}'` |

---

## 4. 全真自动化回归命令

```bash
mvn test -pl ateng-boot-job-local,ateng-boot-samples/ateng-boot-sample-job-local -am -T 1C -nsu -B
```

> 详细原理机制、配置清单与扩展定制，请参阅核心 Starter 文档：[ateng-boot-job-local README](../../ateng-boot-job-local/README.md)。
