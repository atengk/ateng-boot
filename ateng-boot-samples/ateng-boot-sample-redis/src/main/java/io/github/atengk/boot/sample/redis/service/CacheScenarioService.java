package io.github.atengk.boot.sample.redis.service;

import io.github.atengk.boot.redis.bloom.RedisBloomFilter;
import io.github.atengk.boot.redis.cache.template.RedisCacheTemplate;
import io.github.atengk.boot.sample.redis.model.BloomFilterMetricsVO;
import io.github.atengk.boot.sample.redis.model.ProductVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 缓存防卫与两级缓存场景服务
 * 演示 RedisCacheTemplate 缓存防穿透/防击穿/批量防 N+1、Spring Cache @Cacheable 两级缓存与分布式布隆过滤器
 *
 * @author Ateng
 * @since 2026-10-27
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CacheScenarioService {

    private final RedisCacheTemplate redisCacheTemplate;
    private final RedisBloomFilter redisBloomFilter;

    private final AtomicInteger dbQueryCount = new AtomicInteger(0);

    private static final Map<Long, ProductVO> MOCK_PRODUCT_DB = Map.of(
            1001L, new ProductVO(1001L, "iPhone 16 Pro", new BigDecimal("7999.00"), 50),
            1002L, new ProductVO(1002L, "MacBook Pro M4", new BigDecimal("12999.00"), 20),
            1003L, new ProductVO(1003L, "AirPods Pro 2", new BigDecimal("1899.00"), 100)
    );

    /**
     * 单商品安全查询（通过 RedisCacheTemplate 防穿透、防击穿、防雪崩）
     *
     * @param productId 商品 ID
     * @return 商品详情（若不存在返回 null，底层写入短 TTL 空哨兵）
     */
    public ProductVO getProduct(Long productId) {
        return redisCacheTemplate.getOrLoad(
                "product:" + productId,
                ProductVO.class,
                key -> {
                    dbQueryCount.incrementAndGet();
                    log.info("[CacheScenario] 缓存未命中，回源模拟数据库查询: productId={}", productId);
                    return MOCK_PRODUCT_DB.get(productId);
                }
        );
    }

    /**
     * 批量商品安全查询（通过 RedisCacheTemplate MGET + 排序锁 + 批量加载防 N+1）
     *
     * @param productIds 商品 ID 列表
     * @return 商品映射 Map
     */
    public Map<Long, ProductVO> batchGetProducts(List<Long> productIds) {
        return redisCacheTemplate.batchGetOrLoad(
                productIds,
                id -> "product:" + id,
                ProductVO.class,
                missingIds -> {
                    dbQueryCount.incrementAndGet();
                    log.info("[CacheScenario] 批量缓存未命中，单次批量回源模拟数据库: missingCount={}", missingIds.size());
                    Map<Long, ProductVO> result = new LinkedHashMap<>();
                    for (Long id : missingIds) {
                        ProductVO p = MOCK_PRODUCT_DB.get(id);
                        if (p != null) {
                            result.put(id, p);
                        }
                    }
                    return result;
                }
        );
    }

    /**
     * 清理指定商品缓存
     *
     * @param productId 商品 ID
     */
    public void evictProduct(Long productId) {
        redisCacheTemplate.evict("product:" + productId);
        log.info("[CacheScenario] 已驱逐商品缓存: productId={}", productId);
    }

    /**
     * 基于 Spring Cache @Cacheable 两级缓存查询用户档案
     *
     * @param userId 用户 ID
     * @return 档案角色
     */
    @Cacheable(cacheNames = "user-profile", key = "#userId")
    public String getUserProfile(Long userId) {
        dbQueryCount.incrementAndGet();
        log.info("[CacheScenario] 两级缓存未命中，执行模拟数据库查询: userId={}", userId);
        return "Role_VIP_" + userId;
    }

    /**
     * 淘汰指定用户档案两级缓存 (@CacheEvict)
     *
     * @param userId 用户 ID
     */
    @CacheEvict(cacheNames = "user-profile", key = "#userId")
    public void evictUserProfile(Long userId) {
        log.info("[CacheScenario] 淘汰用户档案两级缓存: userId={}", userId);
    }

    /**
     * 清空用户档案全量两级缓存 (@CacheEvict allEntries = true)
     */
    @CacheEvict(cacheNames = "user-profile", allEntries = true)
    public void clearUserProfileCache() {
        log.info("[CacheScenario] 清空用户档案全量两级缓存");
    }

    /**
     * 将用户添加至黑名单布隆过滤器
     *
     * @param userId 用户 ID
     * @return 是否添加成功
     */
    public boolean addBlacklist(String userId) {
        boolean added = redisBloomFilter.add("user-blacklist", userId);
        log.info("[CacheScenario] 添加用户至布隆黑名单: userId={}, added={}", userId, added);
        return added;
    }

    /**
     * 判定用户是否可能处于黑名单中
     *
     * @param userId 用户 ID
     * @return 是否处于黑名单
     */
    public boolean checkBlacklist(String userId) {
        boolean inBlacklist = redisBloomFilter.contains("user-blacklist", userId);
        log.info("[CacheScenario] 检测用户是否在布隆黑名单: userId={}, exists={}", userId, inBlacklist);
        return inBlacklist;
    }

    /**
     * 获取黑名单布隆过滤器监控指标
     *
     * @return 指标 VO
     */
    public BloomFilterMetricsVO getBlacklistMetrics() {
        return BloomFilterMetricsVO.builder()
                .filterName("user-blacklist")
                .count(redisBloomFilter.count("user-blacklist"))
                .expectedInsertions(redisBloomFilter.getExpectedInsertions("user-blacklist"))
                .usageRate(redisBloomFilter.getUsageRate("user-blacklist"))
                .falseProbability(redisBloomFilter.getFalseProbability("user-blacklist"))
                .build();
    }

    public int getDbQueryCount() {
        return dbQueryCount.get();
    }

    public void resetDbQueryCount() {
        dbQueryCount.set(0);
    }
}
