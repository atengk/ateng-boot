package io.github.atengk.boot.milvus.util;

import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Milvus 标量过滤与全文分词检索表达式构造辅助器
 * 支持 Milvus 2.4+ 标准 TEXT_MATCH 全文语法、安全引号转义与 Fluent 链式构造
 *
 * @author Ateng
 * @since 2026-11-04
 */
public final class MilvusExprHelper {

    private MilvusExprHelper() {
    }

    /**
     * 构建 Milvus 2.4+ 标准 BM25 全文分词检索表达式
     * 语法示例: TEXT_MATCH(content, 'machine learning')
     *
     * @param field     全文文本字段名
     * @param queryText 检索查询词文本
     * @return 格式化后的 TEXT_MATCH 表达式
     */
    public static String textMatch(String field, String queryText) {
        Assert.hasText(field, "field must not be empty");
        Assert.hasText(queryText, "queryText must not be empty");
        return String.format("TEXT_MATCH(%s, '%s')", field.trim(), escape(queryText.trim()));
    }

    /**
     * 等于表达式 (field == value)
     *
     * @param field 字段名
     * @param value 目标值
     * @return 表达式
     */
    public static String eq(String field, Object value) {
        Assert.hasText(field, "field must not be empty");
        return String.format("%s == %s", field.trim(), formatValue(value));
    }

    /**
     * 不等于表达式 (field != value)
     *
     * @param field 字段名
     * @param value 目标值
     * @return 表达式
     */
    public static String ne(String field, Object value) {
        Assert.hasText(field, "field must not be empty");
        return String.format("%s != %s", field.trim(), formatValue(value));
    }

    /**
     * 大于表达式 (field > value)
     *
     * @param field 字段名
     * @param value 目标值
     * @return 表达式
     */
    public static String gt(String field, Object value) {
        Assert.hasText(field, "field must not be empty");
        return String.format("%s > %s", field.trim(), formatValue(value));
    }

    /**
     * 大于等于表达式 (field >= value)
     *
     * @param field 字段名
     * @param value 目标值
     * @return 表达式
     */
    public static String gte(String field, Object value) {
        Assert.hasText(field, "field must not be empty");
        return String.format("%s >= %s", field.trim(), formatValue(value));
    }

    /**
     * 小于表达式 (field < value)
     *
     * @param field 字段名
     * @param value 目标值
     * @return 表达式
     */
    public static String lt(String field, Object value) {
        Assert.hasText(field, "field must not be empty");
        return String.format("%s < %s", field.trim(), formatValue(value));
    }

    /**
     * 小于等于表达式 (field <= value)
     *
     * @param field 字段名
     * @param value 目标值
     * @return 表达式
     */
    public static String lte(String field, Object value) {
        Assert.hasText(field, "field must not be empty");
        return String.format("%s <= %s", field.trim(), formatValue(value));
    }

    /**
     * IN 范围表达式 (field in [val1, val2, ...])
     *
     * @param field  字段名
     * @param values 候选值集合
     * @return 表达式
     */
    public static String in(String field, Collection<?> values) {
        Assert.hasText(field, "field must not be empty");
        Assert.notEmpty(values, "values must not be empty");
        String elements = values.stream()
                .filter(Objects::nonNull)
                .map(MilvusExprHelper::formatValue)
                .collect(Collectors.joining(", "));
        return String.format("%s in [%s]", field.trim(), elements);
    }

    /**
     * NOT IN 排除表达式 (field not in [val1, val2, ...])
     *
     * @param field  字段名
     * @param values 候选值集合
     * @return 表达式
     */
    public static String notIn(String field, Collection<?> values) {
        Assert.hasText(field, "field must not be empty");
        Assert.notEmpty(values, "values must not be empty");
        String elements = values.stream()
                .filter(Objects::nonNull)
                .map(MilvusExprHelper::formatValue)
                .collect(Collectors.joining(", "));
        return String.format("%s not in [%s]", field.trim(), elements);
    }

    /**
     * 前缀或模式模糊匹配表达式 (field like 'prefix%')
     *
     * @param field   字段名
     * @param pattern 模式字符串 (如 "prefix%", "%term%")
     * @return 表达式
     */
    public static String like(String field, String pattern) {
        Assert.hasText(field, "field must not be empty");
        Assert.hasText(pattern, "pattern must not be empty");
        return String.format("%s like '%s'", field.trim(), escape(pattern.trim()));
    }

