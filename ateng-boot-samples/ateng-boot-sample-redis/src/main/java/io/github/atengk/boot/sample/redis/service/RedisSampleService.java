package io.github.atengk.boot.sample.redis.service;

import io.github.atengk.boot.redis.atomic.RedisAtomicUtil;
import io.github.atengk.boot.redis.lock.annotation.DistributedLock;
import io.github.atengk.boot.redis.lock.enums.LockMode;
import io.github.atengk.boot.redis.lock.template.DistributedLockTemplate;
import io.github.atengk.boot.redis.util.RedisUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Set;

/**
 * Redis 协同示例服务
 * 演示声明式分布式锁、看门狗自动续期、锁可重入性、争锁超时降级、RedisUtil 安全游标扫描与原子发号（零数据库依赖）
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisSampleService {

    private final RedisUtil redisUtil;
    private final DistributedLockTemplate distributedLockTemplate;
    private final RedisAtomicUtil redisAtomicUtil;

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
        log.info("分布式锁锁定商品: productId={}, 扣减数量={}", productId, count);
        return "扣减库存成功: " + count;
    }

    /**
     * 分布式锁可重入自验（外层注解加锁，内层通过 DistributedLockTemplate 同一线程重入同一锁）
     *
     * @param orderId 订单标识
     * @return 执行结果
     */
    @DistributedLock(key = "'reentrant:' + #orderId", mode = LockMode.REENTRANT)
    public String executeReentrantBusiness(String orderId) {
        log.info("外层方法获取分布式锁成功: orderId={}", orderId);
        return distributedLockTemplate.execute("reentrant:" + orderId, () -> {
            log.info("内层闭包同一线程重入同一锁成功: orderId={}", orderId);
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
        log.info("激活 Redisson 看门狗持锁执行长事务: taskId={}", taskId);
        return "WATCHDOG_SUCCESS_" + taskId;
    }

    /**
     * RedisUtil 安全游标模糊扫描（替代阻塞的 keys *）
     *
     * @param pattern 模式匹配串
     * @param count   单次批次数量
     * @return 匹配的键集合
     */
    public Set<String> scanKeys(String pattern, long count) {
        Set<String> keys = RedisUtil.scanKeys(pattern, count);
        log.info("RedisUtil 游标模糊扫描完成: pattern={}, count={}, matchedSize={}", pattern, count, keys.size());
        return keys;
    }

    /**
     * 生成按日重置带日期前缀的流水号（如 ORD20261019000001）
     *
     * @param prefix 业务前缀
     * @param length 尾部递增序列位宽
     * @return 完整流水号
     */
    public String generateDailyOrderNo(String prefix, int length) {
        String seq = redisAtomicUtil.generateDailySeq(prefix, length);
        log.info("生成结构化原子流水号成功: prefix={}, length={}, seq={}", prefix, length, seq);
        return seq;
    }

    /**
     * 基于 ZSet 毫秒滑动窗口严格限流校验
     *
     * @param key         限流键
     * @param maxRequests 窗口内最大请求数
     * @param window      窗口时长
     * @return 是否放行
     */
    public boolean checkSlidingWindow(String key, int maxRequests, Duration window) {
        boolean allowed = redisAtomicUtil.isAllowedBySlidingWindow(key, maxRequests, window);
        log.info("滑动窗口频控校验: key={}, maxRequests={}, window={}, allowed={}", key, maxRequests, window, allowed);
        return allowed;
    }
}
