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
}
