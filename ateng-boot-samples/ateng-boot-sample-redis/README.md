# ateng-boot-sample-redis 分布式协同自验工程

> 专一 Redis / Redisson 分布式锁、接口限流防刷、幂等防重与延时队列示范。

---

## 1. 场景痛点与功能定位

在高并发业务场景下，防重、限流与防超卖是不可或缺的保护手段。然而传统接入往往引入繁琐的样板代码、复杂的序列化 `@class` 污染，或者与数据库紧密耦合导致 Demo 极度臃肿。

`ateng-boot-sample-redis` 专注于轻量、纯粹的分布式协同场景：
- **零数据库依赖（Zero DB Dependency）**：无需任何 MySQL 或 H2 数据库，极简轻装上阵；
- **声明式分布式锁**：`@DistributedLock` 支持 4 阶模式（公平锁、联锁、红锁等）与 SpEL 动态键；
- **接口限流防刷**：`@RateLimiter` 纯净令牌桶限流，超限自动返回标准 429 契约；
- **接口幂等防重**：`@Idempotent` 防止重复提交与并发碰撞；
- **全能工具类**：原生 `RedisUtil` 强制游标扫描防阻塞。

---

## 2. 最小可用代码与启动测试

### 本地启动（需本机开启 Redis 端口 6379）
```bash
mvn -pl ateng-boot-samples/ateng-boot-sample-redis spring-boot:run
```

### 声明式分布式锁与限流示例
```java
@RestController
@RequestMapping("/api/redis-sample")
@RequiredArgsConstructor
public class RedisSampleController {

    private final RedisSampleService redisSampleService;

    @PostMapping("/deduct-stock")
    public Result<String> deductStock(@RequestParam("productId") Long productId,
                                      @RequestParam("count") Integer count) {
        return Result.success(redisSampleService.deductStock(productId, count));
    }

    @GetMapping("/limited-api")
    @RateLimiter(key = "'sample_limit:' + #userId", count = 5, time = 1)
    public Result<String> limitedApi(@RequestParam("userId") String userId) {
        return Result.success("请求成功: userId=" + userId);
    }
}
```

### 验证接口限流
连续高频发送 6 次请求：
```bash
curl -i "http://localhost:8080/api/redis-sample/limited-api?userId=1001"
```

**超限触发预期响应**：
```json
{
  "code": 429,
  "msg": "访问过于频繁，请稍后重试",
  "data": null,
  "success": false,
  "traceId": "9b1d0e2f4a3b7f8b"
}
```

---

## 3. 定向集成测试

```bash
mvn test -pl ateng-boot-samples/ateng-boot-sample-redis -am -T 1C
```
涵盖分布式锁、限流防刷、幂等防重及延时队列的自动化测试，单工程自验耗时仅 3~5 秒。
