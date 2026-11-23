package io.github.atengk.boot.job.xxl.model;

import com.xxl.job.core.context.XxlJobContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 分布式任务分片上下文对象 ShardingContext 单元测试
 *
 * @author Ateng
 * @since 2026-10-31
 */
@DisplayName("ShardingContext 单元测试")
class ShardingContextTest {

    @Test
    @DisplayName("测试构造器与非法参数边界归一化")
    void testConstructorAndNormalization() {
        ShardingContext defaultContext = new ShardingContext(0, 1);
        assertEquals(0, defaultContext.getShardIndex());
        assertEquals(1, defaultContext.getShardTotal());
        assertFalse(defaultContext.isSharded());
        assertTrue(defaultContext.isFirst());
        assertTrue(defaultContext.isLast());

        // 边界非法负数输入防御
        ShardingContext normalized = new ShardingContext(-5, 0);
        assertEquals(0, normalized.getShardIndex());
        assertEquals(1, normalized.getShardTotal());
        assertFalse(normalized.isSharded());

        // 多分片中间节点
        ShardingContext middle = new ShardingContext(1, 3);
        assertEquals(1, middle.getShardIndex());
        assertEquals(3, middle.getShardTotal());
        assertTrue(middle.isSharded());
        assertFalse(middle.isFirst());
        assertFalse(middle.isLast());

        // 多分片末尾节点
        ShardingContext last = new ShardingContext(2, 3);
        assertTrue(last.isSharded());
        assertFalse(last.isFirst());
        assertTrue(last.isLast());
    }

    @Test
    @DisplayName("测试数值 ID 分片匹配算法")
    void testMatchLongId() {
        // 单机单分片：无条件匹配所有 ID
        ShardingContext single = new ShardingContext(0, 1);
        assertTrue(single.match(0L));
        assertTrue(single.match(100L));
        assertTrue(single.match(-999L));

        // 3 个分片，当前序号为 1
        ShardingContext shard1Of3 = new ShardingContext(1, 3);
        assertTrue(shard1Of3.match(1L));
        assertTrue(shard1Of3.match(4L));
        assertTrue(shard1Of3.match(7L));
        assertFalse(shard1Of3.match(0L));
        assertFalse(shard1Of3.match(2L));
        assertFalse(shard1Of3.match(3L));

        // 负数取模兼容性验证
        long negId = -2L; // Math.floorMod(-2, 3) == 1
        assertTrue(shard1Of3.match(negId));
    }

    @Test
    @DisplayName("测试字符串分片键匹配算法")
    void testMatchStringKey() {
        ShardingContext single = new ShardingContext(0, 1);
        assertTrue(single.match("any-key"));
        assertTrue(single.match((String) null));

        ShardingContext shard0 = new ShardingContext(0, 3);
        assertTrue(shard0.match((String) null)); // null 保底路由至首节点

        ShardingContext shard1 = new ShardingContext(1, 3);
        assertFalse(shard1.match((String) null));

        String key = "test-order-key";
        int expectedIndex = Math.floorMod(key.hashCode(), 3);
        ShardingContext matchingContext = new ShardingContext(expectedIndex, 3);
        assertTrue(matchingContext.match(key));

        ShardingContext otherContext = new ShardingContext((expectedIndex + 1) % 3, 3);
        assertFalse(otherContext.match(key));
    }

    @Test
    @DisplayName("测试从当前 XXL-Job 上下文提取分片")
    void testCurrentContextExtraction() {
        // 无调度上下文保底返回 (0, 1)
        XxlJobContext.setXxlJobContext(null);
        ShardingContext fallback = ShardingContext.current();
        assertEquals(0, fallback.getShardIndex());
        assertEquals(1, fallback.getShardTotal());

        try {
            // 构造真实 XXL-Job 上下文 (shardIndex=2, shardTotal=4)
            XxlJobContext context = new XxlJobContext(1001L, "param", 1L, System.currentTimeMillis(), "log/path", 2, 4);
            XxlJobContext.setXxlJobContext(context);

            ShardingContext current = ShardingContext.current();
            assertEquals(2, current.getShardIndex());
            assertEquals(4, current.getShardTotal());
            assertTrue(current.isSharded());
        } finally {
            XxlJobContext.setXxlJobContext(null);
        }
    }

    @Test
    @DisplayName("测试 equals, hashCode 与 toString")
    void testEqualsAndToString() {
        ShardingContext c1 = new ShardingContext(1, 3);
        ShardingContext c2 = new ShardingContext(1, 3);
        ShardingContext c3 = new ShardingContext(2, 3);

        assertEquals(c1, c2);
        assertEquals(c1.hashCode(), c2.hashCode());
        assertNotEquals(c1, c3);
        assertTrue(c1.toString().contains("shardIndex=1"));
        assertTrue(c1.toString().contains("shardTotal=3"));
    }
}
