package io.github.atengk.boot.sample.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.web.ratelimit.annotation.LimitType;
import io.github.atengk.boot.web.ratelimit.annotation.RateLimiter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 接口限流防刷示例控制层
 * 演示 DEFAULT、IP、USER 以及 SpEL 复合键限流防刷
 *
 * @author Ateng
 * @since 2026-10-16
 */
@RestController
@RequestMapping("/api/samples/ratelimit")
public class RateLimiterSampleController {

    /**
     * 接口方法级默认全局限流演示端点（10 秒内允许最多 2 次请求）
     */
    @GetMapping("/default")
    @RateLimiter(count = 2, time = 10, message = "默认接口限流拦截")
    public Result<String> testDefault() {
        return Result.success("default ok");
    }

    /**
     * IP 维度限流演示端点（10 秒内同一 IP 允许最多 2 次请求）
     */
    @GetMapping("/ip")
    @RateLimiter(limitType = LimitType.IP, count = 2, time = 10, message = "IP 限流拦截")
    public Result<String> testIp() {
        return Result.success("ip ok");
    }

    /**
     * 用户维度限流演示端点（10 秒内同一用户允许最多 2 次请求）
     */
    @GetMapping("/user")
    @RateLimiter(limitType = LimitType.USER, count = 2, time = 10, message = "用户限流拦截")
    public Result<String> testUser() {
        return Result.success("user ok");
    }

    /**
     * SpEL 与 IP 复合限流演示端点（10 秒内同一 IP 与同一租户允许最多 2 次请求）
     */
    @GetMapping("/composite")
    @RateLimiter(limitType = LimitType.IP, key = "#tenantId", count = 2, time = 10, message = "复合租户限流拦截")
    public Result<String> testComposite(@RequestParam String tenantId) {
        return Result.success("tenant: " + tenantId);
    }
}
