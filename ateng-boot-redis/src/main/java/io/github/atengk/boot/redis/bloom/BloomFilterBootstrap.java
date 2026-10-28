package io.github.atengk.boot.redis.bloom;

import io.github.atengk.boot.redis.properties.RedisProperties;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;

import java.util.Map;

/**
 * 分布式布隆过滤器声明式预热生命周期调度器
 * 在 Spring Boot 单例实例化就绪后，自动根据 YAML 配置的 {@code ateng.boot.redis.bloom-filters.*} 规则，
 * 原子化触发 {@link RedisBloomFilter#tryInit(String, long, double)} 完成预热。
 *
 * @author Ateng
 * @since 2026-10-19
 */
public class BloomFilterBootstrap implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(BloomFilterBootstrap.class);

    private final RedisBloomFilter redisBloomFilter;
    private final RedisProperties redisProperties;

    /**
     * 构造函数注入依赖
     *
     * @param redisBloomFilter 布隆过滤器门面
     * @param redisProperties 配置属性
     */
    public BloomFilterBootstrap(RedisBloomFilter redisBloomFilter, RedisProperties redisProperties) {
        Assert.notNull(redisBloomFilter, "RedisBloomFilter 不能为空");
        this.redisBloomFilter = redisBloomFilter;
        this.redisProperties = redisProperties != null ? redisProperties : new RedisProperties();
    }

    @Override
    public void afterSingletonsInstantiated() {
        Map<String, RedisProperties.BloomFilterConfig> configs = redisProperties.getBloomFilters();
        if (CollectionUtils.isEmpty(configs)) {
            return;
        }

        // 遍历所有在配置中声明的布隆过滤器规则，原子幂等预热
        for (Map.Entry<String, RedisProperties.BloomFilterConfig> entry : configs.entrySet()) {
            String filterName = entry.getKey();
            RedisProperties.BloomFilterConfig config = entry.getValue();
            if (config == null) {
                config = new RedisProperties.BloomFilterConfig();
            }

            long expectedInsertions = config.getExpectedInsertions() > 0 ? config.getExpectedInsertions() : 100_000L;
            double falseProbability = (config.getFalseProbability() > 0.0 && config.getFalseProbability() < 1.0)
                    ? config.getFalseProbability() : 0.01;

            try {
                boolean created = redisBloomFilter.tryInit(filterName, expectedInsertions, falseProbability);
                if (created) {
                    log.info("分布式布隆过滤器 [{}] 声明式预热初始化成功: expectedInsertions={}, falseProbability={}",
                            filterName, expectedInsertions, falseProbability);
                } else {
                    log.debug("分布式布隆过滤器 [{}] 已在 Redis 中存在，跳过初始化", filterName);
                }
            } catch (Exception e) {
                log.error("分布式布隆过滤器 [{}] 声明式预热初始化失败: {}", filterName, e.getMessage(), e);
            }
        }
    }
}
