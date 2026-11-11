# ateng-boot-sample-job-xxl 分布式任务调度体验工程

> 专注于演示与自验 `ateng-boot-job-xxl` 核心特性（方法级 `@AtengXxlJob` 生产增强注解、三阶平滑停机排空状态机、本地硬看门狗与就地降级补偿、任务切面责任链拦截器 SPI、节点级任务运行时治理总线、SLF4J 动态日志双写镜像、四维上下文与多租户透传、大数据游标流式分片防 OOM 模板与死信收集、多租户受管并发遍历、OpenAPI 编程式管理门面以及 4 大专职领域 Controller）在真实企业场景下的开箱使用。

---

## 1. 快速启动与服务端口

- **默认 HTTP 端口**：`8080`（可在 `application.yml` 中修改）
- **执行器默认通信端口**：`9999`（支持端口冲突自动顺延自愈）
- **启动方式**：
  ```bash
  # 方式一：IDE 中运行 SampleJobXxlApplication.java 的 main 方法
  # 方式二：命令行启动
  mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-job-xxl
  ```

---

## 2. 运行环境与双模切换

- **默认 / CI 模式**：内置默认自洽配置与轻量 Mock Admin 服务桩，无需启动外部 XXL-Job 调度中心集群即可一键启动并 100% 离线跑通全部集成测试；
- **Local 真实模式**：若需连接真实 XXL-Job 调度中心集群，复制 `src/main/resources/application-local.example.yml` 为 `application-local.yml` 并填入真实调度中心地址与 Token（已被 `.gitignore` 忽略，CI 零污染风险），激活 `local` Profile 启动即可直连真实环境联调。

---

## 3. 核心 Controller 接口与一键体验

