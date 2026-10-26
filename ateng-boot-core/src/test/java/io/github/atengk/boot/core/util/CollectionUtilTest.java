package io.github.atengk.boot.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * CollectionUtil 单元测试
 *
 * @author Ateng
 * @since 2026-10-17
 */
@DisplayName("CollectionUtil 集合增强工具测试")
class CollectionUtilTest {

    @Test
    @DisplayName("集合与 Map 判空测试")
    void shouldTestEmptiness() {
        assertTrue(CollectionUtil.isEmpty(Collections.emptyList()));
        assertTrue(CollectionUtil.isEmpty((List<?>) null));
        assertFalse(CollectionUtil.isNotEmpty(Collections.emptyList()));
        assertTrue(CollectionUtil.isNotEmpty(List.of("a")));

        assertTrue(CollectionUtil.isEmpty(Collections.emptyMap()));
        assertTrue(CollectionUtil.isEmpty((Map<?, ?>) null));
        assertTrue(CollectionUtil.isNotEmpty(Map.of("k", "v")));
    }

    @Test
    @DisplayName("partition: 整除与不整除分批切片验证")
    void shouldPartitionListCorrectly() {
        List<Integer> list = Arrays.asList(1, 2, 3, 4, 5, 6, 7);

        // 分批大小 3 -> [1,2,3], [4,5,6], [7]
        List<List<Integer>> parts = CollectionUtil.partition(list, 3);
        assertEquals(3, parts.size());
        assertEquals(List.of(1, 2, 3), parts.get(0));
        assertEquals(List.of(4, 5, 6), parts.get(1));
        assertEquals(List.of(7), parts.get(2));

        // 整除分批 2 -> 3 组
        List<List<Integer>> evenParts = CollectionUtil.partition(Arrays.asList(1, 2, 3, 4), 2);
        assertEquals(2, evenParts.size());
        assertEquals(List.of(1, 2), evenParts.get(0));
        assertEquals(List.of(3, 4), evenParts.get(1));
    }

    @Test
    @DisplayName("partition: 空集合与非法 batchSize 安全防御")
    void shouldHandlePartitionEdgeCasesDefensively() {
        assertTrue(CollectionUtil.partition(null, 5).isEmpty());
        assertTrue(CollectionUtil.partition(Collections.emptyList(), 5).isEmpty());
        assertTrue(CollectionUtil.partition(List.of(1, 2), 0).isEmpty());
        assertTrue(CollectionUtil.partition(List.of(1, 2), -1).isEmpty());
    }

    @Test
    @DisplayName("partition: batchSize 为 Integer.MAX_VALUE 时不发生整数溢出异常")
    void shouldHandleMaxIntegerBatchSizeWithoutOverflow() {
        List<Integer> list = Arrays.asList(1, 2, 3);
        List<List<Integer>> parts = CollectionUtil.partition(list, Integer.MAX_VALUE);
        assertEquals(1, parts.size());
        assertEquals(list, parts.get(0));
    }

    @Test
    @DisplayName("emptyIfNull 与 first/last 安全提取验证")
    void shouldVerifyEmptyIfNullAndFirstLast() {
        assertNotNull(CollectionUtil.emptyIfNull((List<String>) null));
        assertTrue(CollectionUtil.emptyIfNull((List<String>) null).isEmpty());
        assertNotNull(CollectionUtil.emptyIfNull((java.util.Set<String>) null));
        assertNotNull(CollectionUtil.emptyIfNull((Map<String, String>) null));

        List<String> list = List.of("A", "B", "C");
        assertSame(list, CollectionUtil.emptyIfNull(list));
        assertEquals("A", CollectionUtil.first(list));
        assertEquals("C", CollectionUtil.last(list));

        assertNull(CollectionUtil.first(null));
        assertNull(CollectionUtil.first(Collections.emptyList()));
        assertNull(CollectionUtil.last(null));
        assertNull(CollectionUtil.last(Collections.emptyList()));
    }

    @Test
    @DisplayName("distinctByKey: 支持通过 CollectionUtil 便捷提取去重断言")
    void shouldSupportDistinctByKeyPredicate() {
        record Item(String id, String name) {}
        List<Item> items = List.of(
                new Item("1", "A"),
                new Item("1", "B"),
                new Item("2", "C")
        );
        List<Item> result = items.stream().filter(CollectionUtil.distinctByKey(Item::id)).toList();
        assertEquals(2, result.size());
        assertEquals("A", result.get(0).name());
        assertEquals("C", result.get(1).name());
    }
}
