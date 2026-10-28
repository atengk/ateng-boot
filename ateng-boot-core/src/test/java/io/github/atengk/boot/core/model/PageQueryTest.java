package io.github.atengk.boot.core.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * PageQuery 单元测试
 *
 * @author Ateng
 * @since 2026-10-16
 */
class PageQueryTest {

    @Test
    @DisplayName("默认值校验：默认页码为 1，默认页大小为 10")
    void shouldHaveSensibleDefaults() {
        PageQuery query = new PageQuery();
        assertEquals(1L, query.getCurrent());
        assertEquals(10L, query.getSize());
    }

    @Test
    @DisplayName("自定义参数构造与 Getter/Setter 校验")
    void shouldConstructWithCustomValues() {
        PageQuery query = new PageQuery(2L, 50L);
        assertEquals(2L, query.getCurrent());
        assertEquals(50L, query.getSize());

        query.setCurrent(3L);
        query.setSize(20L);
        assertEquals(3L, query.getCurrent());
        assertEquals(20L, query.getSize());
    }

    @Test
    @DisplayName("JSR-303 格式校验：current 与 size 边界值防护")
    void shouldValidatePageQueryBounds() {
        io.github.atengk.boot.core.util.ValidatorUtil.validate(new PageQuery(1L, 10L));

        io.github.atengk.boot.core.exception.BusinessException ex1 = assertThrows(
                io.github.atengk.boot.core.exception.BusinessException.class,
                () -> io.github.atengk.boot.core.util.ValidatorUtil.validate(new PageQuery(0L, 10L))
        );
        assertEquals("当前页码必须大于或等于 1", ex1.getMessage());

        io.github.atengk.boot.core.exception.BusinessException ex2 = assertThrows(
                io.github.atengk.boot.core.exception.BusinessException.class,
                () -> io.github.atengk.boot.core.util.ValidatorUtil.validate(new PageQuery(1L, 0L))
        );
        assertEquals("单页条数必须大于或等于 1", ex2.getMessage());

        io.github.atengk.boot.core.exception.BusinessException ex3 = assertThrows(
                io.github.atengk.boot.core.exception.BusinessException.class,
                () -> io.github.atengk.boot.core.util.ValidatorUtil.validate(new PageQuery(1L, 1001L))
        );
        assertEquals("单页条数最大不能超过 1000", ex3.getMessage());
    }

    @Test
    @DisplayName("排序项初始化与保底非空防御：sorts 默认为空列表且 setSorts(null) 保底")
    void shouldHandleSortsNullSafety() {
        PageQuery query = new PageQuery();
        assertNotNull(query.getSorts());
        assertTrue(query.getSorts().isEmpty());

        query.setSorts(null);
        assertNotNull(query.getSorts());
        assertTrue(query.getSorts().isEmpty());
    }

    @Test
    @DisplayName("流畅链式添加排序方法：addSort, addAsc, addDesc, clearSorts")
    void shouldSupportFluentSortMethods() {
        PageQuery query = new PageQuery(1L, 20L)
                .addAsc("createTime")
                .addDesc("id")
                .addSort("status", true)
                .addSort("level", SortItem.Direction.DESC)
                .addSort(SortItem.asc("score"));

        assertEquals(5, query.getSorts().size());
        assertEquals("createTime", query.getSorts().get(0).getProperty());
        assertTrue(query.getSorts().get(0).isAsc());

        assertEquals("id", query.getSorts().get(1).getProperty());
        assertFalse(query.getSorts().get(1).isAsc());

        assertEquals("status", query.getSorts().get(2).getProperty());
        assertTrue(query.getSorts().get(2).isAsc());

        assertEquals("level", query.getSorts().get(3).getProperty());
        assertFalse(query.getSorts().get(3).isAsc());

        assertEquals("score", query.getSorts().get(4).getProperty());
        assertTrue(query.getSorts().get(4).isAsc());

        // 清空测试
        query.clearSorts();
        assertTrue(query.getSorts().isEmpty());
    }

    @Test
    @DisplayName("JSR-303 级联校验：PageQuery 中的 SortItem 非法字段阻断")
    void shouldCascadeValidateSortItems() {
        PageQuery query = new PageQuery();
        SortItem invalidItem = new SortItem(); // property 为 null
        query.getSorts().add(invalidItem);

        io.github.atengk.boot.core.exception.BusinessException ex = assertThrows(
                io.github.atengk.boot.core.exception.BusinessException.class,
                () -> io.github.atengk.boot.core.util.ValidatorUtil.validate(query)
        );
        assertTrue(ex.getMessage().contains("排序属性名称不能为空"));
    }
}
