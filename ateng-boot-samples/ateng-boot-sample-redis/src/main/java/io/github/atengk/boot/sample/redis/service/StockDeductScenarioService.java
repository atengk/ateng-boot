package io.github.atengk.boot.sample.redis.service;

import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.redis.lock.annotation.DistributedLock;
import io.github.atengk.boot.redis.lock.enums.LockMode;
import io.github.atengk.boot.redis.lock.template.DistributedLockTemplate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 分布式锁库存扣减与并发防超卖场景服务
 * 演示声明式分布式锁、看门狗自动续期、锁可重入性与争锁超时降级闭包
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
     * 声明式分布式锁扣减库存（防超卖）
     *
     * @param productId 商品 ID
     * @param count     扣减数量
     * @return 扣减后剩余库存
     */
    @DistributedLock(key = "'product:' + #productId", waitTime = 3000, leaseTime = 5000, mode = LockMode.REENTRANT)
    public int deductStock(Long productId, int count) {
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
        return remaining;
    }

    /**
     * 分布式锁可重入自验（外层注解加锁，内层通过 DistributedLockTemplate 同一线程重入同一锁）
     *
     * @param orderId 订单标识
     * @return 执行结果
     */
    @DistributedLock(key = "'reentrant:' + #orderId", mode = LockMode.REENTRANT)
    public String executeReentrantBusiness(String orderId) {
        log.info("[StockDeduct] 外层方法获取分布式锁成功: orderId={}", orderId);
        return distributedLockTemplate.execute("reentrant:" + orderId, () -> {
            log.info("[StockDeduct] 内层闭包同一线程重入同一锁成功: orderId={}", orderId);
            return "REENTRANT_SUCCESS_" + orderId;
        });
    }

    /**
     * 分布式锁争锁超时降级演示（利用 fallback 闭包防御并发风暴）
     *
     * @param resourceId 资源标识
     * @return 业务结果或降级结果
     */
    public String executeWithFallback(String resourceId) {
        return distributedLockTemplate.execute(
                "resource:" + resourceId,
                () -> "NORMAL_SUCCESS_" + resourceId,
                () -> "FALLBACK_TRIGGERED_" + resourceId
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
     * 编程式批量多锁执行 (MultiLock)
     *
     * @param orderId   订单 ID
     * @param productId 商品 ID
     * @return 执行结果
     */
    public String executeMultiLock(String orderId, Long productId) {
        return distributedLockTemplate.execute(
                java.util.List.of("lock:order:" + orderId, "lock:product:" + productId),
                LockMode.REENTRANT,
                3000L,
                5000L,
                java.util.concurrent.TimeUnit.MILLISECONDS,
                () -> "MULTI_LOCK_SUCCESS_" + orderId + "_" + productId
        );
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