    /**
     * 逻辑与组合多个表达式 (自动防御性加括号: (e1) and (e2))
     *
     * @param exprs 待组合表达式
     * @return 组合后的表达式；若全为空则返回空字符串
     */
    public static String and(String... exprs) {
        if (exprs == null || exprs.length == 0) {
            return "";
        }
        return and(Arrays.asList(exprs));
    }

    /**
     * 逻辑与组合表达式列表
     *
     * @param exprs 表达式集合
     * @return 组合后的表达式
     */
    public static String and(Collection<String> exprs) {
        if (exprs == null || exprs.isEmpty()) {
            return "";
        }
        List<String> validExprs = exprs.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
        if (validExprs.isEmpty()) {
            return "";
        }
        if (validExprs.size() == 1) {
            return validExprs.get(0);
        }
        return validExprs.stream()
                .map(e -> String.format("(%s)", e))
                .collect(Collectors.joining(" and "));
    }

    /**
     * 逻辑或组合多个表达式 (自动防御性加括号: (e1) or (e2))
     *
     * @param exprs 待组合表达式
     * @return 组合后的表达式
     */
    public static String or(String... exprs) {
        if (exprs == null || exprs.length == 0) {
            return "";
        }
        return or(Arrays.asList(exprs));
    }

    /**
     * 逻辑或组合表达式列表
     *
     * @param exprs 表达式集合
     * @return 组合后的表达式
     */
    public static String or(Collection<String> exprs) {
        if (exprs == null || exprs.isEmpty()) {
            return "";
        }
        List<String> validExprs = exprs.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
        if (validExprs.isEmpty()) {
            return "";
        }
        if (validExprs.size() == 1) {
            return validExprs.get(0);
        }
        return validExprs.stream()
                .map(e -> String.format("(%s)", e))
                .collect(Collectors.joining(" or "));
    }

    /**
     * 逻辑非否定表达式 (not (expr))
     *
     * @param expr 原始表达式
     * @return 否定表达式
     */
    public static String not(String expr) {
        if (!StringUtils.hasText(expr)) {
            return "";
        }
        return String.format("not (%s)", expr.trim());
    }

    /**
     * 对字符串中的单引号与反斜杠进行安全转义，防止表达式解析异常
     *
     * @param value 原始字符串
     * @return 转义后的字符串
     */
    public static String escape(String value) {
        if (value == null) {
            return "";
        }
        return value.replace("\\", "\\\\").replace("'", "\\'");
    }

    /**
     * 格式化标量值为 Milvus 表达式合法字面量
     *
     * @param value 目标对象
     * @return 格式化后的字面量
     */
    public static String formatValue(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        return "'" + escape(value.toString()) + "'";
    }

    /**
     * 创建链式 Fluent 表达式构造器
     *
     * @return 表达式构造器
     */
    public static Builder builder() {
        return new Builder();
    }

    /**
     * Fluent 表达式链式构造器
     */
    public static class Builder {
        private final List<String> conditions = new ArrayList<>();
        private boolean isOr = false;

        public Builder textMatch(String field, String queryText) {
            conditions.add(MilvusExprHelper.textMatch(field, queryText));
            return this;
        }

        public Builder eq(String field, Object value) {
            conditions.add(MilvusExprHelper.eq(field, value));
            return this;
        }

        public Builder ne(String field, Object value) {
            conditions.add(MilvusExprHelper.ne(field, value));
            return this;
        }

        public Builder gt(String field, Object value) {
            conditions.add(MilvusExprHelper.gt(field, value));
            return this;
        }

        public Builder gte(String field, Object value) {
            conditions.add(MilvusExprHelper.gte(field, value));
            return this;
        }

        public Builder lt(String field, Object value) {
            conditions.add(MilvusExprHelper.lt(field, value));
            return this;
        }

        public Builder lte(String field, Object value) {
            conditions.add(MilvusExprHelper.lte(field, value));
            return this;
        }

        public Builder in(String field, Collection<?> values) {
            conditions.add(MilvusExprHelper.in(field, values));
            return this;
        }

        public Builder notIn(String field, Collection<?> values) {
            conditions.add(MilvusExprHelper.notIn(field, values));
            return this;
        }

        public Builder like(String field, String pattern) {
            conditions.add(MilvusExprHelper.like(field, pattern));
            return this;
        }

        public Builder raw(String rawExpr) {
            if (StringUtils.hasText(rawExpr)) {
                conditions.add(rawExpr.trim());
            }
            return this;
        }

        public Builder useOr() {
            this.isOr = true;
            return this;
        }

        public String build() {
            if (conditions.isEmpty()) {
                return "";
            }
            return isOr ? MilvusExprHelper.or(conditions) : MilvusExprHelper.and(conditions);
        }
    }
}
