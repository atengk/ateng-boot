package io.github.atengk.boot.redis.bloom;

import io.github.atengk.boot.redis.properties.RedisProperties;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;
import org.springframework.util.CollectionUtils;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * 生产级分布式布隆过滤器操作门面
 * 基于 Redisson {@link RBloomFilter} 深度封装，支持 Spring Bean 依赖注入与全局静态门面双重调用范式，
 * 内置声明式预热校验、键名前缀分层纳管与元素填充率 80% 容量预警，
 * 适用于海量黑名单过滤、爬虫 IP 拦截与缓存穿透防御场景。
 *
 * @author Ateng
 * @since 2026-10-19
 */
public class RedisBloomFilter {

    private static final Logger log = LoggerFactory.getLogger(RedisBloomFilter.class);

    private static volatile RedisBloomFilter instance;

    private final RedissonClient redissonClient;
    private final RedisProperties redisProperties;

    /**
     * Spring 容器依赖注入构造器
     *
     * @param redissonClient Redisson 客户端
     * @param redisProperties Redis 全局配置属性
     */
    public RedisBloomFilter(RedissonClient redissonClient, RedisProperties redisProperties) {
        Assert.notNull(redissonClient, "RedissonClient 不能为空");
        this.redissonClient = redissonClient;
        this.redisProperties = redisProperties != null ? redisProperties : new RedisProperties();
        instance = this;
    }

    /**
     * 初始化静态门面（供无 Spring 容器测试或提前装配使用）
     *
     * @param client Redisson 客户端
     * @param properties 全局配置
     */
    public static void init(RedissonClient client, RedisProperties properties) {
        if (client == null) {
            instance = null;
        } else {
            instance = new RedisBloomFilter(client, properties);
        }
    }

    /**
     * 获取受管的 RedisBloomFilter Bean 单例实例
     *
     * @return RedisBloomFilter 实例
     */
    public static RedisBloomFilter getInstance() {
        RedisBloomFilter inst = instance;
        if (inst == null) {
            throw new IllegalStateException("RedisBloomFilter 尚未完成初始化，请确认 Spring 上下文已启动且 RedissonClient 已装配。");
        }
        return inst;
    }

    // ==================== 1. 实例方法 (Spring Bean 依赖注入范式) ====================

    /**
     * 构建受管布隆过滤器在 Redis 中的完整物理键名，统一应用全局 keyPrefix
     *
     * @param filterName 业务过滤器名称
     * @return 完整 Redis Key
     */
    public String buildKey(String filterName) {
        Assert.hasText(filterName, "filterName 不能为空");
        String prefix = redisProperties != null ? redisProperties.getKeyPrefix() : "";
        String baseName = filterName.startsWith("bloom:") ? filterName : "bloom:" + filterName;
        if (StringUtils.hasText(prefix) && !baseName.startsWith(prefix)) {
            return prefix + baseName;
        }
        return baseName;
    }

    /**
     * 获取 Redisson 原生分布式布隆过滤器句柄
     *
     * @param filterName 过滤器名称
     * @param <T> 元素类型
     * @return RBloomFilter 句柄
     */
    public <T> RBloomFilter<T> getFilter(String filterName) {
        String realKey = buildKey(filterName);
        return redissonClient.getBloomFilter(realKey);
    }

    /**
     * 尝试原子初始化分布式布隆过滤器（若已存在则忽略）
     *
     * @param filterName 过滤器名称
     * @param expectedInsertions 预估插入容量（必须大于 0）
     * @param falseProbability 期望误判率（必须在 (0, 1) 区间，如 0.01）
     * @return 若初次创建并初始化成功返回 true；若已存在返回 false
     */
    public boolean tryInit(String filterName, long expectedInsertions, double falseProbability) {
        Assert.hasText(filterName, "filterName 不能为空");
        Assert.isTrue(expectedInsertions > 0, "expectedInsertions 必须大于 0");
        Assert.isTrue(falseProbability > 0.0 && falseProbability < 1.0, "falseProbability 必须处于 (0, 1) 区间");

        RBloomFilter<Object> filter = getFilter(filterName);
        return filter.tryInit(expectedInsertions, falseProbability);
    }

    /**
     * 向布隆过滤器添加单个元素，并实施容量填充率安全检查
     *
     * @param filterName 过滤器名称
     * @param value 待添加元素
     * @param <T> 元素类型
     * @return 若元素添加前可能不存在（位图产生变化）返回 true；若已存在返回 false
     */
    public <T> boolean add(String filterName, T value) {
        Assert.hasText(filterName, "filterName 不能为空");
        if (value == null) {
            return false;
        }
        RBloomFilter<T> filter = getFilter(filterName);
        boolean added = filter.add(value);
        checkCapacityWarn(filterName, filter);
        return added;
    }

