package io.github.atengk.boot.mybatisplus.audit;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 字段级变更 Diff 明细模型
 *
 * @author Ateng
 * @since 2026-10-19
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FieldDiff implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 实体属性名
     */
    private String propertyName;

    /**
     * 数据库列名
     */
    private String columnName;

    /**
     * 变更前旧值
     */
    private Object oldValue;

    /**
     * 变更后新值
     */
    private Object newValue;

    /**
     * 属性数据类型名称（如 java.lang.String, java.lang.Integer）
     */
    private String fieldType;
}