能通过 HTTP 调用的功能已按业务能力拆分为四大高内聚 `@RestController`（严格恪守 Zero-Entity-Leakage 传输隔离铁律，出参统一显式包装强类型 `Result<T>`）：

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **系统热点缓存预热巡检** | `XxlJobTriggerController` | `POST /sample/job-xxl/trigger/warmup` | `curl -X POST "http://localhost:8080/sample/job-xxl/trigger/warmup"` |
| **用户同步 (合规参数)** | `XxlJobTriggerController` | `POST /sample/job-xxl/trigger/user-sync` | `curl -X POST "http://localhost:8080/sample/job-xxl/trigger/user-sync" -H "Content-Type: application/json" -d '{"batchNo":"BATCH-20261111-001","batchSize":300,"tenantId":"TENANT-1001"}'` |
| **用户同步 (JSR-303 快速失败)** | `XxlJobTriggerController` | `POST /sample/job-xxl/trigger/user-sync` | `curl -X POST "http://localhost:8080/sample/job-xxl/trigger/user-sync" -H "Content-Type: application/json" -d '{"batchNo":"","batchSize":9999}'` |
| **大数据分片广播批处理** | `XxlJobTriggerController` | `POST /sample/job-xxl/trigger/sharding-batch` | `curl -X POST "http://localhost:8080/sample/job-xxl/trigger/sharding-batch?shardIndex=0&shardTotal=2"` |
| **订单对账正常平账** | `XxlJobTriggerController` | `POST /sample/job-xxl/trigger/reconciliation` | `curl -X POST "http://localhost:8080/sample/job-xxl/trigger/reconciliation?hasDiff=false"` |
| **订单对账模拟差错告警自愈** | `XxlJobTriggerController` | `POST /sample/job-xxl/trigger/reconciliation` | `curl -X POST "http://localhost:8080/sample/job-xxl/trigger/reconciliation?hasDiff=true"` |
| **历史冷数据归档超时降级** | `XxlJobTriggerController` | `POST /sample/job-xxl/trigger/timeout-archive` | `curl -X POST "http://localhost:8080/sample/job-xxl/trigger/timeout-archive"` |
| **租户全局归档 (IGNORE 模态)** | `XxlJobTriggerController` | `POST /sample/job-xxl/trigger/tenant/global` | `curl -X POST "http://localhost:8080/sample/job-xxl/trigger/tenant/global"` |
| **单租户隔离账单 (PARAM 模态)** | `XxlJobTriggerController` | `POST /sample/job-xxl/trigger/tenant/scoped` | `curl -X POST "http://localhost:8080/sample/job-xxl/trigger/tenant/scoped?tenantId=TENANT-888"` |
| **多租户并发遍历 (ITERATE 模态)** | `XxlJobTriggerController` | `POST /sample/job-xxl/trigger/tenant/parallel` | `curl -X POST "http://localhost:8080/sample/job-xxl/trigger/tenant/parallel"` |
| **暂停单节点任务执行** | `XxlJobGovernorController` | `POST /sample/job-xxl/governor/pause/{handler}` | `curl -X POST "http://localhost:8080/sample/job-xxl/governor/pause/pausableTaskJob"` |
| **恢复单节点任务执行** | `XxlJobGovernorController` | `POST /sample/job-xxl/governor/resume/{handler}` | `curl -X POST "http://localhost:8080/sample/job-xxl/governor/resume/pausableTaskJob"` |
| **巡检单节点静默任务清单** | `XxlJobGovernorController` | `GET /sample/job-xxl/governor/paused-handlers` | `curl -X GET "http://localhost:8080/sample/job-xxl/governor/paused-handlers"` |
| **编程式向 Admin 注册任务** | `XxlJobAdminController` | `POST /sample/job-xxl/admin/jobs` | `curl -X POST "http://localhost:8080/sample/job-xxl/admin/jobs" -H "Content-Type: application/json" -d '{"jobGroup":1,"name":"动态报表任务","executorHandler":"reportJob","scheduleType":"CRON","scheduleConf":"0 0 3 * * ?"}'` |
| **编程式启动调度任务** | `XxlJobAdminController` | `POST /sample/job-xxl/admin/jobs/{jobId}/start` | `curl -X POST "http://localhost:8080/sample/job-xxl/admin/jobs/101/start"` |
| **编程式停止调度任务** | `XxlJobAdminController` | `POST /sample/job-xxl/admin/jobs/{jobId}/stop` | `curl -X POST "http://localhost:8080/sample/job-xxl/admin/jobs/101/stop"` |
| **编程式即时触发调度任务** | `XxlJobAdminController` | `POST /sample/job-xxl/admin/jobs/{jobId}/trigger` | `curl -X POST "http://localhost:8080/sample/job-xxl/admin/jobs/101/trigger?param=manual-test"` |
| **编程式删除调度任务** | `XxlJobAdminController` | `DELETE /sample/job-xxl/admin/jobs/{jobId}` | `curl -X DELETE "http://localhost:8080/sample/job-xxl/admin/jobs/101"` |
| **回查分片批处理死信清单** | `XxlJobDeadLetterController` | `GET /sample/job-xxl/dead-letter/list` | `curl -X GET "http://localhost:8080/sample/job-xxl/dead-letter/list"` |
| **受控重放失败死信切片** | `XxlJobDeadLetterController` | `POST /sample/job-xxl/dead-letter/replay` | `curl -X POST "http://localhost:8080/sample/job-xxl/dead-letter/replay"` |
| **清空死信切片队列** | `XxlJobDeadLetterController` | `DELETE /sample/job-xxl/dead-letter/clear` | `curl -X DELETE "http://localhost:8080/sample/job-xxl/dead-letter/clear"` |
| **Actuator 运维全景端点** | `XxlJobEndpoint` | `GET /actuator/xxljobs` | `curl -X GET "http://localhost:8080/actuator/xxljobs"` |

---

## 4. 全真自动化回归命令

```bash
mvn test -pl ateng-boot-job-xxl,ateng-boot-samples/ateng-boot-sample-job-xxl -am -T 1C -nsu -B
```

> 详细原理机制、配置清单与扩展定制，请参阅核心 Starter 文档：[ateng-boot-job-xxl README](../../ateng-boot-job-xxl/README.md)。
