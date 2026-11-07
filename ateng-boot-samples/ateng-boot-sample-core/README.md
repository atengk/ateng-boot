# ateng-boot-sample-core 核心底座场景体验工程

> 专注于演示与自验 `ateng-boot-core` 核心通用底座在真实业务场景下的开箱使用：统一响应契约、传输分页解耦、因果异常与业务语义断言、分层防卫校验与通用字典枚举、分布式雪花发号与短码、敏感数据动态脱敏（内存真明文）、纯原生对称加解密与验签、四维上下文跨线程传播与多业务线程池舱壁隔离、高可用弹性重试、单机流控与业务责任链编排、高性能树形装配与流式分批切片、系统运行基线防卫与零 XML 工业级日志引擎。

---

## 1. 快速启动与服务端口

- **默认 HTTP 端口**：`8080` (可在 `application.yml` 中通过 `server.port` 修改)
- **启动方式**：
  ```bash
  # 方式一：IDE 中运行 SampleCoreApplication.java 的 main 方法
  # 方式二：命令行启动
  mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-core
  ```

---

## 2. 运行环境与双模切换

- **默认/CI 模式**：本工程坚守纯原生零重型外部依赖原则，无需安装任何外部中间件（零 Redis/DB/MQ 依赖），开箱即用，默认模式与 CI 流水线均 100% 全真自洽运行；
- **Local 真实环境调优模式**：复制 `src/main/resources/application-local.example.yml` 为 `application-local.yml` 并按需调整（该文件已被 Git 忽略，CI 零风险），启动自动探测生效：
  - 支持在本地开启 **JDK 21+ 虚拟线程模式**（`spring.threads.virtual.enabled: true`）；
  - 支持开启 **磁盘双文件异步切片落盘日志**（`ateng.boot.core.logging.file.enabled: true`）；
  - 支持配置 **多网卡环境首选物理网段**（`ateng.boot.core.net.preferred-networks`）。

---

## 3. 核心 Controller 接口与一键体验

应用启动后，可通过以下 cURL 命令或浏览器直接发起调用体验各业务领域特性：

### 3.1 传输契约与分层校验领域 (`CoreContractController` -> `/sample/core/contract`)

| 功能场景 | 请求方式与路径 | 核心门面 / 注解 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **订单结算前置断言与双模堆栈** | `POST /sample/core/contract/order-check` | `Result<T>`<br>`BusinessAssert`<br>`BusinessException`<br>`ResultCodeUtil` | `curl -X POST "http://localhost:8080/sample/core/contract/order-check" -H "Content-Type: application/json" -d '{"orderNo":"ORD-20261107-1001","amount":199.50,"simulateDriverFailure":true}'` |
| **订单分页查询与排序解耦** | `GET /sample/core/contract/orders/page` | `PageQuery`<br>`SortItem`<br>`PageResult<T>` | `curl "http://localhost:8080/sample/core/contract/orders/page?current=1&size=10&sorts[0].property=orderTime&sorts[0].direction=DESC"` |
| **批量客户入驻级联校验与枚举拦截** | `POST /sample/core/contract/users/batch-validate` | `ValidList<E>`<br>`ValidationGroups`<br>`ValidatorUtil`<br>`@InEnum` | `curl -X POST "http://localhost:8080/sample/core/contract/users/batch-validate" -H "Content-Type: application/json" -d '[{"name":"张三","phone":"13812345678","statusCode":1},{"name":"李四","phone":"13987654321","statusCode":2}]'` |
| **通用字典枚举下拉选项渲染** | `GET /sample/core/contract/enums/options` | `IBaseEnum`<br>`@EnumAlias`<br>`EnumUtil`<br>`OptionItem` | `curl "http://localhost:8080/sample/core/contract/enums/options?alias=orderStatus"` |

### 3.2 标识发号、数据脱敏与密码学套件领域 (`CoreSecurityController` -> `/sample/core/security`)

| 功能场景 | 请求方式与路径 | 核心门面 / 注解 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **分布式业务发号与紧凑短链** | `GET /sample/core/security/ids/generate` | `Snowflake`<br>`IdUtil`<br>`Base62`<br>`SnowflakeIdInfo` | `curl "http://localhost:8080/sample/core/security/ids/generate"` |
| **客户隐私档案动态脱敏与内存真明文** | `GET /sample/core/security/customer/masked` | `@FieldSensitive`<br>`SensitiveJsonSerializer`<br>`DesensitizeUtil` | `curl "http://localhost:8080/sample/core/security/customer/masked"` |
| **支付报文加解密与 HMAC 恒定时间验签** | `POST /sample/core/security/crypto/verify` | `CryptoUtil`<br>`HmacUtil`<br>`DigestUtil`<br>`ChecksumUtil` | `curl -X POST "http://localhost:8080/sample/core/security/crypto/verify" -H "Content-Type: application/json" -d '{"tradeNo":"TX-20261107-8899","amount":299.90,"channel":"WXPAY","secretKey":"mySecretApiKey2026","rawPayload":"orderId=10086&currency=CNY&amount=299.90"}'` |

### 3.3 异步上下文、多业务线程池与事务消息协同领域 (`CoreAsyncAndContextController` -> `/sample/core/async`)

| 功能场景 | 请求方式与路径 | 核心门面 / 注解 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **多维上下文与扩展快照跨线程透传** | `POST /sample/core/async/propagate` | `AsyncContext`<br>`UserContextHolder`<br>`TenantContextHolder`<br>`AsyncContextSnapshotProvider` | `curl -X POST "http://localhost:8080/sample/core/async/propagate" -H "Content-Type: application/json" -d '{"userId":"user-prod-8888","tenantId":"tenant-finance-001","customContextData":"CUSTOM-METRIC-TRACE-PAYLOAD"}'` |
| **多业务受管线程池舱壁隔离协同** | `POST /sample/core/async/export-report` | `AsyncUtil.supply`<br>`AsyncUtil.allOf`<br>`DynamicAsyncPoolRegistrar` | `curl -X POST "http://localhost:8080/sample/core/async/export-report" -H "Content-Type: application/json" -d '{"reportName":"Q4-Financial-Analysis.xlsx","format":"EXCEL"}'` |
| **线程池监控探测与在线动态调优** | `POST /sample/core/async/pool-governance` | `AsyncUtil.getStatus`<br>`AsyncUtil.resize`<br>`ThreadPoolStatus` | `curl -X POST "http://localhost:8080/sample/core/async/pool-governance" -H "Content-Type: application/json" -d '{"poolName":"export","newCorePoolSize":6,"newMaxPoolSize":12}'` |
| **事务生命周期钩子与消息传播协同** | `POST /sample/core/async/tx-and-mq` | `TransactionUtil`<br>`MessageContextPropagator`<br>`FatalExceptionClassifier`<br>`MessagePayloadSerializer` | `curl -X POST "http://localhost:8080/sample/core/async/tx-and-mq" -H "Content-Type: application/json" -d '{"bizKey":"ORDER-20261107-001","payload":"{\"amount\":199.00,\"sku\":\"SKU-BOOK-001\"}","simulateFatalError":false}'` |

> 注：其余领域 Controller（弹性流控、数据结构、系统与审计）将随后续工单逐步交付并补充 cURL。

---

## 4. 全真自动化回归命令

```bash
mvn test -pl ateng-boot-core,ateng-boot-samples/ateng-boot-sample-core -am -T 1C -nsu -B
```

> 详细原理机制、配置清单与扩展定制，请参阅核心 Starter 文档：[ateng-boot-core README](../../ateng-boot-core/README.md)。
