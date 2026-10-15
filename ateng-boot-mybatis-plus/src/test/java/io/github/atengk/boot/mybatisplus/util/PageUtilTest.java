package io.github.atengk.boot.mybatisplus.util;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import io.github.atengk.boot.core.model.PageQuery;
import io.github.atengk.boot.core.model.PageResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PageUtil 单元测试
 *
 * @author Ateng
 * @since 2026-10-16
 */
class PageUtilTest {

    @Test
    @DisplayName("toPage: 正常入参转换")
    void shouldConvertPageQueryToPageCorrectly() {
        PageQuery query = new PageQuery(2L, 20L);
        Page<Object> page = PageUtil.toPage(query);

        assertEquals(2L, page.getCurrent());
        assertEquals(20L, page.getSize());
    }

    @Test
    @DisplayName("toPage: null 入参防御降级为第 1 页每页 10 条")
    void shouldDefaultPageWhenQueryIsNull() {
        Page<Object> page = PageUtil.toPage(null);

        assertEquals(1L, page.getCurrent());
        assertEquals(10L, page.getSize());
    }

    @Test
    @DisplayName("toPage: 非正整数参数防御性修正为默认第 1 页每页 10 条")
    void shouldNormalizeInvalidPageParameters() {
        PageQuery invalidQuery = new PageQuery(-5L, 0L);
        Page<Object> page = PageUtil.toPage(invalidQuery);

        assertEquals(1L, page.getCurrent());
        assertEquals(10L, page.getSize());
    }

    @Test
    @DisplayName("toPage: 带 searchCount 参数转换")
    void shouldConvertPageQueryWithSearchCount() {
        PageQuery query = new PageQuery(3L, 15L);
        Page<Object> page = PageUtil.toPage(query, false);

        assertEquals(3L, page.getCurrent());
        assertEquals(15L, page.getSize());
        assertFalse(page.searchCount());

        Page<Object> nullQueryPage = PageUtil.toPage(null, true);
        assertEquals(1L, nullQueryPage.getCurrent());
        assertEquals(10L, nullQueryPage.getSize());
        assertTrue(nullQueryPage.searchCount());
    }

    @Test
    @DisplayName("toPageResult: 将 IPage 转换为标准 PageResult")
    void shouldConvertIPageToPageResult() {
        Page<String> mpPage = new Page<>(1L, 10L, 25L);
        mpPage.setRecords(Arrays.asList("A", "B", "C"));

        PageResult<String> result = PageUtil.toPageResult(mpPage);

        assertEquals(Arrays.asList("A", "B", "C"), result.getRecords());
        assertEquals(25L, result.getTotal());
        assertEquals(1L, result.getCurrent());
        assertEquals(10L, result.getSize());
        assertEquals(3L, result.getPages());
    }

    @Test
    @DisplayName("toPageResult: null 入参防御性返回 empty PageResult")
    void shouldReturnEmptyPageResultWhenPageIsNull() {
        PageResult<String> result = PageUtil.toPageResult((IPage<String>) null);

        assertNotNull(result);
        assertTrue(result.getRecords().isEmpty());
        assertEquals(0L, result.getTotal());
    }

    @Test
    @DisplayName("toPageResult 带映射转换：将 IPage 实体流式转换为 VO")
    void shouldConvertIPageAndMapToVO() {
        Page<Integer> mpPage = new Page<>(2L, 5L, 12L);
        mpPage.setRecords(Arrays.asList(10, 20));

        PageResult<String> result = PageUtil.toPageResult(mpPage, num -> "Code-" + num);

        assertEquals(Arrays.asList("Code-10", "Code-20"), result.getRecords());
        assertEquals(12L, result.getTotal());
        assertEquals(2L, result.getCurrent());
        assertEquals(5L, result.getSize());
        assertEquals(3L, result.getPages());
    }

    @Test
    @DisplayName("toPageResult 带映射转换：null 页面防御返回 empty，null 转换器防御报错")
    void shouldHandleDefensiveNullsWithMapper() {
        PageResult<String> empty = PageUtil.toPageResult(null, num -> "val");
        assertNotNull(empty);
        assertTrue(empty.getRecords().isEmpty());

        Page<String> mpPage = new Page<>(1, 10);
        assertThrows(NullPointerException.class, () -> PageUtil.toPageResult(mpPage, null));
    }
}
