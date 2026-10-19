package io.github.atengk.boot.mybatisplus.dynamicquery;

import io.github.atengk.boot.core.model.PageQuery;
import jakarta.validation.Valid;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 标准动态查询请求契约模型
 * 继承 PageQuery，统一承载无状态分页入参、排序规则与多维度结构化动态过滤条件
 *
 * @author Ateng
 * @since 2026-10-19
 */
@Getter
@Setter
@ToString(callSuper = true)
public class DynamicQueryRequest extends PageQuery {

    private static final long serialVersionUID = 1L;

    /**
     * 动态过滤条件列表
     */
    @Valid
    private List<QueryCondition> conditions = new ArrayList<>();

    /**
     * 默认构造方法
     */
    public DynamicQueryRequest() {
        super();
    }

    /**
     * 指定当前页码与单页条数的构造方法
     *
     * @param current 当前页码
     * @param size    单页大小
     */
    public DynamicQueryRequest(long current, long size) {
        super(current, size);
    }

    /**
     * 获取动态过滤条件列表（保底返回非空列表）
     *
     * @return 过滤条件列表
     */
    public List<QueryCondition> getConditions() {
        if (this.conditions == null) {
            this.conditions = new ArrayList<>();
        }
        return this.conditions;
    }

    /**
     * 设置动态过滤条件列表（执行空安全防御）
     *
     * @param conditions 过滤条件列表
     */
    public void setConditions(List<QueryCondition> conditions) {
        if (conditions == null) {
            this.conditions = new ArrayList<>();
        } else {
            this.conditions = new ArrayList<>(conditions);
        }
    }

    /**
     * 添加动态过滤条件
     *
     * @param condition 条件实例
     * @return 当前 DynamicQueryRequest 实例（支持链式调用）
     */
    public DynamicQueryRequest addCondition(QueryCondition condition) {
        if (condition != null) {
            getConditions().add(condition);
        }
        return this;
    }

    /**
     * 快捷添加单值过滤条件
     *
     * @param field    属性名称
     * @param operator 操作符
     * @param value    参数值
     * @return 当前 DynamicQueryRequest 实例（支持链式调用）
     */
    public DynamicQueryRequest addCondition(String field, QueryOperator operator, Object value) {
        return addCondition(QueryCondition.of(field, operator, value));
    }

    /**
     * 快捷添加等于条件 (EQ)
     *
     * @param field 属性名称
     * @param value 参数值
     * @return 当前 DynamicQueryRequest 实例
     */
    public DynamicQueryRequest eq(String field, Object value) {
        return addCondition(QueryCondition.eq(field, value));
    }

    /**
     * 快捷添加不等于条件 (NE)
     *
     * @param field 属性名称
     * @param value 参数值
     * @return 当前 DynamicQueryRequest 实例
     */
    public DynamicQueryRequest ne(String field, Object value) {
        return addCondition(QueryCondition.ne(field, value));
    }

    /**
     * 快捷添加大于条件 (GT)
     *
     * @param field 属性名称
     * @param value 参数值
     * @return 当前 DynamicQueryRequest 实例
     */
    public DynamicQueryRequest gt(String field, Object value) {
        return addCondition(QueryCondition.gt(field, value));
    }

    /**
     * 快捷添加大于等于条件 (GE)
     *
     * @param field 属性名称
     * @param value 参数值
     * @return 当前 DynamicQueryRequest 实例
     */
    public DynamicQueryRequest ge(String field, Object value) {
        return addCondition(QueryCondition.ge(field, value));
    }

    /**
     * 快捷添加小于条件 (LT)
     *
     * @param field 属性名称
     * @param value 参数值
     * @return 当前 DynamicQueryRequest 实例
     */
    public DynamicQueryRequest lt(String field, Object value) {
        return addCondition(QueryCondition.lt(field, value));
    }

    /**
     * 快捷添加小于等于条件 (LE)
     *
     * @param field 属性名称
     * @param value 参数值
     * @return 当前 DynamicQueryRequest 实例
     */
    public DynamicQueryRequest le(String field, Object value) {
        return addCondition(QueryCondition.le(field, value));
    }

