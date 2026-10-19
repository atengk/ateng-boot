package io.github.atengk.boot.redis.bloom;

import io.github.atengk.boot.redis.properties.RedisProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 分布式布隆过滤器单元测试
 * 覆盖原子初始化预热、元素判定、批量操作、80% 容量安全预警与全局 KeyPrefix 隔离
 *
 * @author Ateng
 * @since 2026-10-19
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RedisBloomFilter 单元测试")
class RedisBloomFilterTest {

    @Mock
    private RedissonClient redissonClient;

    @Mock
    @SuppressWarnings("rawtypes")
    private RBloomFilter bloomFilterMock;

    private RedisProperties redisProperties;
    private RedisBloomFilter redisBloomFilter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        redisProperties = new RedisProperties();
        redisProperties.setKeyPrefix("test:");

        lenient().when(redissonClient.getBloomFilter(anyString())).thenReturn(bloomFilterMock);

        redisBloomFilter = new RedisBloomFilter(redissonClient, redisProperties);
    }

    @AfterEach
    void tearDown() {
        // 重置静态上下文，确保零测试污染
        RedisBloomFilter.init(null, null);
    }

    @Test
    @DisplayName("1. 参数防卫与快速失败测试")
    void testValidationAndDefensiveGuard() {
        // filterName 为空拦截
        assertThatThrownBy(() -> redisBloomFilter.tryInit("", 1000, 0.01))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> redisBloomFilter.contains("  ", "item"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> redisBloomFilter.add(null, "item"))
                .isInstanceOf(IllegalArgumentException.class);

        // expectedInsertions 非法拦截
        assertThatThrownBy(() -> redisBloomFilter.tryInit("test", 0, 0.01))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> redisBloomFilter.tryInit("test", -10, 0.01))
                .isInstanceOf(IllegalArgumentException.class);

        // falseProbability 越界拦截
        assertThatThrownBy(() -> redisBloomFilter.tryInit("test", 1000, 0.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> redisBloomFilter.tryInit("test", 1000, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> redisBloomFilter.tryInit("test", 1000, -0.05))
                .isInstanceOf(IllegalArgumentException.class);

        // 静态工具 RedisBloomUtil 同样拦截
        assertThatThrownBy(() -> RedisBloomUtil.tryInit("", 1000, 0.01))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("2. 全局 KeyPrefix 统一分层纳管验证")
    void testBuildKeyPrefix() {
        // 全局前缀为 "test:" 时，"blacklist" -> "test:bloom:blacklist"
        assertThat(redisBloomFilter.buildKey("blacklist")).isEqualTo("test:bloom:blacklist");
        assertThat(RedisBloomUtil.buildKey("blacklist")).isEqualTo("test:bloom:blacklist");

        // 若已携带 bloom: 则不重复添加
        assertThat(redisBloomFilter.buildKey("bloom:user")).isEqualTo("test:bloom:user");

        // 若前缀为空时
        redisProperties.setKeyPrefix("");
        RedisBloomFilter filterWithoutPrefix = new RedisBloomFilter(redissonClient, redisProperties);
        assertThat(filterWithoutPrefix.buildKey("blacklist")).isEqualTo("bloom:blacklist");
    }

    @Test
    @DisplayName("3. 尝试原子初始化 tryInit 行为验证")
    @SuppressWarnings("unchecked")
    void testTryInit() {
        when(bloomFilterMock.tryInit(100_000L, 0.01)).thenReturn(true);
        boolean firstInit = redisBloomFilter.tryInit("order-filter", 100_000L, 0.01);
        assertThat(firstInit).isTrue();
        verify(bloomFilterMock).tryInit(100_000L, 0.01);

        // 二次初始化返回 false（已存在）
        when(bloomFilterMock.tryInit(100_000L, 0.01)).thenReturn(false);
        boolean secondInit = RedisBloomUtil.tryInit("order-filter", 100_000L, 0.01);
        assertThat(secondInit).isFalse();
    }

    @Test
    @DisplayName("4. 单元素添加与判定行为验证")
    @SuppressWarnings("unchecked")
    void testAddAndContains() {
        when(bloomFilterMock.add("user_1001")).thenReturn(true);
        when(bloomFilterMock.contains("user_1001")).thenReturn(true);
        when(bloomFilterMock.contains("user_9999")).thenReturn(false);

        boolean added = redisBloomFilter.add("user_filter", "user_1001");
        assertThat(added).isTrue();

        assertThat(redisBloomFilter.contains("user_filter", "user_1001")).isTrue();
        assertThat(RedisBloomUtil.contains("user_filter", "user_9999")).isFalse();

        // null 元素空安全验证
        assertThat(redisBloomFilter.add("user_filter", null)).isFalse();
        assertThat(redisBloomFilter.contains("user_filter", null)).isFalse();
    }

    @Test
    @DisplayName("5. 批量元素添加与判定行为验证")
    @SuppressWarnings("unchecked")
    void testBatchOperations() {
        List<String> items = Arrays.asList("a", "b", "c", null);
        when(bloomFilterMock.add("a")).thenReturn(true);
        when(bloomFilterMock.add("b")).thenReturn(true);
        when(bloomFilterMock.add("c")).thenReturn(false); // 已存在

        long addedCount = redisBloomFilter.addAll("batch_filter", items);
        assertThat(addedCount).isEqualTo(2L);

        when(bloomFilterMock.contains("a")).thenReturn(true);
        when(bloomFilterMock.contains("b")).thenReturn(true);
        when(bloomFilterMock.contains("c")).thenReturn(false);

        List<Boolean> results = RedisBloomUtil.containsAll("batch_filter", items);
        assertThat(results).containsExactly(true, true, false, false);

        // 空集合空安全
        assertThat(redisBloomFilter.addAll("batch_filter", Collections.emptyList())).isEqualTo(0L);
        assertThat(redisBloomFilter.containsAll("batch_filter", Collections.emptyList())).isEmpty();
    }

    @Test
    @DisplayName("6. 容量监控与 80% 安全预警阈值验证")
    @SuppressWarnings("unchecked")
    void testCapacityAndUsageRate() {
        when(bloomFilterMock.getExpectedInsertions()).thenReturn(10_000L);
        when(bloomFilterMock.count()).thenReturn(8_000L);
        when(bloomFilterMock.getFalseProbability()).thenReturn(0.01);

        assertThat(redisBloomFilter.getExpectedInsertions("monitor_filter")).isEqualTo(10_000L);
        assertThat(redisBloomFilter.count("monitor_filter")).isEqualTo(8_000L);
        assertThat(redisBloomFilter.getFalseProbability("monitor_filter")).isEqualTo(0.01);

        // 填充率 8000 / 10000 = 0.8
        double usageRate = RedisBloomUtil.getUsageRate("monitor_filter");
        assertThat(usageRate).isEqualTo(0.80);

        // 超过 80% 判定
        assertThat(redisBloomFilter.isOverCapacity("monitor_filter", 0.80)).isTrue();
        assertThat(redisBloomFilter.isOverCapacity("monitor_filter", 0.90)).isFalse();

        // 当触发 add 时自动检查容量预警
        when(bloomFilterMock.add("item_new")).thenReturn(true);
        boolean added = redisBloomFilter.add("monitor_filter", "item_new");
        assertThat(added).isTrue();
    }

    @Test
    @DisplayName("7. 存在性检查与物理销毁对称性验证")
    @SuppressWarnings("unchecked")
    void testExistsAndDelete() {
        when(bloomFilterMock.isExists()).thenReturn(true);
        when(bloomFilterMock.delete()).thenReturn(true);

        assertThat(redisBloomFilter.isExists("temp_filter")).isTrue();
        assertThat(RedisBloomUtil.delete("temp_filter")).isTrue();

        verify(bloomFilterMock).delete();
    }

    @Test
    @DisplayName("8. 未初始化时的快速失败防卫")
    void testUninitializedException() {
        RedisBloomFilter.init(null, null);

        assertThatThrownBy(() -> RedisBloomUtil.contains("test", "item"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RedisBloomFilter 尚未完成初始化");

        assertThatThrownBy(RedisBloomFilter::getInstance)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("9. BloomFilterBootstrap 声明式自动预热调度测试")
    @SuppressWarnings("unchecked")
    void testBloomFilterBootstrap() {
        Map<String, RedisProperties.BloomFilterConfig> configs = new HashMap<>();

        RedisProperties.BloomFilterConfig blacklistConfig = new RedisProperties.BloomFilterConfig();
        blacklistConfig.setExpectedInsertions(500_000L);
        blacklistConfig.setFalseProbability(0.001);
        configs.put("blacklist", blacklistConfig);

        RedisProperties.BloomFilterConfig defaultFilter = new RedisProperties.BloomFilterConfig();
        configs.put("default-filter", defaultFilter);

        redisProperties.setBloomFilters(configs);

        when(bloomFilterMock.tryInit(anyLong(), anyDouble())).thenReturn(true);

        BloomFilterBootstrap bootstrap = new BloomFilterBootstrap(redisBloomFilter, redisProperties);
        bootstrap.afterSingletonsInstantiated();

        verify(bloomFilterMock).tryInit(eq(500_000L), eq(0.001));
        verify(bloomFilterMock).tryInit(eq(100_000L), eq(0.01));
    }
}
