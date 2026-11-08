# ateng-boot-sample-redis 分布式协同与缓存场景体验工程

> 专注于演示与自验 `ateng-boot-redis` 核心特性在真实业务场景下的开箱使用。

---

## 1. 快速启动与服务端口

- **默认 HTTP 端口**：`8080` (可在 `application.yml` 中修改)
- **启动方式**：
  ```bash
  # 方式一：IDE 中运行 SampleRedisApplication.java 的 main 方法
  # 方式二：命令行启动
  mvn spring-boot:run -pl ateng-boot-samples/ateng-boot-sample-redis
  ```

---

## 2. 运行环境与双模切换

- **默认/CI 模式**：内置离线内存仿真桩与自洽配置（`SampleInMemoryRedisConfig` 基于纯 JDK 动态代理实现，零 Mockito 生产依赖），无需启动任何外部依赖即可秒级启动运行与 100% 跑通测试；
- **Local 真实模式**：若需连接真实物理中间件，复制 `src/main/resources/application-local.example.yml` 为 `application-local.yml` 并填入真实连接串（需包含 `spring.config.activate.on-profile: local` 与激活 `local` profile），启动自动生效（该文件已被 Git 忽略，CI 零风险）。

---

## 3. 核心 Controller 接口与一键体验

应用启动后，可通过以下 cURL 命令或 HTTP 客户端直接发起调用体验：

| 功能场景 | Controller 类 | 请求方式与路径 | 快速体验 cURL 命令 |
| :--- | :--- | :--- | :--- |
| **添加/更新购物车商品 (Hash)** | `RedisOpsAndScriptController` | `POST /sample/redis/ops/cart/items` | `curl -X POST "http://localhost:8080/sample/redis/ops/cart/items" -H "Content-Type: application/json" -d '{"userId":"user_1001","skuId":"SKU_IPHONE_16","skuName":"iPhone 16 Pro","quantity":2,"price":7999.00}'` |
| **查询购物车明细 (Hash)** | `RedisOpsAndScriptController` | `GET /sample/redis/ops/cart/{userId}` | `curl "http://localhost:8080/sample/redis/ops/cart/user_1001"` |
| **删除购物车商品 (Hash)** | `RedisOpsAndScriptController` | `DELETE /sample/redis/ops/cart/{userId}/items/{skuId}` | `curl -X DELETE "http://localhost:8080/sample/redis/ops/cart/user_1001/items/SKU_IPHONE_16"` |
| **更新商品热度积分 (ZSet)** | `RedisOpsAndScriptController` | `POST /sample/redis/ops/rank/score` | `curl -X POST "http://localhost:8080/sample/redis/ops/rank/score" -H "Content-Type: application/json" -d '{"productId":"PROD_1001","productName":"华为 Mate 70 Pro","scoreDelta":15.5}'` |
| **查询热销榜 Top N (ZSet)** | `RedisOpsAndScriptController` | `GET /sample/redis/ops/rank/top/{limit}` | `curl "http://localhost:8080/sample/redis/ops/rank/top/10"` |
| **安全游标扫描 (SCAN)** | `RedisOpsAndScriptController` | `GET /sample/redis/ops/keys/scan` | `curl "http://localhost:8080/sample/redis/ops/keys/scan?pattern=sample:cart:*&count=50"` |
| **Pipeline 批量预热** | `RedisOpsAndScriptController` | `POST /sample/redis/ops/pipeline/warmup` | `curl -X POST "http://localhost:8080/sample/redis/ops/pipeline/warmup" -H "Content-Type: application/json" -d '{"entries":{"sample:cfg:site_name":"AtengMall","sample:cfg:status":"UP"}}'` |
| **原子核销优惠券 (Lua)** | `RedisOpsAndScriptController` | `POST /sample/redis/ops/coupon/deduct` | `curl -X POST "http://localhost:8080/sample/redis/ops/coupon/deduct" -H "Content-Type: application/json" -d '{"couponId":"CPN_20261108","userId":"USER_9999"}'` |
| **跨天自重置流水号** | `RedisOpsAndScriptController` | `POST /sample/redis/ops/sequence/daily` | `curl -X POST "http://localhost:8080/sample/redis/ops/sequence/daily?bizPrefix=order&length=6"` |
| **CAS 原子水位更新** | `RedisOpsAndScriptController` | `POST /sample/redis/ops/watermark/cas` | `curl -X POST "http://localhost:8080/sample/redis/ops/watermark/cas" -H "Content-Type: application/json" -d '{"resourceId":"stock_watermark","expectedValue":100,"newValue":90}'` |

> 注：其余 7 大领域 Controller（分布式锁扣减库存、延时队列投递/死信、Stream 消息生产/待决认领、非 Web 业务防重、受管信号量并发治理、Pub/Sub 主题广播、两级缓存与布隆防卫）将在后续工单切片中陆续暴露并挂载体验。

---

## 4. 全真自动化回归命令

```bash
mvn test -pl ateng-boot-redis,ateng-boot-samples/ateng-boot-sample-redis -am -T 1C -nsu -B
```

> 详细原理机制、配置清单与扩展定制，请参阅核心 Starter 文档：[ateng-boot-redis README](../../ateng-boot-redis/README.md)。
