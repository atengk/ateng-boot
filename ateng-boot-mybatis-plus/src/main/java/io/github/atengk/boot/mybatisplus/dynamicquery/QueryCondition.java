package io.github.atengk.boot.mybatisplus.dynamicquery;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 单项动态查询过滤条件契约模型
 * 承载目标属性名称、受控操作符与单值/多值参数
 *
 * @author Ateng
 * @since 2026-10-19
 */
@Getter
@Setter
@ToString
@EqualsAndHashCode
public class QueryCondition implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 目标属性名称
     */
    @NotBlank(message = "动态查询属性名称不能为空")
    private String field;

    /**
     * 查询操作符（默认 EQ）
     */
    @NotNull(message = "查询操作符不能为空")
    private QueryOperator operator = QueryOperator.EQ;

    /**
     * 单值比较参数
     */
    private Object value;

    /**
     * 多值比较参数列表（用于 IN、NOT_IN、BETWEEN 等场景）
     */
    private List<Object> values = new ArrayList<>();

    /**
     * 默认无参构造（供序列化与框架反射使用）
     */
    public QueryCondition() {
    }

    /**
     * 构造单值比较条件
     *
     * @param field    属性名称
     * @param operator 查询操作符
     * @param value    参数值
     */
    public QueryCondition(String field, QueryOperator operator, Object value) {
        this.field = (field != null) ? field.trim() : null;
        this.operator = (operator != null) ? operator : QueryOperator.EQ;
        setValue(value);
    }

    /**
     * 构造多值比较条件
     *
     * @param field    属性名称
     * @param operator 查询操作符
     * @param values   多值参数集合
     */
    public QueryCondition(String field, QueryOperator operator, Collection<?> values) {
        this.field = (field != null) ? field.trim() : null;
        this.operator = (operator != null) ? operator : QueryOperator.EQ;
        setValues(values);
    }

    /**
     * 设置单值参数，若传入 Collection 或数组则自适应填充 values
     *
     * @param value 参数值
     */
    public void setValue(Object value) {
        this.value = value;
        if (value instanceof Collection<?> col) {
            this.values = new ArrayList<>(col);
        } else if (value != null && value.getClass().isArray()) {
            this.values = new ArrayList<>(Arrays.asList((Object[]) value));
        }
    }

    /**
     * 获取多值参数列表（保底返回非空列表）
     *
     * @return 多值参数列表
     */
    public List<Object> getValues() {
        if (this.values == null) {
            this.values = new ArrayList<>();
        }
        return this.values;
    }

    /**
     * 设置多值参数集合（执行空安全防御）
     *
     * @param values 多值集合
     */
    public void setValues(Collection<?> values) {
        if (values == null) {
            this.values = new ArrayList<>();
        } else {
            this.values = new ArrayList<>(values);
        }
    }

    /**
     * 设置属性名称并去除首尾空格
     *
     * @param field 属性名称
     */
    public void setField(String field) {
        this.field = (field != null) ? field.trim() : null;
    }

    /**
     * 创建条件工厂方法
     *
     * @param field    属性名称
     * @param operator 操作符
     * @param value    单值
     * @return QueryCondition 实例
     */
    public static QueryCondition of(String field, QueryOperator operator, Object value) {
        Objects.requireNonNull(field, "属性名称不能为 null");
        return new QueryCondition(field, operator, value);
    }

    /**
     * 创建等于条件 (EQ)
     *
     * @param field 属性名称
     * @param value 参数值
     * @return QueryCondition 实例
     */
    public static QueryCondition eq(String field, Object value) {
        return of(field, QueryOperator.EQ, value);
    }

    /**
     * 创建不等于条件 (NE)
     *
     * @param field 属性名称
     * @param value 参数值
     * @return QueryCondition 实例
     */
    public static QueryCondition ne(String field, Object value) {
        return of(field, QueryOperator.NE, value);
    }

    /**
     * 创建大于条件 (GT)
     *
     * @param field 属性名称
     * @param value 参数值
     * @return QueryCondition 实例
     */
    public static QueryCondition gt(String field, Object value) {
        return of(field, QueryOperator.GT, value);
    }

    /**
     * 创建大于等于条件 (GE)
     *
     * @param field 属性名称
     * @param value 参数值
     * @return QueryCondition 实例
     */
    public static QueryCondition ge(String field, Object value) {
        return of(field, QueryOperator.GE, value);
    }

    /**
     * 创建小于条件 (LT)
     *
     * @param field 属性名称
     * @param value 参数值
     * @return QueryCondition 实例
     */
    public static QueryCondition lt(String field, Object value) {
        return of(field, QueryOperator.LT, value);
    }

    /**
     * 创建小于等于条件 (LE)
     *
     * @param field 属性名称
     * @param value 参数值
     * @return QueryCondition 实例
     */
    public static QueryCondition le(String field, Object value) {
        return of(field, QueryOperator.LE, value);
    }

    /**
     * 创建全模糊条件 (LIKE)
     *
     * @param field 属性名称
     * @param value 参数值
     * @return QueryCondition 实例
     */
    public static QueryCondition like(String field, Object value) {
        return of(field, QueryOperator.LIKE, value);
    }

    /**
     * 创建左模糊条件 (LIKE_LEFT)
     *
     * @param field 属性名称
     * @param value 参数值
     * @return QueryCondition 实例
     */
    public static QueryCondition likeLeft(String field, Object value) {
        return of(field, QueryOperator.LIKE_LEFT, value);
    }

    /**
     * 创建右模糊条件 (LIKE_RIGHT)
     *
     * @param field 属性名称
     * @param value 参数值
     * @return QueryCondition 实例
     */
    public static QueryCondition likeRight(String field, Object value) {
        return of(field, QueryOperator.LIKE_RIGHT, value);
    }

    /**
     * 创建 IN 包含条件
     *
     * @param field  属性名称
     * @param values 参数集合
     * @return QueryCondition 实例
     */
    public static QueryCondition in(String field, Collection<?> values) {
        Objects.requireNonNull(field, "属性名称不能为 null");
        return new QueryCondition(field, QueryOperator.IN, values);
    }

    /**
     * 创建 NOT IN 不包含条件
     *
     * @param field  属性名称
     * @param values 参数集合
     * @return QueryCondition 实例
     */
    public static QueryCondition notIn(String field, Collection<?> values) {
        Objects.requireNonNull(field, "属性名称不能为 null");
        return new QueryCondition(field, QueryOperator.NOT_IN, values);
    }

    /**
     * 创建闭区间范围条件 (BETWEEN)
     *
     * @param field 属性名称
     * @param val1  起始值
     * @param val2  截止值
     * @return QueryCondition 实例
     */
    public static QueryCondition between(String field, Object val1, Object val2) {
        Objects.requireNonNull(field, "属性名称不能为 null");
        List<Object> list = new ArrayList<>(2);
        list.add(val1);
        list.add(val2);
        return new QueryCondition(field, QueryOperator.BETWEEN, list);
    }

    /**
     * 创建 IS NULL 为空条件
     *
     * @param field 属性名称
     * @return QueryCondition 实例
     */
    public static QueryCondition isNull(String field) {
        return of(field, QueryOperator.IS_NULL, null);
    }

    /**
     * 创建 IS NOT NULL 非空条件
     *
     * @param field 属性名称
     * @return QueryCondition 实例
     */
    public static QueryCondition isNotNull(String field) {
        return of(field, QueryOperator.IS_NOT_NULL, null);
    }
}
