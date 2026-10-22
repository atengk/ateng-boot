# ateng-boot-sample-observability 云原生可观测性场景自验工程

> 专职 `ateng-boot-observability` 云原生可观测性、健康探针防假死与端点安全治理底座自验：受管线程池动态度量绑定器（`AtengThreadPoolMeterBinder`）、任务拒绝追踪装饰器（`TrackingRejectedExecutionHandler`）、防假死探针基类（`AbstractSafeHealthIndicator`）、四大专属探针（`StorageHealthIndicator`、`DynamicDatasourceHealthIndicator` 等）、端点安全守卫过滤器（`ActuatorEndpointSecurityFilter`）及 Kubernetes 黄金探针解耦治理。

---

## 1. 场景痛点与功能定位

`ateng-boot-sample-observability` 是面向生产就绪的 1:1 独立可观测性自验工程，致力于解决传统 Spring Boot Actuator 的生产落地痛点：
- **受管线程池黑盒度量**：自动发现容器内所有 `ThreadPoolTaskExecutor` 与 `ThreadPoolExecutor`，向 Prometheus 暴露核心大小、活跃线程数、队列积压任务数及拒绝任务累计计数；
- **探针防假死超时熔断**：在底层物理依赖出现网络闪断或挂起时，通过受管守护线程池与强类型超时阈值（默认 2s）实施硬切断，降级返回 `UNKNOWN`，保护 Actuator 线程池不被耗尽；
- **Kubernetes 黄金探针解耦**：自动开启 `/actuator/health/liveness` 与 `/actuator/health/readiness` 探针路径，并与白名单端点（`/health`、`/info`、`/prometheus`、`/pre-stop`）免密放行顺畅协作；
- **敏感高危端点生产安全锁定**：对 `/actuator/env`、`/actuator/beans` 等端点进行前置安全核验，加固模式下要求携带 `X-Actuator-Token` 凭证，未授权直接阻断返回统一规范的 HTTP 403 报文；
- **100% 内存自洽**：基于 H2 内存数据库与本地临时目录存储驱动，无需外部真实数据库与监控集群即可秒级独立运行。

---

## 2. 工程架构与五大自验场景矩阵

| 场景序号 | 场景名称 | 核心门面 / 端点 | 核心验证行为 | 对应端到端测试 |
| :--- | :--- | :--- | :--- | :--- |
| **场景一** | Prometheus 线程池度量抓取 | `/actuator/prometheus`<br>`AtengThreadPoolMeterBinder` | 抓取受管线程池 `sampleTaskExecutor` 的核心、最大、活跃、队列及拒绝指标 | `scenario1_prometheusThreadPoolMetricsScrape` |
| **场景二** | Kubernetes 黄金探针聚合检查 | `/actuator/health`<br>`/actuator/health/liveness`<br>`/actuator/health/readiness` | 聚合检查 H2 数据源与本地存储驱动健康状态，K8s 探针端到端返回 200 UP | `scenario2_healthAndKubernetesProbes` |
| **场景三** | 业务接口免干扰正常放行 | `/api/demo/execute-task` | 业务请求穿透过滤器管道，正常调度受管线程池异步任务并返回 `Result<String>` | `scenario3_businessApiPassThrough` |
| **场景四** | 黄金探针白名单免密畅行 | `/actuator/health`<br>`/actuator/info`<br>`/actuator/prometheus` | 白名单免密端点无 `X-Actuator-Token` 凭据正常访问，返回 HTTP 200 | `scenario4_whitelistedEndpointsNoTokenRequired` |
| **场景五** | 高危敏感端点锁定与凭据鉴权 | `/actuator/env`<br>`/actuator/beans`<br>`ActuatorEndpointSecurityFilter` | 无 Token 拦截 403、错误 Token 拦截 403、正确 Token 核验通过放行 200 | `scenario5_sensitiveEndpointsLockdownAndTokenGuard` |

---

## 3. 核心实测代码范式

### 1. Prometheus 抓取文本 (`/actuator/prometheus`)
```text
# HELP ateng_threadpool_core_size Core pool size of the thread pool executor
# TYPE ateng_threadpool_core_size gauge
ateng_threadpool_core_size{pool="sampleTaskExecutor",} 4.0

# HELP ateng_threadpool_max_size Maximum pool size of the thread pool executor
# TYPE ateng_threadpool_max_size gauge
ateng_threadpool_max_size{pool="sampleTaskExecutor",} 8.0

# HELP ateng_threadpool_active_size Current active threads in the thread pool
# TYPE ateng_threadpool_active_size gauge
ateng_threadpool_active_size{pool="sampleTaskExecutor",} 0.0

# HELP ateng_threadpool_queue_size Current queue task count waiting in the thread pool
# TYPE ateng_threadpool_queue_size gauge
ateng_threadpool_queue_size{pool="sampleTaskExecutor",} 0.0

# HELP ateng_threadpool_rejected_total Total count of tasks rejected by the thread pool
# TYPE ateng_threadpool_rejected_total counter
ateng_threadpool_rejected_total{pool="sampleTaskExecutor",} 0.0
```

### 2. Kubernetes 黄金探针响应 (`/actuator/health`)
```json
{
  "status": "UP",
  "components": {
    "dynamicDatasource": {
      "status": "UP",
      "details": {
        "multiDataSourceDetected": false,
        "dataSourceCount": 1,
        "dataSources": {
          "dataSource": {
            "type": "HikariDataSource",
            "status": "UP",
            "databaseProductName": "H2",
            "databaseProductVersion": "2.2.224"
          }
        }
      }
    },
    "storage": {
      "status": "UP",
      "details": {
        "driver": "LocalDriver",
        "type": "local",
        "status": "UP"
      }
    }
  }
}
```

### 3. 未授权访问高危端点拦截 (`GET /actuator/env`)
```json
{
  "code": 403,
  "msg": "Actuator 端点访问拒绝: 未授权访问高危端点：X-Actuator-Token 凭据缺失或不匹配",
  "data": null,
  "timestamp": 1792656000000
}
```

---

## 4. 运行自验测试

```bash
mvn test -pl ateng-boot-samples/ateng-boot-sample-observability -T 1C -nsu -B
```
