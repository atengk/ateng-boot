package io.github.atengk.boot.redis.bloom;

import org.redisson.api.RBloomFilter;

import java.util.Collection;
import java.util.List;

/**
 * 生产级分布式布隆过滤器全局静态操作门面
 * 底层统一委托受管单例 {@link RedisBloomFilter}，为无需 Spring Bean 依赖注入的工具类或静态上下文提供对称门面支持。
 *
 * @author Ateng
 * @since 2026-10-19
 */
public final class RedisBloomUtil {

    private RedisBloomUtil() {
    }

    private static RedisBloomFilter getFilter() {
        return RedisBloomFilter.getInstance();
    }

    /**
     * 构建受管布隆过滤器在 Redis 中的完整物理键名，统一应用全局 keyPrefix
     *
     * @param filterName 业务过滤器名称
     * @return 完整 Redis Key
     */
    public static String buildKey(String filterName) {
        return getFilter().buildKey(filterName);
    }

    /**
     * 获取 Redisson 原生分布式布隆过滤器句柄
     *
     * @param filterName 过滤器名称
     * @param <T> 元素类型
     * @return RBloomFilter 句柄
     */
    public static <T> RBloomFilter<T> getFilter(String filterName) {
        return getFilter().getFilter(filterName);
    }

    /**
     * 尝试原子初始化分布式布隆过滤器（若已存在则忽略）
     *
     * @param filterName 过滤器名称
     * @param expectedInsertions 预估插入容量（必须大于 0）
     * @param falseProbability 期望误判率（必须在 (0, 1) 区间，如 0.01）
     * @return 若初次创建并初始化成功返回 true；若已存在返回 false
     */
    public static boolean tryInit(String filterName, long expectedInsertions, double falseProbability) {
        return getFilter().tryInit(filterName, expectedInsertions, falseProbability);
    }

    /**
     * 向布隆过滤器添加单个元素，并实施容量填充率安全检查
     *
     * @param filterName 过滤器名称
     * @param value 待添加元素
     * @param <T> 元素类型
     * @return 若元素添加前可能不存在（位图产生变化）返回 true；若已存在返回 false
     */
    public static <T> boolean add(String filterName, T value) {
        return getFilter().add(filterName, value);
    }

    /**
     * 批量向布隆过滤器添加元素
     *
     * @param filterName 过滤器名称
     * @param values 待添加元素集合
     * @param <T> 元素类型
     * @return 实际新添加成功的元素近似数量
     */
    public static <T> long addAll(String filterName, Collection<T> values) {
        return getFilter().addAll(filterName, values);
    }

    /**
     * 判定元素是否可能存在于布隆过滤器中
     *
     * @param filterName 过滤器名称
     * @param value 待判定元素
     * @param <T> 元素类型
     * @return true 可能存在；false 一定不存在
     */
    public static <T> boolean contains(String filterName, T value) {
        return getFilter().contains(filterName, value);
    }

    /**
     * 批量判定元素是否可能存在于布隆过滤器中
     *
     * @param filterName 过滤器名称
     * @param values 待判定元素集合
     * @param <T> 元素类型
     * @return 判定结果列表（保持与入参集合相同的迭代顺序）
     */
    public static <T> List<Boolean> containsAll(String filterName, Collection<T> values) {
        return getFilter().containsAll(filterName, values);
    }

    /**
     * 获取布隆过滤器当前已添加元素的近似计数
     *
     * @param filterName 过滤器名称
     * @return 近似元素计数
     */
    public static long count(String filterName) {
        return getFilter().count(filterName);
    }

    /**
     * 获取布隆过滤器预估容量
     *
     * @param filterName 过滤器名称
     * @return 预估容量
     */
    public static long getExpectedInsertions(String filterName) {
        return getFilter().getExpectedInsertions(filterName);
    }

    /**
     * 获取布隆过滤器预估误判率
     *
     * @param filterName 过滤器名称
     * @return 误判率
     */
    public static double getFalseProbability(String filterName) {
        return getFilter().getFalseProbability(filterName);
    }

    /**
     * 获取布隆过滤器当前容量填充率 (count / expectedInsertions)
     *
     * @param filterName 过滤器名称
     * @return 容量填充率（0.0 ~ 1.0+）
     */
    public static double getUsageRate(String filterName) {
        return getFilter().getUsageRate(filterName);
    }

    /**
     * 检查布隆过滤器容量是否已达到或超过指定阈值比例
     *
     * @param filterName 过滤器名称
     * @param thresholdRate 阈值比例（如 0.8 表示 80%）
     * @return 是否超过阈值
     */
    public static boolean isOverCapacity(String filterName, double thresholdRate) {
        return getFilter().isOverCapacity(filterName, thresholdRate);
    }

    /**
     * 检查布隆过滤器是否已在 Redis 中物理存在
     *
     * @param filterName 过滤器名称
     * @return 是否存在
     */
    public static boolean isExists(String filterName) {
        return getFilter().isExists(filterName);
    }

    /**
     * 删除分布式布隆过滤器位图数据（对称语义物理销毁）
     *
     * @param filterName 过滤器名称
     * @return 是否删除成功
     */
    public static boolean delete(String filterName) {
        return getFilter().delete(filterName);
    }
}
