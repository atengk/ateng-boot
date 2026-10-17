package io.github.atengk.boot.redis.util;

import com.fasterxml.jackson.core.type.TypeReference;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.GeoOperations;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.HyperLogLogOperations;
import org.springframework.data.redis.core.ListOperations;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.ZSetOperations;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * RedisUtil 全能门面与安全游标扫描单元测试
 *
 * @author Ateng
 * @since 2026-10-16
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("RedisUtil 单元测试")
class RedisUtilTest {

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    @Mock
    private HashOperations<String, Object, Object> hashOperations;

    @Mock
    private ListOperations<String, Object> listOperations;

    @Mock
    private SetOperations<String, Object> setOperations;

    @Mock
    private ZSetOperations<String, Object> zSetOperations;

    @Mock
    private GeoOperations<String, Object> geoOperations;

    @Mock
    private HyperLogLogOperations<String, Object> hyperLogLogOperations;

    private RedisUtil redisUtil;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    static class ItemVO {
        private Long id;
        private String name;
    }

    @BeforeEach
    void setUp() {
        redisUtil = new RedisUtil(redisTemplate);
    }

    @Test
    @DisplayName("生产防呆红线：验证 RedisUtil 严禁暴露裸 keys * 接口")
    void shouldStrictlyProhibitKeysMethod() {
        Method[] methods = RedisUtil.class.getDeclaredMethods();
        for (Method method : methods) {
            assertThat(method.getName())
                    .as("RedisUtil 严禁暴露任何名为 keys 的全量阻塞扫描方法")
                    .isNotEqualTo("keys");
        }
    }

    @Test
    @DisplayName("生产级游标安全扫描：验证分批 scan 契约")
    @SuppressWarnings("unchecked")
    void shouldScanKeysUsingCursorSafely() {
        Cursor<String> cursor = mock(Cursor.class);
        when(cursor.hasNext()).thenReturn(true, true, false);
        when(cursor.next()).thenReturn("order:1001", "order:1002");
        when(redisTemplate.scan(any(ScanOptions.class))).thenReturn(cursor);

        Set<String> keys = RedisUtil.scan("order:*", 500L);

        assertThat(keys).containsExactlyInAnyOrder("order:1001", "order:1002");
        verify(redisTemplate).scan(any(ScanOptions.class));
    }

    @Test
    @DisplayName("Key 基础操作：hasKey, expire, getExpire, delete")
    void shouldPerformKeyOperationsCorrectly() {
        when(redisTemplate.hasKey("k1")).thenReturn(true);
        when(redisTemplate.expire("k1", 60L, TimeUnit.SECONDS)).thenReturn(true);
        when(redisTemplate.getExpire("k1", TimeUnit.SECONDS)).thenReturn(55L);
        when(redisTemplate.delete("k1")).thenReturn(true);
        when(redisTemplate.delete(any(Collection.class))).thenReturn(3L);

        assertThat(RedisUtil.hasKey("k1")).isTrue();
        assertThat(RedisUtil.expire("k1", 60L, TimeUnit.SECONDS)).isTrue();
        assertThat(RedisUtil.getExpire("k1", TimeUnit.SECONDS)).isEqualTo(55L);
        assertThat(RedisUtil.delete("k1")).isTrue();
        assertThat(RedisUtil.delete(List.of("k1", "k2", "k3"))).isEqualTo(3L);
    }

    @Test
    @DisplayName("String (Value) 操作与类型安全转换")
    void shouldPerformValueOperationsAndTypeConversion() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        RedisUtil.set("str:1", "hello");
        verify(valueOperations).set("str:1", "hello");

        RedisUtil.set("str:1", "hello", 10, TimeUnit.MINUTES);
        verify(valueOperations).set("str:1", "hello", 10, TimeUnit.MINUTES);

        when(valueOperations.setIfAbsent("lock:1", "val")).thenReturn(true);
        assertThat(RedisUtil.setIfAbsent("lock:1", "val")).isTrue();

        when(valueOperations.increment("seq:1", 5L)).thenReturn(105L);
        assertThat(RedisUtil.incr("seq:1", 5L)).isEqualTo(105L);

        when(valueOperations.decrement("seq:1", 2L)).thenReturn(103L);
        assertThat(RedisUtil.decr("seq:1", 2L)).isEqualTo(103L);

        // 模拟底层 Jackson 反序列化返回 LinkedHashMap 结构，验证类型自适应转换
        Map<String, Object> rawMap = new HashMap<>();
        rawMap.put("id", "8888");
        rawMap.put("name", "Laptop");
        when(valueOperations.get("item:1")).thenReturn(rawMap);

        ItemVO item = RedisUtil.get("item:1", ItemVO.class);
        assertThat(item).isNotNull();
        assertThat(item.getId()).isEqualTo(8888L);
        assertThat(item.getName()).isEqualTo("Laptop");