    /**
     * 批量向布隆过滤器添加元素
     *
     * @param filterName 过滤器名称
     * @param values 待添加元素集合
     * @param <T> 元素类型
     * @return 实际新添加成功的元素近似数量
     */
    public <T> long addAll(String filterName, Collection<T> values) {
        Assert.hasText(filterName, "filterName 不能为空");
        if (CollectionUtils.isEmpty(values)) {
            return 0L;
        }
        RBloomFilter<T> filter = getFilter(filterName);
        long count = 0L;
        for (T value : values) {
            if (value != null && filter.add(value)) {
                count++;
            }
        }
        checkCapacityWarn(filterName, filter);
        return count;
    }

    /**
     * 判定元素是否可能存在于布隆过滤器中
     *
     * @param filterName 过滤器名称
     * @param value 待判定元素
     * @param <T> 元素类型
     * @return true 可能存在；false 一定不存在
     */
    public <T> boolean contains(String filterName, T value) {
        Assert.hasText(filterName, "filterName 不能为空");
        if (value == null) {
            return false;
        }
        RBloomFilter<T> filter = getFilter(filterName);
        return filter.contains(value);
    }

    /**
     * 批量判定元素是否可能存在于布隆过滤器中
     *
     * @param filterName 过滤器名称
     * @param values 待判定元素集合
     * @param <T> 元素类型
     * @return 判定结果列表（保持与入参集合相同的迭代顺序）
     */
    public <T> List<Boolean> containsAll(String filterName, Collection<T> values) {
        Assert.hasText(filterName, "filterName 不能为空");
        if (CollectionUtils.isEmpty(values)) {
            return Collections.emptyList();
        }
        RBloomFilter<T> filter = getFilter(filterName);
        List<Boolean> results = new ArrayList<>(values.size());
        for (T value : values) {
            if (value == null) {
                results.add(false);
            } else {
                results.add(filter.contains(value));
            }
        }
        return results;
    }

    /**
     * 获取布隆过滤器当前已添加元素的近似计数
     *
     * @param filterName 过滤器名称
     * @return 近似元素计数
     */
    public long count(String filterName) {
        Assert.hasText(filterName, "filterName 不能为空");
        return getFilter(filterName).count();
    }

    /**
     * 获取布隆过滤器预估容量
     *
     * @param filterName 过滤器名称
     * @return 预估容量
     */
    public long getExpectedInsertions(String filterName) {
        Assert.hasText(filterName, "filterName 不能为空");
        return getFilter(filterName).getExpectedInsertions();
    }

    /**
     * 获取布隆过滤器预估误判率
     *
     * @param filterName 过滤器名称
     * @return 误判率
     */
    public double getFalseProbability(String filterName) {
        Assert.hasText(filterName, "filterName 不能为空");
        return getFilter(filterName).getFalseProbability();
    }

    /**
     * 获取布隆过滤器当前容量填充率 (count / expectedInsertions)
     *
     * @param filterName 过滤器名称
     * @return 容量填充率（0.0 ~ 1.0+）
     */
    public double getUsageRate(String filterName) {
        Assert.hasText(filterName, "filterName 不能为空");
        RBloomFilter<?> filter = getFilter(filterName);
        long expected = filter.getExpectedInsertions();
        if (expected <= 0) {
            return 0.0;
        }
        return (double) filter.count() / expected;
    }

    /**
     * 检查布隆过滤器容量是否已达到或超过指定阈值比例
     *
     * @param filterName 过滤器名称
     * @param thresholdRate 阈值比例（如 0.8 表示 80%）
     * @return 是否超过阈值
     */
    public boolean isOverCapacity(String filterName, double thresholdRate) {
        return getUsageRate(filterName) >= thresholdRate;
    }

    /**
     * 检查布隆过滤器是否已在 Redis 中物理存在
     *
     * @param filterName 过滤器名称
     * @return 是否存在
     */
    public boolean isExists(String filterName) {
        Assert.hasText(filterName, "filterName 不能为空");
        return getFilter(filterName).isExists();
    }

    /**
     * 删除分布式布隆过滤器位图数据（对称语义物理销毁）
     *
     * @param filterName 过滤器名称
     * @return 是否删除成功
     */
    public boolean delete(String filterName) {
        Assert.hasText(filterName, "filterName 不能为空");
        return getFilter(filterName).delete();
    }

    /**
     * 元素填充率 80% 安全告警防线
     */
    private void checkCapacityWarn(String filterName, RBloomFilter<?> filter) {
        try {
            long expected = filter.getExpectedInsertions();
            if (expected <= 0) {
                return;
            }
            long currentCount = filter.count();
            double usage = (double) currentCount / expected;
            if (usage >= 0.8) {
                String usagePercent = String.format("%.2f%%", usage * 100);
                log.warn("分布式布隆过滤器 [{}] 容量逼近告警: 当前估计元素数 {}，预估容量 {}，填充率 {}（建议规划扩容或重建新过滤器以防误判率恶化）",
                        filterName, currentCount, expected, usagePercent);
            }
        } catch (Exception e) {
            log.debug("布隆过滤器 [{}] 容量检查异常（忽略）: {}", filterName, e.getMessage());
        }
    }
}
