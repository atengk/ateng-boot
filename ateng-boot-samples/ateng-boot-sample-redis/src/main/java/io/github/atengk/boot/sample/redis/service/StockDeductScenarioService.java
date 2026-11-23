package io.github.atengk.boot.sample.redis.service;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.redis.lock.annotation.DistributedLock;
import io.github.atengk.boot.redis.lock.enums.LockMode;
import io.github.atengk.boot.redis.lock.template.DistributedLockTemplate;
import io.github.atengk.boot.redis.lock.util.RedisLockUtil;
import io.github.atengk.boot.sample.redis.model.lock.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 分布式锁库存扣减、读写分离、公平锁与多锁协同场景服务
 * 演示四阶锁模式、看门狗自动续期、锁跨服务可重入性与争锁降级
 *
 * @author Ateng
 * @since 2026-10-27
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StockDeductScenarioService {

    private final DistributedLockTemplate distributedLockTemplate;

    /**
     * 模拟商品初始库存
     */
    private final Map<Long, Integer> stockTable = new ConcurrentHashMap<>(Map.of(
            1001L, 100,
            1002L, 50,
            1003L, 10
    ));

    /**
     * 模拟促销规则表
     */
    private final Map<String, PromotionRuleVO> promoRuleTable = new ConcurrentHashMap<>(Map.of(
            "RULE_618", new PromotionRuleVO("RULE_618", "年中狂欢大促", new BigDecimal("0.85"), System.currentTimeMillis()),
            "RULE_1111", new PromotionRuleVO("RULE_1111", "双十一热卖", new BigDecimal("0.70"), System.currentTimeMillis())
    ));

    /**
     * 模拟稀缺名额活动排队计数器
     */
    private final AtomicInteger fairQueueCounter = new AtomicInteger(0);

    /**
     * 声明式分布式锁扣减秒杀库存（防超卖）
     *
     * @param request 扣减请求
     * @return 扣减结果 VO
     */
    @DistributedLock(key = "'product:' + #request.productId", waitTime = 3000, leaseTime = 5000, mode = LockMode.REENTRANT)
    public StockDeductResultVO deductStock(StockDeductRequest request) {
        Long productId = request.getProductId();
        int count = request.getCount();
        log.info("[StockDeduct] 获取分布式锁成功，开始扣减库存: productId={}, count={}", productId, count);

        Integer currentStock = stockTable.get(productId);
        if (currentStock == null) {
            throw new BusinessException("商品不存在: " + productId);
        }
        if (currentStock < count) {
            throw new BusinessException("库存不足，当前库存: " + currentStock + ", 拟扣减: " + count);
        }
        int remaining = currentStock - count;
        stockTable.put(productId, remaining);
        log.info("[StockDeduct] 库存扣减完成: productId={}, remaining={}", productId, remaining);

        return StockDeductResultVO.builder()
                .productId(productId)
                .remainingStock(remaining)
                .success(true)
                .message("库存扣减成功")
                .build();
    }

    /**
     * 兼容存量单测签名
     */
    public int deductStock(Long productId, int count) {
        StockDeductResultVO result = deductStock(StockDeductRequest.builder().productId(productId).count(count).build());
        return result.getRemainingStock();
    }

    /**
     * 促销规则写入/修改（排他写锁 LockMode.WRITE）
     *
     * @param request 规则更新请求
     * @return 更新后的规则 VO
     */
    public PromotionRuleVO updatePromotionRule(PromotionRuleUpdateRequest request) {
        String lockKey = "promo:rule:" + request.getRuleId();
        return distributedLockTemplate.execute(
                lockKey,
                LockMode.WRITE,
                3000L,
                5000L,
                TimeUnit.MILLISECONDS,
                () -> {
                    log.info("[StockDeduct] 获取排他写锁成功，更新促销规则: ruleId={}, ruleName={}",
                            request.getRuleId(), request.getRuleName());
                    PromotionRuleVO ruleVO = PromotionRuleVO.builder()
                            .ruleId(request.getRuleId())
                            .ruleName(request.getRuleName())
                            .discountRate(request.getDiscountRate())
                            .updateTime(System.currentTimeMillis())
                            .build();
                    promoRuleTable.put(request.getRuleId(), ruleVO);
                    return ruleVO;
                }
        );
    }

    /**
     * 促销规则查询（共享读锁 LockMode.READ）
     *
     * @param ruleId 规则 ID
     * @return 规则详情 VO
     */
    public PromotionRuleVO getPromotionRule(String ruleId) {
        String lockKey = "promo:rule:" + ruleId;
        return distributedLockTemplate.execute(
                lockKey,
                LockMode.READ,
                3000L,
                5000L,
                TimeUnit.MILLISECONDS,
                () -> {
                    log.info("[StockDeduct] 获取共享读锁成功，读取促销规则: ruleId={}", ruleId);
                    PromotionRuleVO rule = promoRuleTable.get(ruleId);
                    if (rule == null) {
                        throw new BusinessException("促销规则不存在: " + ruleId);
                    }
                    return rule;
                }
        );
    }

    /**
     * 稀缺名额排队获取（公平锁 LockMode.FAIR）
     *
     * @param request 排队请求
     * @return 排队获取结果 VO
     */
    @DistributedLock(key = "'fair:activity:' + #request.activityId", waitTime = 3000, leaseTime = 5000, mode = LockMode.FAIR)
    public FairQuotaVO acquireFairQuota(FairQuotaRequest request) {
        int queueNo = fairQueueCounter.incrementAndGet();
        log.info("[StockDeduct] 公平锁排队获取名额成功: activityId={}, userId={}, queueNo={}",
                request.getActivityId(), request.getUserId(), queueNo);

        return FairQuotaVO.builder()
                .activityId(request.getActivityId())
                .userId(request.getUserId())
                .queueNumber(queueNo)
                .acquired(true)
                .build();
    }

    /**
     * 组合套餐多 SKU 批量联锁扣减 (MultiLock)
     *
     * @param request 扣减请求
     * @return 扣减结果 VO
     */
    public MultiSkuDeductVO deductMultiSku(MultiSkuDeductRequest request) {
        List<String> lockKeys = request.getProductIds().stream()
                .map(id -> "product:" + id)
                .toList();

        return distributedLockTemplate.execute(
                lockKeys,
                LockMode.REENTRANT,
                3000L,
                5000L,
                TimeUnit.MILLISECONDS,
                () -> {
                    log.info("[StockDeduct] 批量联锁获取成功，扣减组合套餐商品: comboId={}, products={}",
                            request.getComboId(), request.getProductIds());

                    // 1. 预检所有商品库存
                    for (Long productId : request.getProductIds()) {
                        Integer current = stockTable.get(productId);
                        if (current == null || current < request.getQuantity()) {
                            throw new BusinessException("商品库存不足，扣减终止: productId=" + productId);
                        }
                    }

                    // 2. 扣减库存
                    for (Long productId : request.getProductIds()) {
                        stockTable.computeIfPresent(productId, (k, v) -> v - request.getQuantity());
                    }

                    return MultiSkuDeductVO.builder()
                            .comboId(request.getComboId())
                            .productIds(request.getProductIds())
                            .success(true)
                            .message("组合套餐全量商品联锁扣减成功")
                            .build();
                }
        );
    }

    /**
     * 兼容存量单测批量多锁
     */
    public String executeMultiLock(String orderId, Long productId) {
        MultiSkuDeductVO vo = deductMultiSku(MultiSkuDeductRequest.builder()
                .comboId(orderId)
                .productIds(List.of(productId))
                .quantity(1)
                .build());
        return "MULTI_LOCK_SUCCESS_" + orderId + "_" + productId;
    }

    /**
     * 争锁超时降级演示
     *
     * @param request 请求对象
     * @return 降级结果 VO
     */
    public LockFallbackVO executeWithFallback(LockFallbackRequest request) {
        String resourceId = request.getResourceId();
        return distributedLockTemplate.execute(
                "resource:" + resourceId,
                () -> LockFallbackVO.builder()
                        .resourceId(resourceId)
                        .fallbackTriggered(false)
                        .result("NORMAL_SUCCESS")
                        .build(),
                () -> LockFallbackVO.builder()
                        .resourceId(resourceId)
                        .fallbackTriggered(true)
                        .result("FALLBACK_TRIGGERED")
                        .build()
        );
    }

    /**
     * 兼容存量单测争锁超时降级方法
     */
    public String executeWithFallback(String resourceId) {
        LockFallbackVO vo = executeWithFallback(new LockFallbackRequest(resourceId));
        return vo.getFallbackTriggered() ? "FALLBACK_TRIGGERED_" + resourceId : "NORMAL_SUCCESS_" + resourceId;
    }

    /**
     * 静态门面 RedisLockUtil 调用演示
     *
     * @param request 请求对象
     * @return 静态加锁结果 VO
     */
    public StaticLockVO executeStaticLock(StaticLockRequest request) {
        String key = "static:" + request.getResourceId();
        return RedisLockUtil.execute(key, () -> {
            log.info("[StockDeduct] 静态工具 RedisLockUtil 加锁成功: key={}", key);
            return StaticLockVO.builder()
                    .resourceId(request.getResourceId())
                    .locked(true)
                    .message("静态门面锁执行成功")
                    .build();
        });
    }

    /**
     * 声明式与编程式嵌套重入锁自验（兼容存量单测）
     *
     * @param orderId 订单标识
     * @return 业务结果
     */
    @DistributedLock(key = "'order:' + #orderId", waitTime = 3000, leaseTime = 5000, mode = LockMode.REENTRANT)
    public String executeReentrantBusiness(String orderId) {
        log.info("[StockDeduct] 外层注解获取分布式锁成功: orderId={}", orderId);
        return distributedLockTemplate.execute(
                "order:" + orderId,
                LockMode.REENTRANT,
                1000L,
                3000L,
                TimeUnit.MILLISECONDS,
                () -> {
                    log.info("[StockDeduct] 内层同一线程重入持锁成功: orderId={}", orderId);
                    return "REENTRANT_SUCCESS_" + orderId;
                }
        );
    }

    /**
     * 看门狗自动续期长事务业务自验（leaseTime = -1）
     *
     * @param taskId 任务标识
     * @return 执行结果
     */
    @DistributedLock(key = "'watchdog:' + #taskId", leaseTime = -1)
    public String executeWithWatchdog(String taskId) {
        log.info("[StockDeduct] 激活 Redisson 看门狗持锁执行长事务: taskId={}", taskId);
        return "WATCHDOG_SUCCESS_" + taskId;
    }

    /**
     * 获取指定商品当前剩余库存
     */
    public int getStock(Long productId) {
        return stockTable.getOrDefault(productId, 0);
    }

    /**
     * 重置库存表
     */
    public void resetStock(Long productId, int stock) {
        stockTable.put(productId, stock);
    }
}
