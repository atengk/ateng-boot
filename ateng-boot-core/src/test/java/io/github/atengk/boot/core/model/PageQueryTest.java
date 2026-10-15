package io.github.atengk.boot.core.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
