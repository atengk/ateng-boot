package io.github.atengk.boot.milvus;

import dev.langchain4j.store.embedding.filter.Filter;
import dev.langchain4j.store.embedding.filter.comparison.IsEqualTo;
import dev.langchain4j.store.embedding.filter.comparison.IsGreaterThan;
import dev.langchain4j.store.embedding.filter.comparison.IsGreaterThanOrEqualTo;
import dev.langchain4j.store.embedding.filter.comparison.IsIn;
import dev.langchain4j.store.embedding.filter.comparison.IsLessThan;
import dev.langchain4j.store.embedding.filter.comparison.IsLessThanOrEqualTo;
import dev.langchain4j.store.embedding.filter.comparison.IsNotEqualTo;
import dev.langchain4j.store.embedding.filter.comparison.IsNotIn;
import dev.langchain4j.store.embedding.filter.logical.And;
import dev.langchain4j.store.embedding.filter.logical.Not;
import dev.langchain4j.store.embedding.filter.logical.Or;
import io.github.atengk.boot.milvus.langchain4j.MilvusFilterMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MilvusFilterMapper 单元测试
 *
 * @author Ateng
 * @since 2026-11-04
 */
class MilvusFilterMapperTest {

    @Test
    @DisplayName("map - 比较过滤器映射 (IsEqualTo, IsNotEqualTo, IsGreaterThan 等)")
    void testComparisonFilters() {
        assertThat(MilvusFilterMapper.map(new IsEqualTo("status", 1))).isEqualTo("status == 1");
        assertThat(MilvusFilterMapper.map(new IsNotEqualTo("author", "Tom"))).isEqualTo("author != 'Tom'");
        assertThat(MilvusFilterMapper.map(new IsGreaterThan("score", 90))).isEqualTo("score > 90");
        assertThat(MilvusFilterMapper.map(new IsGreaterThanOrEqualTo("age", 18))).isEqualTo("age >= 18");
        assertThat(MilvusFilterMapper.map(new IsLessThan("price", 100))).isEqualTo("price < 100");
        assertThat(MilvusFilterMapper.map(new IsLessThanOrEqualTo("level", 5))).isEqualTo("level <= 5");

        assertThat(MilvusFilterMapper.map(new IsIn("category", List.of("news", "tech"))))
                .isEqualTo("category in ['news', 'tech']");
        assertThat(MilvusFilterMapper.map(new IsNotIn("tag", List.of("draft"))))
                .isEqualTo("tag not in ['draft']");
    }

    @Test
    @DisplayName("map - 逻辑过滤器映射 (And, Or, Not)")
    void testLogicalFilters() {
        Filter f1 = new IsEqualTo("tenant_id", "t_1");
        Filter f2 = new IsGreaterThan("views", 50);

        assertThat(MilvusFilterMapper.map(new And(f1, f2)))
                .isEqualTo("(tenant_id == 't_1') and (views > 50)");

        assertThat(MilvusFilterMapper.map(new Or(f1, f2)))
                .isEqualTo("(tenant_id == 't_1') or (views > 50)");

        assertThat(MilvusFilterMapper.map(new Not(f1)))
                .isEqualTo("not (tenant_id == 't_1')");
    }

    @Test
    @DisplayName("map - 空值防御")
    void testNullFilter() {
        assertThat(MilvusFilterMapper.map(null)).isNull();
    }
}
