package io.github.atengk.boot.milvus.langchain4j;

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
import io.github.atengk.boot.milvus.util.MilvusExprHelper;
import org.springframework.util.StringUtils;

/**
 * LangChain4j Filter 到 Milvus 标量过滤表达式转换器
 *
 * @author Ateng
 * @since 2026-11-04
 */
public final class MilvusFilterMapper {

    private MilvusFilterMapper() {
    }

    /**
     * 将 LangChain4j Filter 递归解析映射为 Milvus 表达式
     *
     * @param filter LangChain4j 过滤条件
     * @return Milvus 标量过滤表达式；若 filter 为 null 则返回 null
     */
    public static String map(Filter filter) {
        if (filter == null) {
            return null;
        }

        if (filter instanceof IsEqualTo f) {
            return MilvusExprHelper.eq(f.key(), f.comparisonValue());
        }
        if (filter instanceof IsNotEqualTo f) {
            return MilvusExprHelper.ne(f.key(), f.comparisonValue());
        }
        if (filter instanceof IsGreaterThan f) {
            return MilvusExprHelper.gt(f.key(), f.comparisonValue());
        }
        if (filter instanceof IsGreaterThanOrEqualTo f) {
            return MilvusExprHelper.gte(f.key(), f.comparisonValue());
        }
        if (filter instanceof IsLessThan f) {
            return MilvusExprHelper.lt(f.key(), f.comparisonValue());
        }
        if (filter instanceof IsLessThanOrEqualTo f) {
            return MilvusExprHelper.lte(f.key(), f.comparisonValue());
        }
        if (filter instanceof IsIn f) {
            return MilvusExprHelper.in(f.key(), f.comparisonValues());
        }
        if (filter instanceof IsNotIn f) {
            return MilvusExprHelper.notIn(f.key(), f.comparisonValues());
        }
        if (filter instanceof And f) {
            String left = map(f.left());
            String right = map(f.right());
            return MilvusExprHelper.and(left, right);
        }
        if (filter instanceof Or f) {
            String left = map(f.left());
            String right = map(f.right());
            return MilvusExprHelper.or(left, right);
        }
        if (filter instanceof Not f) {
            String expr = map(f.expression());
            return StringUtils.hasText(expr) ? MilvusExprHelper.not(expr) : "";
        }

        return null;
    }
}
