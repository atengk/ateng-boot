package io.github.atengk.boot.sample.redis.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.redis.atomic.RedisAtomicUtil;
import io.github.atengk.boot.redis.ratelimit.RedisRateLimiterClient;
import io.github.atengk.boot.sample.redis.model.traffic.CreditCheckRequest;
import io.github.atengk.boot.sample.redis.model.traffic.CreditCheckVO;
import io.github.atengk.boot.sample.redis.model.traffic.RateLimitResultVO;
import io.github.atengk.boot.sample.redis.model.traffic.SemaphorePermitsVO;
import io.github.atengk.boot.sample.redis.model.traffic.SemaphoreResetRequest;
import io.github.atengk.boot.sample.redis.model.traffic.SmsRateLimitRequest;
import io.github.atengk.boot.sample.redis.model.traffic.TokenBucketRateLimitRequest;
import io.github.atengk.boot.sample.redis.model.traffic.TokenBucketResetRequest;
import io.github.atengk.boot.sample.redis.service.SemaphoreThrottlingScenarioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;

/**
 * 分布式流控与并发治理控制器
 * 演示信号量在途并发治理（带降级）、运行时动态配额调配、分布式令牌桶限流与毫秒级滑动窗口防刷
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Slf4j
@Validated
@RestController
@RequestMapping("/sample/redis/traffic")
@RequiredArgsConstructor
public class RedisTrafficController {

    private final SemaphoreThrottlingScenarioService semaphoreService;
    private final RedisRateLimiterClient redisRateLimiterClient;
    private final RedisAtomicUtil redisAtomicUtil;

    /**
     * 外部征信评估在途并发槽位管控（编程式模板带超时降级）
     *
     * @param request 征信评估请求
     * @return 征信评估处理结果 VO
     */
    @PostMapping("/semaphore/credit-check")
    public Result<CreditCheckVO> creditCheck(@Valid @RequestBody CreditCheckRequest request) {
        log.info("[RedisTrafficController] 征信评估并发管控: customerName={}", request.getCustomerName());
        CreditCheckVO vo = semaphoreService.checkCredit(request);
        return Result.success(vo);
    }

    /**
     * 外部征信评估在途并发槽位管控（静态门面）
     *
     * @param request 征信评估请求
     * @return 征信评估处理结果 VO
     */
    @PostMapping("/semaphore/static-check")
    public Result<CreditCheckVO> staticCheck(@Valid @RequestBody CreditCheckRequest request) {
        log.info("[RedisTrafficController] 静态门面征信评估并发管控: customerName={}", request.getCustomerName());
        CreditCheckVO vo = semaphoreService.checkCreditStatic(request);
        return Result.success(vo);
    }

    /**
     * 动态设置/重置信号量总许可证配额
     *
     * @param request 配额调配请求
     * @return 是否重置成功
     */
    @PostMapping("/semaphore/permits/reset")
    public Result<Boolean> resetSemaphorePermits(@Valid @RequestBody SemaphoreResetRequest request) {
        log.info("[RedisTrafficController] 动态调配信号量配额: semaphoreName={}, newPermits={}",
                request.getSemaphoreName(), request.getNewPermits());
        boolean success = semaphoreService.resetPermits(request.getSemaphoreName(), request.getNewPermits());
        return Result.success(success);
    }

    /**
     * 实时获取信号量当前可用剩余配额
     *
     * @param name 信号量名称
     * @return 信号量配额详情 VO
     */
    @GetMapping("/semaphore/permits/{name}")
    public Result<SemaphorePermitsVO> getAvailablePermits(@PathVariable("name") String name) {
        int available = semaphoreService.getAvailablePermits(name);
        return Result.success(SemaphorePermitsVO.builder()
                .semaphoreName(name)
                .currentPermits(available)
                .resetSuccess(true)
                .build());
    }

    /**
     * 令牌桶分布式限流获取（基于 RedisRateLimiterClient）
     *
     * @param request 令牌桶获取请求
     * @return 限流结果 VO
     */
    @PostMapping("/rate-limit/token-bucket")
    public Result<RateLimitResultVO> tokenBucketRateLimit(@Valid @RequestBody TokenBucketRateLimitRequest request) {
        boolean allowed = redisRateLimiterClient.tryAcquire(
                request.getApiKey(),
                request.getRate(),
                Duration.ofSeconds(request.getIntervalSeconds())
        );
        long remaining = redisRateLimiterClient.getAvailablePermits(request.getApiKey());
        return Result.success(RateLimitResultVO.builder()
                .key(request.getApiKey())
                .allowed(allowed)
                .remainingPermits(remaining)
                .build());
    }

    /**
     * 重置令牌桶限流速率与窗口配置
     *
     * @param request 重置请求
     * @return 是否重置成功
     */
    @PostMapping("/rate-limit/token-bucket/reset")
    public Result<Boolean> resetTokenBucket(@Valid @RequestBody TokenBucketResetRequest request) {
        boolean reset = redisRateLimiterClient.reset(request.getApiKey());
        return Result.success(reset);
    }

    /**
     * 短信发送滑动窗口频控校验（基于 RedisAtomicUtil 毫秒级 ZSet 窗口）
     *
     * @param request 短信频控请求
     * @return 限流判定结果 VO
     */
    @PostMapping("/rate-limit/sms-sliding-window")
    public Result<RateLimitResultVO> smsSlidingWindowLimit(@Valid @RequestBody SmsRateLimitRequest request) {
        String key = "rate:sms:" + request.getPhoneNumber();
        boolean allowed = redisAtomicUtil.slidingWindowCheck(
                key,
                request.getMaxAttempts(),
                Duration.ofSeconds(request.getWindowSeconds())
        );
        return Result.success(RateLimitResultVO.builder()
                .key(key)
                .allowed(allowed)
                .remainingPermits(null)
                .build());
    }
}