    /**
     * 快捷添加全模糊匹配条件 (LIKE)
     *
     * @param field 属性名称
     * @param value 参数值
     * @return 当前 DynamicQueryRequest 实例
     */
    public DynamicQueryRequest like(String field, Object value) {
        return addCondition(QueryCondition.like(field, value));
    }

    /**
     * 快捷添加左模糊匹配条件 (LIKE_LEFT)
     *
     * @param field 属性名称
     * @param value 参数值
     * @return 当前 DynamicQueryRequest 实例
     */
    public DynamicQueryRequest likeLeft(String field, Object value) {
        return addCondition(QueryCondition.likeLeft(field, value));
    }

    /**
     * 快捷添加右模糊匹配条件 (LIKE_RIGHT)
     *
     * @param field 属性名称
     * @param value 参数值
     * @return 当前 DynamicQueryRequest 实例
     */
    public DynamicQueryRequest likeRight(String field, Object value) {
        return addCondition(QueryCondition.likeRight(field, value));
    }

    /**
     * 快捷添加 IN 包含条件
     *
     * @param field  属性名称
     * @param values 参数集合
     * @return 当前 DynamicQueryRequest 实例
     */
    public DynamicQueryRequest in(String field, Collection<?> values) {
        return addCondition(QueryCondition.in(field, values));
    }

    /**
     * 快捷添加 NOT IN 不包含条件
     *
     * @param field  属性名称
     * @param values 参数集合
     * @return 当前 DynamicQueryRequest 实例
     */
    public DynamicQueryRequest notIn(String field, Collection<?> values) {
        return addCondition(QueryCondition.notIn(field, values));
    }

    /**
     * 快捷添加闭区间范围条件 (BETWEEN)
     *
     * @param field 属性名称
     * @param val1  起始值
     * @param val2  截止值
     * @return 当前 DynamicQueryRequest 实例
     */
    public DynamicQueryRequest between(String field, Object val1, Object val2) {
        return addCondition(QueryCondition.between(field, val1, val2));
    }

    /**
     * 快捷添加 IS NULL 为空条件
     *
     * @param field 属性名称
     * @return 当前 DynamicQueryRequest 实例
     */
    public DynamicQueryRequest isNull(String field) {
        return addCondition(QueryCondition.isNull(field));
    }

    /**
     * 快捷添加 IS NOT NULL 非空条件
     *
     * @param field 属性名称
     * @return 当前 DynamicQueryRequest 实例
     */
    public DynamicQueryRequest isNotNull(String field) {
        return addCondition(QueryCondition.isNotNull(field));
    }

    /**
     * 添加指定 OR 分组的作用域条件
     *
     * @param orGroup   OR 作用域分组标识（如 "1", "groupA"）
     * @param condition 条件实例
     * @return 当前 DynamicQueryRequest 实例
     */
    public DynamicQueryRequest or(String orGroup, QueryCondition condition) {
        if (condition != null) {
            condition.setOrGroup(orGroup);
            getConditions().add(condition);
        }
        return this;
    }

    /**
     * 快捷添加指定 OR 分组的等于条件
     *
     * @param orGroup OR 作用域分组标识
     * @param field   属性名称
     * @param value   参数值
     * @return 当前 DynamicQueryRequest 实例
     */
    public DynamicQueryRequest orEq(String orGroup, String field, Object value) {
        return or(orGroup, QueryCondition.eq(field, value));
    }

    /**
     * 快捷添加指定 OR 分组的模糊匹配条件
     *
     * @param orGroup OR 作用域分组标识
     * @param field   属性名称
     * @param value   参数值
     * @return 当前 DynamicQueryRequest 实例
     */
    public DynamicQueryRequest orLike(String orGroup, String field, Object value) {
        return or(orGroup, QueryCondition.like(field, value));
    }

    /**
     * 清空全部过滤条件
     *
     * @return 当前 DynamicQueryRequest 实例
     */
    public DynamicQueryRequest clearConditions() {
        if (this.conditions != null) {
            this.conditions.clear();
        }
        return this;
    }
}
