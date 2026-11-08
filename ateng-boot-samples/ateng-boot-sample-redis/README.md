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

| **秒杀库存扣减 (分布式锁)** | `RedisLockController` | `POST /sample/redis/lock/deduct-stock` | `curl -X POST "http://localhost:8080/sample/redis/lock/deduct-stock" -H "Content-Type: application/json" -d '{"productId":1001,"count":5}'` |
| **跨服务订单重入结算 (分布式锁)** | `RedisLockController` | `POST /sample/redis/lock/order-settle-reentrant` | `curl -X POST "http://localhost:8080/sample/redis/lock/order-settle-reentrant" -H "Content-Type: application/json" -d '{"orderId":"ORD_SETTLE_1001","productId":1001,"quantity":2}'` |
| **促销规则更新 (排他写锁)** | `RedisLockController` | `POST /sample/redis/lock/promotion-rule` | `curl -X POST "http://localhost:8080/sample/redis/lock/promotion-rule" -H "Content-Type: application/json" -d '{"ruleId":"RULE_VIP_88","ruleName":"VIP专享八折券","discountRate":0.80}'` |
| **促销规则查询 (共享读锁)** | `RedisLockController` | `GET /sample/redis/lock/promotion-rule/{ruleId}` | `curl "http://localhost:8080/sample/redis/lock/promotion-rule/RULE_VIP_88"` |
| **排队抢占名额 (公平锁)** | `RedisLockController` | `POST /sample/redis/lock/fair-quota/acquire` | `curl -X POST "http://localhost:8080/sample/redis/lock/fair-quota/acquire" -H "Content-Type: application/json" -d '{"activityId":"ACT_FAIR_99","userId":"USER_001"}'` |
| **组合套餐扣减 (MultiLock)** | `RedisLockController` | `POST /sample/redis/lock/multi-sku/deduct` | `curl -X POST "http://localhost:8080/sample/redis/lock/multi-sku/deduct" -H "Content-Type: application/json" -d '{"comboId":"COMBO_PHONE_WATCH","productIds":[1001,1002],"quantity":1}'` |
| **争锁超时排队降级 (Fallback)** | `RedisLockController` | `POST /sample/redis/lock/try-acquire-fallback` | `curl -X POST "http://localhost:8080/sample/redis/lock/try-acquire-fallback" -H "Content-Type: application/json" -d '{"resourceId":"hot_goods_1"}'` |
| **静态门面加锁 (RedisLockUtil)** | `RedisLockController` | `POST /sample/redis/lock/static-lock` | `curl -X POST "http://localhost:8080/sample/redis/lock/static-lock" -H "Content-Type: application/json" -d '{"resourceId":"daily_report"}'` |
| **支付异步通知防重入账 (幂等)** | `RedisIdempotentController` | `POST /sample/redis/idempotent/payment/notify` | `curl -X POST "http://localhost:8080/sample/redis/idempotent/payment/notify" -H "Content-Type: application/json" -d '{"paymentNo":"PAY_20261108_001","orderId":"ORD_1001","amount":99.00,"payStatus":"SUCCESS"}'` |
| **瞬时故障自愈重试 (幂等)** | `RedisIdempotentController` | `POST /sample/redis/idempotent/payment/simulate-failure-retry` | `curl -X POST "http://localhost:8080/sample/redis/idempotent/payment/simulate-failure-retry" -H "Content-Type: application/json" -d '{"paymentNo":"PAY_20261108_002","amount":199.00,"shouldFail":false}'` |
| **编程式退款核销 (幂等)** | `RedisIdempotentController` | `POST /sample/redis/idempotent/refund/template` | `curl -X POST "http://localhost:8080/sample/redis/idempotent/refund/template" -H "Content-Type: application/json" -d '{"refundNo":"REFUND_001","refundAmount":50.00,"reason":"七天无理由"}'` |
| **静态门面退款核销 (幂等)** | `RedisIdempotentController` | `POST /sample/redis/idempotent/refund/static` | `curl -X POST "http://localhost:8080/sample/redis/idempotent/refund/static" -H "Content-Type: application/json" -d '{"refundNo":"REFUND_002","refundAmount":80.00,"reason":"降价补差"}'` |
| **外部征信在途并发管控 (信号量)** | `RedisTrafficController` | `POST /sample/redis/traffic/semaphore/credit-check` | `curl -X POST "http://localhost:8080/sample/redis/traffic/semaphore/credit-check" -H "Content-Type: application/json" -d '{"idCard":"110101199003072345","customerName":"张三"}'` |
| **静态门面征信评估 (信号量)** | `RedisTrafficController` | `POST /sample/redis/traffic/semaphore/static-check` | `curl -X POST "http://localhost:8080/sample/redis/traffic/semaphore/static-check" -H "Content-Type: application/json" -d '{"idCard":"110101199003072345","customerName":"张三"}'` |
| **动态调整信号量配额** | `RedisTrafficController` | `POST /sample/redis/traffic/semaphore/permits/reset` | `curl -X POST "http://localhost:8080/sample/redis/traffic/semaphore/permits/reset" -H "Content-Type: application/json" -d '{"semaphoreName":"sem:credit:eval","newPermits":20}'` |
| **查询信号量剩余配额** | `RedisTrafficController` | `GET /sample/redis/traffic/semaphore/permits/{name}` | `curl "http://localhost:8080/sample/redis/traffic/semaphore/permits/sem:credit:eval"` |
| **令牌桶流控获取 (RateLimiter)** | `RedisTrafficController` | `POST /sample/redis/traffic/rate-limit/token-bucket` | `curl -X POST "http://localhost:8080/sample/redis/traffic/rate-limit/token-bucket" -H "Content-Type: application/json" -d '{"apiKey":"app_gateway_order","rate":10,"intervalSeconds":1}'` |
| **重置令牌桶限流配置** | `RedisTrafficController` | `POST /sample/redis/traffic/rate-limit/token-bucket/reset` | `curl -X POST "http://localhost:8080/sample/redis/traffic/rate-limit/token-bucket/reset" -H "Content-Type: application/json" -d '{"apiKey":"app_gateway_order"}'` |
| **短信滑动窗口防刷 (ZSet)** | `RedisTrafficController` | `POST /sample/redis/traffic/rate-limit/sms-sliding-window` | `curl -X POST "http://localhost:8080/sample/redis/traffic/rate-limit/sms-sliding-window" -H "Content-Type: application/json" -d '{"phoneNumber":"13800138000","windowSeconds":60,"maxAttempts":5}'` |
| **单级系统字典缓存查询** | `RedisCacheController` | `GET /sample/redis/cache/dict/{code}` | `curl "http://localhost:8080/sample/redis/cache/dict/GENDER"` |
| **单级系统字典缓存驱逐** | `RedisCacheController` | `DELETE /sample/redis/cache/dict/{code}` | `curl -X DELETE "http://localhost:8080/sample/redis/cache/dict/GENDER"` |
| **单商品安全防卫查询 (DCL+防穿透)** | `RedisCacheController` | `GET /sample/redis/cache/product/{id}` | `curl "http://localhost:8080/sample/redis/cache/product/1001"` |
| **批量商品安全查询 (MGET防N+1)** | `RedisCacheController` | `POST /sample/redis/cache/products/batch` | `curl -X POST "http://localhost:8080/sample/redis/cache/products/batch" -H "Content-Type: application/json" -d '[1001, 1002]'` |
| **驱逐商品缓存** | `RedisCacheController` | `DELETE /sample/redis/cache/product/{id}` | `curl -X DELETE "http://localhost:8080/sample/redis/cache/product/1001"` |
| **用户档案两级缓存查询 (L1+L2)** | `RedisCacheController` | `GET /sample/redis/cache/user-profile/{userId}` | `curl "http://localhost:8080/sample/redis/cache/user-profile/1001"` |
| **驱逐用户两级缓存 (广播失效)** | `RedisCacheController` | `DELETE /sample/redis/cache/user-profile/{userId}` | `curl -X DELETE "http://localhost:8080/sample/redis/cache/user-profile/1001"` |
| **加入黑名单布隆过滤器** | `RedisCacheController` | `POST /sample/redis/cache/blacklist/{userId}` | `curl -X POST "http://localhost:8080/sample/redis/cache/blacklist/risk_user_99"` |
| **布隆过滤器存在性核验** | `RedisCacheController` | `GET /sample/redis/cache/blacklist/check/{userId}` | `curl "http://localhost:8080/sample/redis/cache/blacklist/check/risk_user_99"` |
| **布隆过滤器80%容量监控指标** | `RedisCacheController` | `GET /sample/redis/cache/blacklist/metrics` | `curl "http://localhost:8080/sample/redis/cache/blacklist/metrics"` |

