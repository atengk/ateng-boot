package io.github.atengk.boot.search;

import io.github.atengk.boot.search.annotation.SearchId;
import io.github.atengk.boot.search.annotation.SearchIndex;
import io.github.atengk.boot.search.annotation.SearchRouting;
import io.github.atengk.boot.search.util.SearchMetadataHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SearchMetadataHelper 单元测试
 *
 * @author Ateng
 * @since 2026-10-25
 */
class SearchMetadataHelperTest {

    @SearchIndex("custom_idx")
    static class StaticIndexEntity {
        @SearchId
        private String docId = "id-123";

        @SearchRouting
        private String route = "route-abc";
    }

    @SearchIndex("log_#{T(java.time.LocalDate).now().format('yyyyMM')}")
    static class SpelIndexEntity {
    }

    @SearchIndex("invalid_#{1 / 0}")
    static class InvalidSpelIndexEntity {
    }

    static class DefaultEntity {
        private Long id = 999L;
    }

    static class NoIdEntity {
        private String name = "test";
    }

    @Test
    @DisplayName("测试静态索引名称解析")
    void testResolveStaticIndexName() {
        String index = SearchMetadataHelper.resolveIndexName(StaticIndexEntity.class);
        assertThat(index).isEqualTo("custom_idx");
    }

    @Test
    @DisplayName("测试动态 SpEL 索引名称解析")
    void testResolveSpelIndexName() {
        String index = SearchMetadataHelper.resolveIndexName(SpelIndexEntity.class);
        String expected = "log_" + LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
        assertThat(index).isEqualTo(expected);
    }

    @Test
    @DisplayName("测试异常 SpEL 表达式优雅降级与日志告警")
    void testResolveInvalidSpelIndexNameFallback() {
        String rawExpr = "invalid_#{1 / 0}";
        String index = SearchMetadataHelper.resolveIndexName(InvalidSpelIndexEntity.class);
        assertThat(index).isEqualTo(rawExpr);
    }

    @Test
    @DisplayName("测试默认类名转蛇形索引名称解析")
    void testResolveDefaultSnakeCaseIndexName() {
        String index = SearchMetadataHelper.resolveIndexName(DefaultEntity.class);
        assertThat(index).isEqualTo("default_entity");
    }

    @Test
    @DisplayName("测试解析文档 ID 与分片路由键")
    void testResolveDocumentIdAndRouting() {
        StaticIndexEntity entity = new StaticIndexEntity();
        assertThat(SearchMetadataHelper.resolveDocumentId(entity)).isEqualTo("id-123");
        assertThat(SearchMetadataHelper.resolveRouting(entity)).isEqualTo("route-abc");
        assertThat(SearchMetadataHelper.hasRoutingAnnotation(StaticIndexEntity.class)).isTrue();
    }

    @Test
    @DisplayName("测试兜底 id 字段解析")
    void testResolveFallbackId() {
        DefaultEntity entity = new DefaultEntity();
        assertThat(SearchMetadataHelper.resolveDocumentId(entity)).isEqualTo("999");
        assertThat(SearchMetadataHelper.hasRoutingAnnotation(DefaultEntity.class)).isFalse();
        assertThat(SearchMetadataHelper.resolveRouting(entity)).isNull();
    }

    @Test
    @DisplayName("测试无 ID 字段解析返回 null")
    void testResolveNoId() {
        NoIdEntity entity = new NoIdEntity();
        assertThat(SearchMetadataHelper.resolveDocumentId(entity)).isNull();
    }

    @Test
    @DisplayName("测试空入参防御安全返回")
    void testNullSafety() {
        assertThat(SearchMetadataHelper.resolveIndexName(null)).isEmpty();
        assertThat(SearchMetadataHelper.resolveDocumentId(null)).isNull();
        assertThat(SearchMetadataHelper.resolveRouting(null)).isNull();
        assertThat(SearchMetadataHelper.hasRoutingAnnotation(null)).isFalse();
    }
}
