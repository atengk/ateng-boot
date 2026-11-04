package io.github.atengk.boot.milvus;

import io.github.atengk.boot.milvus.util.MilvusExprHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MilvusExprHelper 表达式构造辅助器单测
 *
 * @author Ateng
 * @since 2026-11-04
 */
class MilvusExprHelperTest {

    @Test
    @DisplayName("textMatch - Milvus 2.4+ 标准 BM25 全文分词检索语法与引号转义")
    void testTextMatch() {
        String expr = MilvusExprHelper.textMatch("content", "machine learning");
        assertThat(expr).isEqualTo("TEXT_MATCH(content, 'machine learning')");

        // 带单引号转义
        String exprWithQuote = MilvusExprHelper.textMatch("title", "Tom's book");
        assertThat(exprWithQuote).isEqualTo("TEXT_MATCH(title, 'Tom\\'s book')");
    }

    @Test
    @DisplayName("比较操作符 - eq, ne, gt, gte, lt, lte, like")
    void testComparisonOperators() {
        assertThat(MilvusExprHelper.eq("status", 1)).isEqualTo("status == 1");
        assertThat(MilvusExprHelper.eq("is_active", true)).isEqualTo("is_active == true");
        assertThat(MilvusExprHelper.eq("author", "Ateng")).isEqualTo("author == 'Ateng'");

        assertThat(MilvusExprHelper.ne("deleted", 0)).isEqualTo("deleted != 0");
        assertThat(MilvusExprHelper.gt("age", 18)).isEqualTo("age > 18");
        assertThat(MilvusExprHelper.gte("score", 90.5)).isEqualTo("score >= 90.5");
        assertThat(MilvusExprHelper.lt("price", 100)).isEqualTo("price < 100");
        assertThat(MilvusExprHelper.lte("level", 5)).isEqualTo("level <= 5");

        assertThat(MilvusExprHelper.like("code", "MILVUS_%")).isEqualTo("code like 'MILVUS_%'");
    }

    @Test
    @DisplayName("集合操作符 - in, notIn")
    void testCollectionOperators() {
        String inExpr = MilvusExprHelper.in("category", List.of("news", "tech", "finance"));
        assertThat(inExpr).isEqualTo("category in ['news', 'tech', 'finance']");

        String inNumbers = MilvusExprHelper.in("id", List.of(1, 2, 3));
        assertThat(inNumbers).isEqualTo("id in [1, 2, 3]");

        String notInExpr = MilvusExprHelper.notIn("tag", List.of("draft", "archived"));
        assertThat(notInExpr).isEqualTo("tag not in ['draft', 'archived']");
    }

    @Test
    @DisplayName("逻辑组合 - and, or, not")
    void testLogicalCombinations() {
        String e1 = "status == 1";
        String e2 = "category == 'ai'";
        String e3 = "views > 100";

        assertThat(MilvusExprHelper.and(e1, e2, e3)).isEqualTo("(status == 1) and (category == 'ai') and (views > 100)");
        assertThat(MilvusExprHelper.or(e1, e2)).isEqualTo("(status == 1) or (category == 'ai')");
        assertThat(MilvusExprHelper.not(e1)).isEqualTo("not (status == 1)");

        // 单个表达式无需外层括号
        assertThat(MilvusExprHelper.and(e1)).isEqualTo("status == 1");
        assertThat(MilvusExprHelper.or(e1)).isEqualTo("status == 1");
    }

    @Test
    @DisplayName("Fluent Builder - 链式构造复杂组合表达式")
    void testFluentBuilder() {
        String expr = MilvusExprHelper.builder()
                .textMatch("content", "大模型 RAG")
                .gte("score", 0.8)
                .in("category", List.of("tech", "nlp"))
                .build();

        assertThat(expr).isEqualTo("(TEXT_MATCH(content, '大模型 RAG')) and (score >= 0.8) and (category in ['tech', 'nlp'])");

        String orExpr = MilvusExprHelper.builder()
                .useOr()
                .eq("tenant_id", "A")
                .eq("tenant_id", "B")
                .build();
        assertThat(orExpr).isEqualTo("(tenant_id == 'A') or (tenant_id == 'B')");
    }
}
