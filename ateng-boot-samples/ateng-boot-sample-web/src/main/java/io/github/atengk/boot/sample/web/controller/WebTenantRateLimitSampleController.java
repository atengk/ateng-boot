package io.github.atengk.boot.sample.web.controller;

import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.web.ratelimit.annotation.LimitType;
import io.github.atengk.boot.web.ratelimit.annotation.RateLimiter;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

/**
 * 多租户原生限流场景演示控制器
 * 演示 @RateLimiter(limitType = LimitType.TENANT) 提取租户上下文实施流量隔离与熔断
 *
 * @author Ateng
 * @since 2026-10-27
 */
@RestController
@RequestMapping("/api/sample/ratelimit/tenant")
public class WebTenantRateLimitSampleController {

    /**
     * 租户受控限流端点（每个租户 60 秒内最多允许 2 次调用）
     *
     * @return 业务响应契约
     */
    @GetMapping("/data")
    @RateLimiter(limitType = LimitType.TENANT, count = 2, time = 60, timeUnit = TimeUnit.SECONDS, message = "当前租户请求过于频繁，请稍后再试")
    public Result<String> getTenantData() {
        String tenantId = TenantContextHolder.getTenantId();
        return Result.success("tenant=" + (tenantId != null ? tenantId : "default"));
    }
}
