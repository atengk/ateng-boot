package io.github.atengk.boot.sample.web.controller;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.context.TenantContextHolder;
import io.github.atengk.boot.core.context.UserContextHolder;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.web.dto.TrafficOrderDTO;
import io.github.atengk.boot.web.idempotent.annotation.Idempotent;
import io.github.atengk.boot.web.ratelimit.annotation.LimitType;
import io.github.atengk.boot.web.ratelimit.annotation.RateLimiter;
import io.github.atengk.boot.web.util.IpUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.concurrent.TimeUnit;

/**
 * 接口防刷限流与防重幂等治理第三领域场景控制器
 * 覆盖三级自适应令牌桶限流（@RateLimiter）与三级自适应防重幂等（@Idempotent）
 * 涵盖电商订单防重支付、领券按钮特征指纹防抖、短信验证码客户端 IP 防刷、用户维度限流与 SaaS 多租户原生流量隔离
 *
 * @author Ateng
 * @since 2026-11-07
 */
@RestController
@RequestMapping("/sample/web/traffic")
public class WebTrafficGovernanceController {

    /**
     * 全局默认接口限流演示端点（10秒内最多允许2次请求）
     *
     * @return 业务响应契约
     */
    @GetMapping("/ratelimit/default")
    @RateLimiter(count = 2, time = 10, message = "默认接口限流拦截")
    public Result<String> testDefaultRateLimit() {
        return Result.success("全局接口访问成功");
    }

    /**
     * 短信验证码客户端 IP 防刷限流演示端点（10秒内同一客户端 IP 最多允许2次发送）
     *
     * @param request HTTP 请求
     * @return 业务响应契约
     */
    @GetMapping("/ratelimit/ip")
    @RateLimiter(limitType = LimitType.IP, count = 2, time = 10, message = "短信验证码请求过于频繁，请稍后再试")
    public Result<String> testIpRateLimit(HttpServletRequest request) {
        String clientIp = IpUtil.getClientIp(request);
        return Result.success("验证码已发送至客户端 IP: " + clientIp);
    }

    /**
     * 用户维度限流演示端点（10秒内同一已登录用户最多允许2次调用）
     *
     * @return 业务响应契约
     */
    @GetMapping("/ratelimit/user")
    @RateLimiter(limitType = LimitType.USER, count = 2, time = 10, message = "用户操作过于频繁，请稍后再试")
    public Result<String> testUserRateLimit() {
        String userName = UserContextHolder.get();
        return Result.success("用户资料操作成功: " + (userName != null ? userName : "anonymous"));
    }

    /**
     * SpEL 与客户端 IP 复合限流演示端点（10秒内同一 IP 与同一租户最多允许2次调用）
     *
     * @param tenantId 租户标识
     * @return 业务响应契约
     */
    @GetMapping("/ratelimit/composite")
    @RateLimiter(limitType = LimitType.IP, key = "#tenantId", count = 2, time = 10, message = "复合租户限流拦截")
    public Result<String> testCompositeRateLimit(@RequestParam String tenantId) {
        return Result.success("租户配额请求成功: " + tenantId);
    }

    /**
     * SaaS 多租户原生流量隔离限流演示端点（每个租户 60 秒内最多允许 2 次调用，租户间完全隔离）
     *
     * @return 业务响应契约
     */
    @GetMapping("/ratelimit/tenant")
    @RateLimiter(limitType = LimitType.TENANT, count = 2, time = 60, timeUnit = TimeUnit.SECONDS, message = "当前租户请求过于频繁，请稍后再试")
    public Result<String> testTenantRateLimit() {
        String tenantId = TenantContextHolder.getTenantId();
        return Result.success("tenant=" + (tenantId != null ? tenantId : "default"));
    }

    /**
     * 电商订单支付 SpEL 业务键精准防重（以订单号为业务锁，锁定2秒）
     *
     * @param dto 订单提交载荷
     * @return 业务订单号
     */
    @PostMapping("/idempotent/order")
    @Idempotent(key = "#dto.orderNo", prefix = "order:pay:", timeout = 2, message = "订单处理中，请勿重复提交")
    public Result<String> createOrder(@Valid @RequestBody TrafficOrderDTO dto) {
        return Result.success(dto.getOrderNo());
    }

    /**
     * 营销领券按钮特征指纹自动哈希防抖（无显式业务 key，基于入参自动生成 MD5 指纹防连击）
     *
     * @param formToken 领券防抖令牌
     * @return 领券响应契约
     */
    @PostMapping("/idempotent/button")
    @Idempotent(timeout = 2, message = "操作过于频繁，请稍后再试")
    public Result<String> submitButton(@RequestParam String formToken) {
        return Result.success("表单提交成功: " + formToken);
    }

    /**
     * 领券业务防重提交端点
     *
     * @param couponCode 优惠券编码
     * @return 领券结果
     */
    @PostMapping("/idempotent/coupon")
    @Idempotent(key = "#couponCode", prefix = "coupon:claim:", timeout = 2, message = "领券操作过于频繁，请稍后再试")
    public Result<String> claimCoupon(@RequestParam String couponCode) {
        return Result.success("优惠券领取成功: " + couponCode);
    }

    /**
     * 业务异常时锁自动释放（delKeyOnException = true，业务可恢复异常时释放锁支持立即重试）
     *
     * @param bizId 业务流水号
     * @param fail  是否模拟抛出业务校验失败异常
     * @return 业务响应契约
     */
    @PostMapping("/idempotent/fail-retry")
    @Idempotent(key = "#bizId", prefix = "biz:retry:", timeout = 5, delKeyOnException = true)
    public Result<String> failAndRetry(@RequestParam String bizId, @RequestParam boolean fail) {
        if (fail) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(), "可恢复业务校验失败");
        }
        return Result.success("success: " + bizId);
    }
}
