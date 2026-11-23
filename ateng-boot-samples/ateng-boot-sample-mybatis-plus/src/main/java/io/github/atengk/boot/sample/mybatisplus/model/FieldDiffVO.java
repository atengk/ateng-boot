package io.github.atengk.boot.sample.mybatisplus.model;

import io.github.atengk.boot.mybatisplus.audit.FieldDiff;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 字段级变更 Diff 明细视图对象
 *
 * @author Ateng
 * @since 2026-11-08
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class FieldDiffVO implements Serializable {

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
     * 属性数据类型名称
     */
    private String fieldType;

    public static FieldDiffVO fromDiff(FieldDiff diff) {
        if (diff == null) {
            return null;
        }
        FieldDiffVO vo = new FieldDiffVO();
        vo.setPropertyName(diff.getPropertyName());
        vo.setColumnName(diff.getColumnName());
        vo.setOldValue(diff.getOldValue());
        vo.setNewValue(diff.getNewValue());
        vo.setFieldType(diff.getFieldType());
        return vo;
    }
}
