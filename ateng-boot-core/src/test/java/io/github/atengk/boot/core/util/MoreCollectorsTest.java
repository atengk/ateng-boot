package io.github.atengk.boot.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * MoreCollectors 单元测试
 *
 * @author Ateng
 * @since 2026-10-17
 */
@DisplayName("MoreCollectors 安全收集器测试")
class MoreCollectorsTest {

    record Item(String key, String value) {
    }

    @Test
    @DisplayName("toSafeMap: 支持 Value 为 null 且不抛 NPE")
    void shouldSupportNullValueWithoutNpe() {
        List<Item> items = Arrays.asList(
                new Item("k1", "v1"),
                new Item("k2", null),
                new Item("k3", "v3")
        );

        Map<String, String> map = items.stream()
                .collect(MoreCollectors.toSafeMap(Item::key, Item::value));

        assertEquals(3, map.size());
        assertEquals("v1", map.get("k1"));
        assertNull(map.get("k2"));
        assertTrue(map.containsKey("k2"));
        assertEquals("v3", map.get("k3"));
    }

    @Test
    @DisplayName("toSafeMap: 默认后置覆盖重复键")
    void shouldOverrideDuplicateKeysByDefault() {
        List<Item> items = Arrays.asList(
                new Item("k1", "first"),
                new Item("k1", "latest")
        );

        Map<String, String> map = items.stream()
                .collect(MoreCollectors.toSafeMap(Item::key, Item::value));

        assertEquals(1, map.size());
        assertEquals("latest", map.get("k1"));
    }

    @Test
    @DisplayName("toSafeMap: 支持自定义重复键合并函数")
    void shouldSupportCustomMergeFunction() {
        List<Item> items = Arrays.asList(
                new Item("count", "1"),
                new Item("count", "2")
        );

        Map<String, String> map = items.stream()
                .collect(MoreCollectors.toSafeMap(
                        Item::key,
                        Item::value,
                        (oldVal, newVal) -> oldVal + "+" + newVal
                ));

        assertEquals("1+2", map.get("count"));
    }
}