        // 验证 TypeReference 复杂泛型支持
        List<Map<String, Object>> listMap = List.of(rawMap);
        when(valueOperations.get("item:list")).thenReturn(listMap);

        List<ItemVO> items = RedisUtil.get("item:list", new TypeReference<List<ItemVO>>() {});
        assertThat(items).hasSize(1);
        assertThat(items.get(0).getId()).isEqualTo(8888L);
    }

    @Test
    @DisplayName("Hash 操作契约验证")
    void shouldPerformHashOperations() {
        when(redisTemplate.opsForHash()).thenReturn(hashOperations);

        RedisUtil.hSet("user:10", "name", "Bob");
        verify(hashOperations).put("user:10", "name", "Bob");

        when(hashOperations.get("user:10", "name")).thenReturn("Bob");
        assertThat(RedisUtil.hGet("user:10", "name")).isEqualTo("Bob");

        Map<Object, Object> map = Map.of("k1", "v1");
        when(hashOperations.entries("user:10")).thenReturn(map);
        assertThat(RedisUtil.hGetAll("user:10")).isEqualTo(map);

        when(hashOperations.delete("user:10", "name")).thenReturn(1L);
        assertThat(RedisUtil.hDel("user:10", "name")).isEqualTo(1L);

        when(hashOperations.hasKey("user:10", "name")).thenReturn(true);
        assertThat(RedisUtil.hHasKey("user:10", "name")).isTrue();

        when(hashOperations.increment("user:10", "score", 10L)).thenReturn(100L);
        assertThat(RedisUtil.hIncr("user:10", "score", 10L)).isEqualTo(100L);
    }

    @Test
    @DisplayName("List 操作契约验证")
    void shouldPerformListOperations() {
        when(redisTemplate.opsForList()).thenReturn(listOperations);

        when(listOperations.leftPush("list:1", "a")).thenReturn(1L);
        assertThat(RedisUtil.lPush("list:1", "a")).isEqualTo(1L);

        when(listOperations.rightPush("list:1", "b")).thenReturn(2L);
        assertThat(RedisUtil.rPush("list:1", "b")).isEqualTo(2L);

        when(listOperations.leftPop("list:1")).thenReturn("a");
        assertThat(RedisUtil.lPop("list:1")).isEqualTo("a");

        when(listOperations.rightPop("list:1")).thenReturn("b");
        assertThat(RedisUtil.rPop("list:1")).isEqualTo("b");

        when(listOperations.range("list:1", 0, -1)).thenReturn(List.of("x", "y"));
        assertThat(RedisUtil.lRange("list:1", 0, -1)).containsExactly("x", "y");

        when(listOperations.size("list:1")).thenReturn(5L);
        assertThat(RedisUtil.lLen("list:1")).isEqualTo(5L);
    }

    @Test
    @DisplayName("Set 操作契约验证")
    void shouldPerformSetOperations() {
        when(redisTemplate.opsForSet()).thenReturn(setOperations);

        when(setOperations.add("set:1", "s1", "s2")).thenReturn(2L);
        assertThat(RedisUtil.sAdd("set:1", "s1", "s2")).isEqualTo(2L);

        when(setOperations.members("set:1")).thenReturn(new HashSet<>(Arrays.asList("s1", "s2")));
        assertThat(RedisUtil.sMembers("set:1")).containsExactlyInAnyOrder("s1", "s2");

        when(setOperations.isMember("set:1", "s1")).thenReturn(true);
        assertThat(RedisUtil.sIsMember("set:1", "s1")).isTrue();

        when(setOperations.remove("set:1", "s1")).thenReturn(1L);
        assertThat(RedisUtil.sRem("set:1", "s1")).isEqualTo(1L);

        when(setOperations.size("set:1")).thenReturn(1L);
        assertThat(RedisUtil.sCard("set:1")).isEqualTo(1L);
    }

    @Test
    @DisplayName("ZSet 操作契约验证")
    void shouldPerformZSetOperations() {
        when(redisTemplate.opsForZSet()).thenReturn(zSetOperations);

        when(zSetOperations.add("zset:1", "player1", 100.0)).thenReturn(true);
        assertThat(RedisUtil.zAdd("zset:1", "player1", 100.0)).isTrue();

        when(zSetOperations.score("zset:1", "player1")).thenReturn(100.0);
        assertThat(RedisUtil.zScore("zset:1", "player1")).isEqualTo(100.0);

        when(zSetOperations.rank("zset:1", "player1")).thenReturn(0L);
        assertThat(RedisUtil.zRank("zset:1", "player1")).isEqualTo(0L);

        when(zSetOperations.reverseRank("zset:1", "player1")).thenReturn(10L);
        assertThat(RedisUtil.zRevRank("zset:1", "player1")).isEqualTo(10L);

        when(zSetOperations.remove("zset:1", "player1")).thenReturn(1L);
        assertThat(RedisUtil.zRem("zset:1", "player1")).isEqualTo(1L);

        when(zSetOperations.zCard("zset:1")).thenReturn(50L);
        assertThat(RedisUtil.zCard("zset:1")).isEqualTo(50L);
    }

    @Test
    @DisplayName("Geo 空间操作契约验证")
    void shouldPerformGeoOperations() {
        when(redisTemplate.opsForGeo()).thenReturn(geoOperations);

        Point point = new Point(116.403963, 39.915119);
        when(geoOperations.add("geo:beijing", point, "tiananmen")).thenReturn(1L);
        assertThat(RedisUtil.geoAdd("geo:beijing", point, "tiananmen")).isEqualTo(1L);

        Distance distance = new Distance(15.5, Metrics.KILOMETERS);
        when(geoOperations.distance("geo:beijing", "p1", "p2", Metrics.KILOMETERS)).thenReturn(distance);
        assertThat(RedisUtil.geoDist("geo:beijing", "p1", "p2", Metrics.KILOMETERS)).isEqualTo(distance);
    }

    @Test
    @DisplayName("BitMap 位图操作契约验证")
    void shouldPerformBitMapOperations() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        when(valueOperations.setBit("bm:user:1", 5L, true)).thenReturn(false);
        assertThat(RedisUtil.setBit("bm:user:1", 5L, true)).isFalse();

        when(valueOperations.getBit("bm:user:1", 5L)).thenReturn(true);
        assertThat(RedisUtil.getBit("bm:user:1", 5L)).isTrue();
    }

    @Test
    @DisplayName("HyperLogLog 基数操作契约验证")
    void shouldPerformHyperLogLogOperations() {
        when(redisTemplate.opsForHyperLogLog()).thenReturn(hyperLogLogOperations);

        when(hyperLogLogOperations.add("hll:uv", "ip1", "ip2")).thenReturn(1L);
        assertThat(RedisUtil.pfAdd("hll:uv", "ip1", "ip2")).isEqualTo(1L);

        when(hyperLogLogOperations.size("hll:uv")).thenReturn(10000L);
        assertThat(RedisUtil.pfCount("hll:uv")).isEqualTo(10000L);
    }

    @Test
    @DisplayName("Pipeline 批量流水线操作契约验证")
    void shouldExecutePipelineSuccessfully() {
        when(redisTemplate.executePipelined(any(SessionCallback.class))).thenReturn(List.of("OK", 1L));

        List<Object> results = RedisUtil.executePipelined(ops -> {
            ops.opsForValue().set("k1", "v1");
            ops.opsForValue().increment("k2");
        });

        assertThat(results).containsExactly("OK", 1L);
    }

    @Test
    @DisplayName("Pipeline 批量流水线操作契约验证：底座返回 null 时保底返回空集合")
    void shouldReturnEmptyListWhenPipelinedReturnsNull() {
        when(redisTemplate.executePipelined(any(SessionCallback.class))).thenReturn(null);

        List<Object> results = RedisUtil.executePipelined(ops -> {
            ops.opsForValue().set("k1", "v1");
        });

        assertThat(results).isNotNull().isEmpty();
    }

    @Test
    @DisplayName("实例方法全量对齐与 Spring 注入双重范式验证")
    void shouldSupportInstanceMethodDelegation() {
        when(redisTemplate.hasKey("inst:k")).thenReturn(true);
        assertThat(redisUtil.hasKey("inst:k")).isTrue();
        assertThat(redisUtil.isKeyExists("inst:k")).isTrue();

        when(redisTemplate.expire("inst:k", 10, TimeUnit.SECONDS)).thenReturn(true);
        assertThat(redisUtil.expire("inst:k", 10, TimeUnit.SECONDS)).isTrue();
        assertThat(redisUtil.setKeyExpire("inst:k", 10, TimeUnit.SECONDS)).isTrue();

        when(redisTemplate.getExpire("inst:k", TimeUnit.SECONDS)).thenReturn(10L);
        assertThat(redisUtil.getExpire("inst:k", TimeUnit.SECONDS)).isEqualTo(10L);
        assertThat(redisUtil.getKeyExpire("inst:k", TimeUnit.SECONDS)).isEqualTo(10L);

        when(redisTemplate.delete("inst:k")).thenReturn(true);
        assertThat(redisUtil.delete("inst:k")).isTrue();
        assertThat(redisUtil.deleteKey("inst:k")).isTrue();

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get("inst:k")).thenReturn("inst_val");
        assertThat(redisUtil.get("inst:k")).isEqualTo("inst_val");
    }
}