| **投递订单延时取消任务** | `RedisDelayQueueController` | `POST /sample/redis/delay-queue/order/cancel/send` | `curl -X POST "http://localhost:8080/sample/redis/delay-queue/order/cancel/send" -H "Content-Type: application/json" -d '{"orderId":"ORD_DELAY_001","delaySeconds":15,"reason":"未支付超时自动取消"}'` |
| **查询已消费订单取消记录** | `RedisDelayQueueController` | `GET /sample/redis/delay-queue/order/cancel/consumed` | `curl "http://localhost:8080/sample/redis/delay-queue/order/cancel/consumed"` |
| **投递毒丸订单触发重试死信演练** | `RedisDelayQueueController` | `POST /sample/redis/delay-queue/order/cancel/send-poison` | `curl -X POST "http://localhost:8080/sample/redis/delay-queue/order/cancel/send-poison?orderId=POISON_ORD_999"` |
| **查询延时死信积压统计** | `RedisDelayQueueController` | `GET /sample/redis/delay-queue/dead-letter/count` | `curl "http://localhost:8080/sample/redis/delay-queue/dead-letter/count"` |
| **拉取延时死信消息 (运维探针)** | `RedisDelayQueueController` | `GET /sample/redis/delay-queue/dead-letter/poll` | `curl "http://localhost:8080/sample/redis/delay-queue/dead-letter/poll"` |
| **清空延时队列死信消息** | `RedisDelayQueueController` | `DELETE /sample/redis/delay-queue/dead-letter/clear` | `curl -X DELETE "http://localhost:8080/sample/redis/delay-queue/dead-letter/clear"` |
| **查询延时队列 SPI 告警快照** | `RedisDelayQueueController` | `GET /sample/redis/delay-queue/alerts` | `curl "http://localhost:8080/sample/redis/delay-queue/alerts"` |

