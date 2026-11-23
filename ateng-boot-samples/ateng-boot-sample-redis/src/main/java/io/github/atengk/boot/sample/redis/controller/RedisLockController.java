package io.github.atengk.boot.sample.redis.controller;

import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.sample.redis.model.lock.*;
import io.github.atengk.boot.sample.redis.service.OrderSettlementService;
import io.github.atengk.boot.sample.redis.service.StockDeductScenarioService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 分布式锁与协同治理业务控制器
 * 提供秒杀防超卖、跨 Bean 真实可重入锁、读写锁、公平锁、多键联锁、争锁降级与静态门面调用
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Slf4j
@RestController
@RequestMapping("/sample/redis/lock")
@RequiredArgsConstructor
public class RedisLockController {

    private final StockDeductScenarioService stockDeductScenarioService;
    private final OrderSettlementService orderSettlementService;

    /**
     * 1. 声明式分布式锁扣减秒杀库存（防超卖）
     */
    @PostMapping("/deduct-stock")
    public Result<StockDeductResultVO> deductStock(@Valid @RequestBody StockDeductRequest request) {
        StockDeductResultVO result = stockDeductScenarioService.deductStock(request);
        return Result.success(result);
    }

    /**
     * 2. 订单结算服务调用库存服务的跨 Bean 真实 LockMode.REENTRANT 重入锁演练
     */
    @PostMapping("/order-settle-reentrant")
    public Result<OrderSettleVO> settleOrderReentrant(@Valid @RequestBody OrderSettleRequest request) {
        OrderSettleVO result = orderSettlementService.settleOrder(request);
        return Result.success(result);
    }

    /**
     * 3. 促销规则写入与修改（LockMode.WRITE 排他写锁）
     */
    @PostMapping("/promotion-rule")
    public Result<PromotionRuleVO> updatePromotionRule(@Valid @RequestBody PromotionRuleUpdateRequest request) {
        PromotionRuleVO rule = stockDeductScenarioService.updatePromotionRule(request);
        return Result.success(rule);
    }

    /**
     * 4. 促销规则读取（LockMode.READ 共享读锁）
     */
    @GetMapping("/promotion-rule/{ruleId}")
    public Result<PromotionRuleVO> getPromotionRule(@PathVariable String ruleId) {
        PromotionRuleVO rule = stockDeductScenarioService.getPromotionRule(ruleId);
        return Result.success(rule);
    }

    /**
     * 5. 稀缺名额公平排队获取（LockMode.FAIR 公平锁）
     */
    @PostMapping("/fair-quota/acquire")
    public Result<FairQuotaVO> acquireFairQuota(@Valid @RequestBody FairQuotaRequest request) {
        FairQuotaVO quota = stockDeductScenarioService.acquireFairQuota(request);
        return Result.success(quota);
    }

    /**
     * 6. 组合套餐多 SKU 批量联锁扣减 (MultiLock)
     */
    @PostMapping("/multi-sku/deduct")
    public Result<MultiSkuDeductVO> deductMultiSku(@Valid @RequestBody MultiSkuDeductRequest request) {
        MultiSkuDeductVO result = stockDeductScenarioService.deductMultiSku(request);
        return Result.success(result);
    }

    /**
     * 7. 高峰争锁超时 fallback 排队引导降级
     */
    @PostMapping("/try-acquire-fallback")
    public Result<LockFallbackVO> tryAcquireFallback(@Valid @RequestBody LockFallbackRequest request) {
        LockFallbackVO result = stockDeductScenarioService.executeWithFallback(request);
        return Result.success(result);
    }

    /**
     * 8. 静态工具门面 RedisLockUtil 一行代码安全加锁
     */
    @PostMapping("/static-lock")
    public Result<StaticLockVO> executeStaticLock(@Valid @RequestBody StaticLockRequest request) {
        StaticLockVO result = stockDeductScenarioService.executeStaticLock(request);
        return Result.success(result);
    }
}
