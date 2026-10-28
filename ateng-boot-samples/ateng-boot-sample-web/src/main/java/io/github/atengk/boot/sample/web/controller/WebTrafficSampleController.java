package io.github.atengk.boot.sample.web.controller;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.web.dto.TrafficOrderDTO;
import io.github.atengk.boot.web.idempotent.annotation.Idempotent;
import io.github.atengk.boot.web.ratelimit.annotation.LimitType;
import io.github.atengk.boot.web.ratelimit.annotation.RateLimiter;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 流量控制与防重幂等场景控制器
 * 演示基于单机内存存储（Tier 3）的 @RateLimiter 令牌桶限流与 @Idempotent 幂等防重防抖
 *
 * @author Ateng
 * @since 2026-10-19
 */
@RestController
@RequestMapping("/api/sample/traffic")
public class WebTrafficSampleController {

    /**
     * 默认全局接口方法限流（10秒内允许2次）
     *
     * @return 正常响应
     */
    @GetMapping("/ratelimit/default")
    @RateLimiter(count = 2, time = 10, message = "默认接口限流拦截")
    public Result<String> testDefaultRateLimit() {
        return Result.success("default ok");
    }

    /**
     * 客户端 IP 维度联合防刷限流（10秒内同一IP允许2次）
     *
     * @return 正常响应
     */
    @GetMapping("/ratelimit/ip")
    @RateLimiter(limitType = LimitType.IP, count = 2, time = 10, message = "IP 限流拦截")
    public Result<String> testIpRateLimit() {
        return Result.success("ip ok");
    }

    /**
     * 用户维度限流（10秒内同一用户允许2次）
     *
     * @return 正常响应
     */
    @GetMapping("/ratelimit/user")
    @RateLimiter(limitType = LimitType.USER, count = 2, time = 10, message = "用户限流拦截")
    public Result<String> testUserRateLimit() {
        return Result.success("user ok");
    }

    /**
     * SpEL 与 IP 复合限流（10秒内同一IP与同一租户允许2次）
     *
     * @param tenantId 租户标识
     * @return 响应包含租户
     */
    @GetMapping("/ratelimit/composite")
    @RateLimiter(limitType = LimitType.IP, key = "#tenantId", count = 2, time = 10, message = "复合租户限流拦截")
    public Result<String> testCompositeRateLimit(@RequestParam String tenantId) {
        return Result.success("tenant: " + tenantId);
    }

    /**
     * 精准 SpEL 业务键防重（如按订单号防重）
     *
     * @param dto 订单载荷
     * @return 订单号
     */
    @PostMapping("/idempotent/order")
    @Idempotent(key = "#dto.orderNo", prefix = "order:", timeout = 2, message = "订单处理中，请勿重复提交")
    public Result<String> createOrder(@Valid @RequestBody TrafficOrderDTO dto) {
        return Result.success(dto.getOrderNo());
    }

    /**
     * 通用按钮请求特征指纹自动哈希防抖
     *
     * @param formToken 表单防抖令牌
     * @return 响应
     */
    @PostMapping("/idempotent/button")
    @Idempotent(timeout = 2)
    public Result<String> submitButton(@RequestParam String formToken) {
        return Result.success("submitted: " + formToken);
    }

    /**
     * 业务异常时锁自动释放以支持立即重试
     *
     * @param bizId 业务标识
     * @param fail  是否模拟触发异常
     * @return 处理结果
     */
    @PostMapping("/idempotent/fail-retry")
    @Idempotent(key = "#bizId", timeout = 5, delKeyOnException = true)
    public Result<String> failAndRetry(@RequestParam String bizId, @RequestParam boolean fail) {
        if (fail) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), "可恢复业务校验失败");
        }
        return Result.success("success: " + bizId);
    }
}