| **模板门面投递订单流事件** | `RedisStreamController` | `POST /sample/redis/stream/events/template` | `curl -X POST "http://localhost:8080/sample/redis/stream/events/template" -H "Content-Type: application/json" -d '{"orderId":"ORD_STREAM_001","eventType":"ORDER_CREATED","amount":199.00}'` |
| **静态门面投递订单流事件** | `RedisStreamController` | `POST /sample/redis/stream/events/static` | `curl -X POST "http://localhost:8080/sample/redis/stream/events/static" -H "Content-Type: application/json" -d '{"orderId":"ORD_STREAM_002","eventType":"ORDER_PAID","amount":88.50}'` |
| **查询消费组已消费事件记录** | `RedisStreamController` | `GET /sample/redis/stream/consumed` | `curl "http://localhost:8080/sample/redis/stream/consumed"` |
| **查询待决消息概要统计 (XPENDING)** | `RedisStreamController` | `GET /sample/redis/stream/pending/summary` | `curl "http://localhost:8080/sample/redis/stream/pending/summary"` |
| **查询待决消息明细列表 (XPENDING)** | `RedisStreamController` | `GET /sample/redis/stream/pending/messages` | `curl "http://localhost:8080/sample/redis/stream/pending/messages?count=10"` |
| **认领孤儿待决消息 (XCLAIM)** | `RedisStreamController` | `POST /sample/redis/stream/claim` | `curl -X POST "http://localhost:8080/sample/redis/stream/claim" -H "Content-Type: application/json" -d '{"stream":"stream:order:events","group":"group:order:processor","newConsumer":"consumer-standby-01","minIdleSeconds":60,"recordIds":["1700000000000-0"]}'` |
| **定长修剪裁剪 Stream 长度 (XTRIM)** | `RedisStreamController` | `POST /sample/redis/stream/trim` | `curl -X POST "http://localhost:8080/sample/redis/stream/trim" -H "Content-Type: application/json" -d '{"stream":"stream:order:events","maxLen":100}'` |
| **投递毒丸 Stream 事件演练** | `RedisStreamController` | `POST /sample/redis/stream/events/poison` | `curl -X POST "http://localhost:8080/sample/redis/stream/events/poison?orderId=POISON_STREAM_999"` |
| **查询死信流清单 ({stream}:dlq)** | `RedisStreamController` | `GET /sample/redis/stream/dead-letter` | `curl "http://localhost:8080/sample/redis/stream/dead-letter?count=10"` |
| **查询 Stream SPI 告警快照** | `RedisStreamController` | `GET /sample/redis/stream/alerts` | `curl "http://localhost:8080/sample/redis/stream/alerts"` |

| **广播全站系统维护公告** | `RedisPubSubController` | `POST /sample/redis/pubsub/broadcast/notice` | `curl -X POST "http://localhost:8080/sample/redis/pubsub/broadcast/notice" -H "Content-Type: application/json" -d '{"noticeId":"NOT_20261108","title":"系统维护通知","content":"系统将于今晚 02:00 进行停机升级","level":"WARN"}'` |
| **通配模式系统配置刷新广播** | `RedisPubSubController` | `POST /sample/redis/pubsub/broadcast/system-config` | `curl -X POST "http://localhost:8080/sample/redis/pubsub/broadcast/system-config" -H "Content-Type: application/json" -d '{"eventType":"config_refresh","eventData":"{\"refreshKey\":\"ALL_CACHE\"}"}'` |
| **查询已接收系统公告广播历史** | `RedisPubSubController` | `GET /sample/redis/pubsub/history/notices` | `curl "http://localhost:8080/sample/redis/pubsub/history/notices"` |
| **查询已接收系统通配事件历史** | `RedisPubSubController` | `GET /sample/redis/pubsub/history/system-events` | `curl "http://localhost:8080/sample/redis/pubsub/history/system-events"` |

---

## 4. 全真自动化回归命令

```bash
mvn test -pl ateng-boot-redis,ateng-boot-samples/ateng-boot-sample-redis -am -T 1C -nsu -B
```

> 详细原理机制、配置清单与扩展定制，请参阅核心 Starter 文档：[ateng-boot-redis README](../../ateng-boot-redis/README.md)。
