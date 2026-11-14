# ateng-boot-sample-core 核心底座场景体验工程

> 专注于演示与自验 `ateng-boot-core` 核心通用底座在真实业务场景下的开箱使用：统一响应契约、传输分页解耦、因果异常与业务语义断言、分层防卫校验与通用字典枚举、分布式雪花发号与 `IdUtil` Base62 短码、敏感数据动态脱敏（内存真明文）、纯原生国密（SM3/SM4/HmacSM3）与带 AAD 的 AES-GCM 加解密、四维上下文跨线程传播与多业务线程池舱壁隔离、高可用弹性重试、单机流控与业务责任链编排、高性能树形装配与二进制 JSON 泛型/切片处理、系统运行基线防卫、`SlowAlertEvent` 慢调用事件闭环及零 XML 工业级日志引擎。

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

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **订单结算前置断言与双模堆栈** | `CoreContractController` | `POST /sample/core/contract/order-check` | `curl -X POST "http://localhost:8080/sample/core/contract/order-check" -H "Content-Type: application/json" -d '{"orderNo":"ORD-20261107-1001","amount":199.50,"simulateDriverFailure":true}'` |
| **订单分页查询与排序解耦** | `CoreContractController` | `GET /sample/core/contract/orders/page` | `curl "http://localhost:8080/sample/core/contract/orders/page?current=1&size=10&sorts[0].property=orderTime&sorts[0].direction=DESC"` |
| **批量客户入驻级联校验与枚举拦截** | `CoreContractController` | `POST /sample/core/contract/users/batch-validate` | `curl -X POST "http://localhost:8080/sample/core/contract/users/batch-validate" -H "Content-Type: application/json" -d '[{"name":"张三","phone":"13812345678","statusCode":1},{"name":"李四","phone":"13987654321","statusCode":2}]'` |
| **通用字典枚举下拉选项渲染** | `CoreContractController` | `GET /sample/core/contract/enums/options` | `curl "http://localhost:8080/sample/core/contract/enums/options"` |
| **编程式安全分页纠偏与防溢出偏移** | `CoreContractController` | `GET /sample/core/contract/page-normalize` | `curl "http://localhost:8080/sample/core/contract/page-normalize?current=0&size=-5"` |
| **分布式业务发号与 IdUtil Base62 短码** | `CoreSecurityController` | `GET /sample/core/security/distributed-id` | `curl "http://localhost:8080/sample/core/security/distributed-id"` |
| **分布式业务发号与短码（兼容路由）** | `CoreSecurityController` | `GET /sample/core/security/ids/generate` | `curl "http://localhost:8080/sample/core/security/ids/generate"` |
| **客户隐私档案动态脱敏与内存真明文** | `CoreSecurityController` | `GET /sample/core/security/customer/masked` | `curl "http://localhost:8080/sample/core/security/customer/masked"` |
| **支付报文加解密与综合摘要验签** | `CoreSecurityController` | `POST /sample/core/security/crypto/verify` | `curl -X POST "http://localhost:8080/sample/core/security/crypto/verify" -H "Content-Type: application/json" -d '{"tradeNo":"TX-20261107-8899","amount":299.90,"channel":"WXPAY","secretKey":"mySecretApiKey2026","rawPayload":"orderId=10086&currency=CNY&amount=299.90"}'` |
| **纯 JDK 国密 HmacSM3 签名与防侧信道验签** | `CoreSecurityController` | `POST /sample/core/security/sign-and-verify` | `curl -X POST "http://localhost:8080/sample/core/security/sign-and-verify" -H "Content-Type: application/json" -d '{"payload":"orderNo=ORD-20261113-01&amount=199.00","secretKey":"sample-hmac-sm3-secret-key"}'` |
| **SM4 密钥生成与带 AAD 的 AES-GCM 加解密** | `CoreSecurityController` | `POST /sample/core/security/crypto` | `curl -X POST "http://localhost:8080/sample/core/security/crypto" -H "Content-Type: application/json" -d '{"plaintext":"敏感支付载荷-20261113","aad":"tenant:t-001|channel:pay"}'` |
| **多维上下文与扩展快照跨线程透传** | `CoreAsyncAndContextController` | `POST /sample/core/async/propagate` | `curl -X POST "http://localhost:8080/sample/core/async/propagate" -H "Content-Type: application/json" -d '{"userId":"user-prod-8888","tenantId":"tenant-finance-001","customContextData":"CUSTOM-METRIC-TRACE-PAYLOAD"}'` |
| **多业务受管线程池舱壁隔离协同** | `CoreAsyncAndContextController` | `POST /sample/core/async/export-report` | `curl -X POST "http://localhost:8080/sample/core/async/export-report" -H "Content-Type: application/json" -d '{"reportName":"Q4-Financial-Analysis.xlsx","format":"EXCEL"}'` |
| **线程池监控探测与在线动态调优** | `CoreAsyncAndContextController` | `POST /sample/core/async/pool-governance` | `curl -X POST "http://localhost:8080/sample/core/async/pool-governance" -H "Content-Type: application/json" -d '{"poolName":"export","newCorePoolSize":6,"newMaxPoolSize":12}'` |
| **异步批量任务强类型超时汇聚** | `CoreAsyncAndContextController` | `POST /sample/core/async/batch-supply` | `curl -X POST "http://localhost:8080/sample/core/async/batch-supply" -H "Content-Type: application/json" -d '{"metrics":["cpu","memory","disk"]}'` |
| **异步并行规则校验与快速失败短路** | `CoreAsyncAndContextController` | `POST /sample/core/async/risk-check` | `curl -X POST "http://localhost:8080/sample/core/async/risk-check" -H "Content-Type: application/json" -d '{"scores":[85,92,78],"safetyThreshold":60}'` |
| **事务生命周期钩子与消息传播协同** | `CoreAsyncAndContextController` | `POST /sample/core/async/tx-and-mq` | `curl -X POST "http://localhost:8080/sample/core/async/tx-and-mq" -H "Content-Type: application/json" -d '{"bizKey":"ORDER-20261107-001","payload":"{\"amount\":199.00,\"sku\":\"SKU-BOOK-001\"}","simulateFatalError":false}'` |
| **跨境汇率网关弹性查询与保底降级** | `CoreResilienceAndPipelineController` | `POST /sample/core/resilience/retry-exchange-rate` | `curl -X POST "http://localhost:8080/sample/core/resilience/retry-exchange-rate" -H "Content-Type: application/json" -d '{"fromCurrency":"USD","toCurrency":"CNY","simulateFailures":1}'` |
| **单机防刷、令牌桶限流与幂等防重** | `CoreResilienceAndPipelineController` | `POST /sample/core/resilience/rate-limit` | `curl -X POST "http://localhost:8080/sample/core/resilience/rate-limit" -H "Content-Type: application/json" -d '{"clientId":"client-app-01","reqToken":"REQ-20261107-1001","triggerIdempotentCheck":true}'` |
| **电商提单责任链编排与风控短路** | `CoreResilienceAndPipelineController` | `POST /sample/core/resilience/order-pipeline` | `curl -X POST "http://localhost:8080/sample/core/resilience/order-pipeline" -H "Content-Type: application/json" -d '{"orderNo":"ORD202611070001","userId":1001,"orderAmount":200.00,"skuCode":"SKU-8888","buyCount":2,"availableStock":20}'` |
| **商品类目树构建、剪枝与全景导航** | `CoreDataAndTreeController` | `GET /sample/core/data/category-tree` | `curl "http://localhost:8080/sample/core/data/category-tree?keyword=手机&targetNodeName=折叠屏手机"` |
| **大批量切片流转与有界租户配额缓存** | `CoreDataAndTreeController` | `POST /sample/core/data/batch-and-cache` | `curl -X POST "http://localhost:8080/sample/core/data/batch-and-cache" -H "Content-Type: application/json" -d '{"orderCodes":["ORD-001","ORD-002","ORD-003","ORD-004","ORD-005","ORD-006","ORD-007"],"sliceSize":3,"tenantId":"tenant-vip-001","tenantName":"VIP电商租户","quotaLimit":1000}'` |
| **安全受限 SpEL 沙箱与高频算法原语** | `CoreDataAndTreeController` | `POST /sample/core/data/spel-and-primitives` | `curl -X POST "http://localhost:8080/sample/core/data/spel-and-primitives" -H "Content-Type: application/json" -d '{"expression":"#user.name + \"-\" + #user.status","simulateMaliciousSpel":true,"user":{"id":10086,"name":"诸葛孔明","status":1},"patchUser":{"name":"诸葛亮"},"rawString":"order_delivery_address","rawNumberStr":"1234567890123","baseDate":"2026-11-06"}'` |
| **二进制 JSON 泛型/集合反序列化与合并** | `CoreDataAndTreeController` | `POST /sample/core/data/json-and-bean` | `curl -X POST "http://localhost:8080/sample/core/data/json-and-bean" -H "Content-Type: application/json" -d '{"users":[{"id":1001,"name":"张大伟","phone":"13812345678"}],"metadata":{"env":"prod","source":"sample"},"patchUser":{"name":"张伟"}}'` |
| **系统环境基线防卫与网络探测** | `CoreSystemAndAuditController` | `GET /sample/core/system/baseline-and-net` | `curl "http://localhost:8080/sample/core/system/baseline-and-net?clientIp=192.168.1.188:8443&cidrPattern=192.168.1.0/24"` |
| **慢方法告警与受管安全文件治理** | `CoreSystemAndAuditController` | `POST /sample/core/system/slow-alert-and-file` | `curl -X POST "http://localhost:8080/sample/core/system/slow-alert-and-file" -H "Content-Type: application/json" -d '{"taskId":"bench-task-999","secretToken":"secretToken-ABC-123456","filename":"../../malicious/path/export-audit.txt","content":"Ateng-Boot 2026 Core Baseline Audit File Content","maxReadBytes":512}'` |
| **结构化操作/SQL 审计与异常诊断** | `CoreSystemAndAuditController` | `POST /sample/core/system/audit-and-diagnose` | `curl -X POST "http://localhost:8080/sample/core/system/audit-and-diagnose" -H "Content-Type: application/json" -d '{"module":"系统基线管理","operateType":"INSERT","description":"新增系统参数配置","sql":"SELECT id, config_key, config_value FROM sys_config WHERE id = 1","errorMessage":"底层数据库连接池耗尽拒绝连接"}'` |
| **慢调用事件消费与慢 Bean 启动诊断** | `CoreSystemAndAuditController` | `POST /sample/core/system/slow-alert` | `curl -X POST "http://localhost:8080/sample/core/system/slow-alert" -H "Content-Type: application/json" -d '{"taskId":"slow-diag-001","secretToken":"secretToken-Demo-950"}'` |

---

## 4. 全真自动化回归命令

```bash
mvn test -pl ateng-boot-core,ateng-boot-samples/ateng-boot-sample-core -am -T 1C -nsu -B
```

> 详细原理机制、配置清单与扩展定制，请参阅核心 Starter 文档：[ateng-boot-core README](../../ateng-boot-core/README.md)。
