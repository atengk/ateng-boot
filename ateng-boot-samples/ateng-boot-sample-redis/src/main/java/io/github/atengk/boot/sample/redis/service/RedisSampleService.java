package io.github.atengk.boot.sample.redis.service;

import io.github.atengk.boot.redis.lock.annotation.DistributedLock;
import io.github.atengk.boot.redis.lock.enums.LockMode;
import io.github.atengk.boot.redis.util.RedisUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * Redis 协同示例服务
 * 演示声明式分布式锁与 RedisUtil 原子操作（零数据库依赖）
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisSampleService {

    private final RedisUtil redisUtil;

    /**
     * 声明式分布式锁业务执行
     *
     * @param orderId 订单标识
     * @return 执行结果
     */
    @DistributedLock(key = "'order:' + #orderId", waitTime = 2000, leaseTime = 8000, mode = LockMode.REENTRANT)
    public String executeOrderBusiness(String orderId) {
        log.info("获得分布式锁，开始处理订单业务: orderId={}", orderId);
        return "SUCCESS_ORDER_" + orderId;
    }

    /**
     * 模拟并发扣减库存
     *
     * @param productId 商品 ID
     * @param count     扣减数量
     * @return 扣减结果说明
     */
    @DistributedLock(key = "'product:' + #productId", waitTime = 3000, leaseTime = 5000, mode = LockMode.REENTRANT)
    public String deductStock(Long productId, Integer count) {
        String stockKey = "product:stock:" + productId;
        log.info("分布式锁锁定商品: productId={}, 扣减数量={}", productId, count);
        return "扣减库存成功: " + count;
    }
}
