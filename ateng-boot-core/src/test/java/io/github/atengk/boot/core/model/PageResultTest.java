package io.github.atengk.boot.core.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PageResult 单元测试
 *
 * @author Ateng
 * @since 2026-10-16
 */
class PageResultTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @DisplayName("验证 PageResult 构造与自动计算总页数")
    void shouldConstructPageResultAndComputePagesCorrectly() {
        List<String> records = Arrays.asList("A", "B", "C");
        PageResult<String> page = PageResult.of(records, 25L, 1L, 10L);

        assertEquals(Arrays.asList("A", "B", "C"), page.getRecords());
        assertEquals(25L, page.getTotal());
        assertEquals(1L, page.getCurrent());
        assertEquals(10L, page.getSize());
        assertEquals(3L, page.getPages());
    }

    @Test
    @DisplayName("验证边界情况总页数计算：总数为 0 或单页大小 <= 0 时总页数为 0")
    void shouldComputeZeroPagesWhenTotalOrSizeIsZero() {
        PageResult<String> pageZeroTotal = PageResult.of(Collections.emptyList(), 0L, 1L, 10L);
        assertEquals(0L, pageZeroTotal.getPages());

        PageResult<String> pageZeroSize = PageResult.of(Collections.emptyList(), 10L, 1L, 0L);
        assertEquals(0L, pageZeroSize.getPages());
    }

    @Test
    @DisplayName("空安全防御：传入 null records 时防御性降级为空列表，绝不返回 null")
    void shouldDefaultToEmptyListWhenRecordsIsNull() {
        PageResult<String> page = new PageResult<>(null, 0L, 1L, 10L);
        assertNotNull(page.getRecords());
        assertTrue(page.getRecords().isEmpty());

        page.setRecords(null);
        assertNotNull(page.getRecords());
        assertTrue(page.getRecords().isEmpty());
    }

    @Test
    @DisplayName("验证静态工厂方法 empty()")
    void shouldCreateEmptyPageResult() {
        PageResult<String> empty = PageResult.empty();
        assertTrue(empty.getRecords().isEmpty());
        assertEquals(0L, empty.getTotal());
        assertEquals(1L, empty.getCurrent());
        assertEquals(10L, empty.getSize());
        assertEquals(0L, empty.getPages());

        PageResult<String> emptyWithCustom = PageResult.empty(2L, 20L);
        assertEquals(2L, emptyWithCustom.getCurrent());
        assertEquals(20L, emptyWithCustom.getSize());
        assertEquals(0L, emptyWithCustom.getTotal());
    }

    @Test
    @DisplayName("流式映射：通过 map() 方法平滑完成实体到视图对象的转换")
    void shouldMapRecordsFluently() {
        List<Integer> numbers = Arrays.asList(1, 2, 3);
        PageResult<Integer> intPage = PageResult.of(numbers, 15L, 1L, 5L);

        PageResult<String> strPage = intPage.map(num -> "Item-" + num);

        assertEquals(Arrays.asList("Item-1", "Item-2", "Item-3"), strPage.getRecords());
        assertEquals(15L, strPage.getTotal());
        assertEquals(1L, strPage.getCurrent());
        assertEquals(5L, strPage.getSize());
        assertEquals(3L, strPage.getPages());
    }

    @Test
    @DisplayName("流式映射防御：map 传入 null 抛出异常")
    void shouldThrowExceptionWhenMapperIsNull() {
        PageResult<String> page = PageResult.of(Arrays.asList("A"), 1L, 1L, 10L);
        NullPointerException exception = assertThrows(NullPointerException.class, () -> page.map(null));
        assertTrue(exception.getMessage().contains("mapper 不能为 null"));
    }

    @Test
    @DisplayName("JSON 序列化与反序列化验证：包含且仅包含 records, total, size, current, pages 5 要素")
    void shouldSerializeAndDeserializeJsonCleanly() throws Exception {
        PageResult<String> page = PageResult.of(Arrays.asList("Alice", "Bob"), 2L, 1L, 10L, 1L);
        String json = objectMapper.writeValueAsString(page);

        assertTrue(json.contains("\"records\":[\"Alice\",\"Bob\"]"));
        assertTrue(json.contains("\"total\":2"));
        assertTrue(json.contains("\"size\":10"));
        assertTrue(json.contains("\"current\":1"));
        assertTrue(json.contains("\"pages\":1"));

        // 反序列化
        PageResult<?> deserialized = objectMapper.readValue(json, PageResult.class);
        assertEquals(2L, deserialized.getTotal());
        assertEquals(1L, deserialized.getCurrent());
        assertEquals(10L, deserialized.getSize());
        assertEquals(1L, deserialized.getPages());
        assertEquals(2, deserialized.getRecords().size());
    }
}
