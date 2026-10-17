package io.github.atengk.boot.sample.redis.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.redis.service.RedisSampleService;
import io.github.atengk.boot.web.idempotent.annotation.Idempotent;
import io.github.atengk.boot.web.ratelimit.annotation.RateLimiter;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Redis 场景样例控制器
 * 演示声明式分布式锁、接口限流防刷与幂等防重（零数据库依赖）
 *
 * @author Ateng
 * @since 2026-10-17
 */
@RestController
@RequestMapping("/api/redis-sample")
@RequiredArgsConstructor
public class RedisSampleController {

    private final RedisSampleService redisSampleService;

    /**
     * 扣减商品库存（受 Redisson 分布式锁严格并发保护）
     *
     * @param productId 商品 ID
     * @param count     扣减数量
     * @return 业务响应
     */
    @PostMapping("/deduct-stock")
    public Result<String> deductStock(@RequestParam("productId") Long productId,
                                      @RequestParam("count") Integer count) {
        return Result.success(redisSampleService.deductStock(productId, count));
    }

    /**
     * 接口限流防刷演示
     *
     * @param userId 用户 ID
     * @return 成功响应
     */
    @GetMapping("/limited-api")
    @RateLimiter(key = "'sample_limit:' + #userId", count = 5, time = 1)
    public Result<String> limitedApi(@RequestParam("userId") String userId) {
        return Result.success("请求成功: userId=" + userId);
    }

    /**
     * 接口幂等防重演示
     *
     * @param token 幂等令牌
     * @return 成功响应
     */
    @PostMapping("/idempotent")
    @Idempotent(key = "'sample_idempotent:' + #token", timeout = 10)
    public Result<String> idempotentApi(@RequestParam("token") String token) {
        return Result.success("幂等提交成功: token=" + token);
    }
}
