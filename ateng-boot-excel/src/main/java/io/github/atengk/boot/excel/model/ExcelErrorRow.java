package io.github.atengk.boot.excel.model;

import io.github.atengk.boot.core.util.LambdaUtil;
import io.github.atengk.boot.core.util.PropertyFunc;
import io.github.atengk.boot.excel.util.ExcelColumnMetadataResolver;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * Excel 导入行级校验错误模型
 * <p>
 * 封装数据校验失败时的物理行号、列信息、属性名、非法值以及具体校验提示信息。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExcelErrorRow implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 物理行号（从 1 开始，例如表头通常为第 1 行，首行数据通常为第 2 行）
     */
    private Integer rowIndex;

    /**
     * 列索引（从 0 开始，若未匹配则可能为 null）
     */
    private Integer columnIndex;

    /**
     * 列标题名称（对应实体 {@code @ExcelProperty} 标注的表头名称）
     */
    private String columnName;

    /**
     * 实体 Java 属性名（例如 {@code mobile}、{@code age}）
     */
    private String fieldName;

    /**
     * 校验失败时的原始输入值
     */
    private Object invalidValue;

    /**
     * 校验失败错误描述（例如 {@code "手机号格式不正确"}）
     */
    private String message;

    /**
     * 校验失败行的原始实体数据对象（未通过校验的 Java Bean 实例，便于差错隔离导出）
     */
    private Object rowData;

    /**
     * 基于实体模型与属性名称构建列级业务校验错误（自动解析 0-based 列索引与最末级表头名称）
     *
     * @param modelClass   导入实体模型 Class（不可为空）
     * @param rowIndex     原始 Excel 物理行号（从 1 开始）
     * @param fieldName    实体 Java 属性名（不可为空，且必须为有效 Excel 映射列）
     * @param invalidValue 校验失败时的原始字段值
     * @param message      校验失败描述信息
     * @return 结构化 {@link ExcelErrorRow}
     * @throws IllegalArgumentException 当模型为空、行号非法或未解析到有效列元数据时 Fail-Fast 抛出
     */
    public static ExcelErrorRow of(Class<?> modelClass,
                                   int rowIndex,
                                   String fieldName,
                                   Object invalidValue,
                                   String message) {
        return of(modelClass, rowIndex, fieldName, invalidValue, message, null);
    }

    /**
     * 基于实体模型、属性名称与原始行实体构建列级业务校验错误
     *
     * @param modelClass   导入实体模型 Class（不可为空）
     * @param rowIndex     原始 Excel 物理行号（从 1 开始）
     * @param fieldName    实体 Java 属性名（不可为空，且必须为有效 Excel 映射列）
     * @param invalidValue 校验失败时的原始字段值
     * @param message      校验失败描述信息
     * @param rowData      原始行实体对象（可选，便于差错隔离导出）
     * @return 结构化 {@link ExcelErrorRow}
     * @throws IllegalArgumentException 当模型为空、行号非法或未解析到有效列元数据时 Fail-Fast 抛出
     */
    public static ExcelErrorRow of(Class<?> modelClass,
                                   int rowIndex,
                                   String fieldName,
                                   Object invalidValue,
                                   String message,
                                   Object rowData) {
        if (modelClass == null) {
            throw new IllegalArgumentException("modelClass 不能为空");
        }
        if (rowIndex < 1) {
            throw new IllegalArgumentException("rowIndex 必须为大于等于 1 的物理行号: " + rowIndex);
        }
        if (fieldName == null || fieldName.isBlank()) {
            throw new IllegalArgumentException("fieldName 不能为空");
        }
        String normalizedField = fieldName.trim();
        ExcelColumnMetadataResolver.ColumnMetadata meta =
                ExcelColumnMetadataResolver.resolve(modelClass).getColumnByFieldName(normalizedField);
        if (meta == null) {
            throw new IllegalArgumentException("未在模型 [" + modelClass.getName()
                    + "] 中解析到有效 Excel 列元数据: fieldName=" + normalizedField
                    + "（如需构造整行级错误请调用 ExcelErrorRow.ofRow）");
        }
        return ExcelErrorRow.builder()
                .rowIndex(rowIndex)
                .columnIndex(meta.columnIndex())
                .columnName(meta.leafHeaderName())
                .fieldName(meta.fieldName())
                .invalidValue(invalidValue)
                .message(message)
                .rowData(rowData)
                .build();
    }

    /**
     * 基于类型安全 Lambda 属性方法引用构建列级业务校验错误（自动解析 0-based 列索引与最末级表头名称）
     *
     * @param modelClass  导入实体模型 Class（不可为空）
     * @param rowIndex    原始 Excel 物理行号（从 1 开始）
     * @param fieldGetter 属性方法引用（如 {@code UserExcelVO::getPhone}）
     * @param invalidValue 校验失败时的原始字段值
     * @param message     校验失败描述信息
     * @param <T>         实体泛型
     * @param <R>         字段类型泛型
     * @return 结构化 {@link ExcelErrorRow}
     */
    public static <T, R> ExcelErrorRow of(Class<T> modelClass,
                                         int rowIndex,
                                         PropertyFunc<T, R> fieldGetter,
                                         Object invalidValue,
                                         String message) {
        return of(modelClass, rowIndex, fieldGetter, invalidValue, message, null);
    }

    /**
     * 基于类型安全 Lambda 属性方法引用与原始行实体构建列级业务校验错误
     *
     * @param modelClass   导入实体模型 Class（不可为空）
     * @param rowIndex     原始 Excel 物理行号（从 1 开始）
     * @param fieldGetter  属性方法引用（如 {@code UserExcelVO::getPhone}）
     * @param invalidValue 校验失败时的原始字段值
     * @param message      校验失败描述信息
     * @param rowData      原始行实体对象（可选，便于差错隔离导出）
     * @param <T>          实体泛型
     * @param <R>          字段类型泛型
     * @return 结构化 {@link ExcelErrorRow}
     */
    public static <T, R> ExcelErrorRow of(Class<T> modelClass,
                                         int rowIndex,
                                         PropertyFunc<T, R> fieldGetter,
                                         Object invalidValue,
                                         String message,
                                         T rowData) {
        if (fieldGetter == null) {
            throw new IllegalArgumentException("fieldGetter 不能为空");
        }
        return of(modelClass, rowIndex, LambdaUtil.getFieldName(fieldGetter), invalidValue, message, rowData);
    }

    /**
     * 构建整行级业务校验错误（不绑定特定列索引，交由反馈渲染默认落于首列批注）
     *
     * @param rowIndex 原始 Excel 物理行号（从 1 开始）
     * @param message  校验失败描述信息
     * @return 结构化 {@link ExcelErrorRow}
     */
    public static ExcelErrorRow ofRow(int rowIndex, String message) {
        return ofRow(rowIndex, message, null);
    }

    /**
     * 构建带原始行实体的整行级业务校验错误
     *
     * @param rowIndex 原始 Excel 物理行号（从 1 开始）
     * @param message  校验失败描述信息
     * @param rowData  原始行实体对象（可选）
     * @return 结构化 {@link ExcelErrorRow}
     */
    public static ExcelErrorRow ofRow(int rowIndex, String message, Object rowData) {
        if (rowIndex < 1) {
            throw new IllegalArgumentException("rowIndex 必须为大于等于 1 的物理行号: " + rowIndex);
        }
        return ExcelErrorRow.builder()
                .rowIndex(rowIndex)
                .columnIndex(null)
                .columnName("整行")
                .fieldName(null)
                .invalidValue(null)
                .message(message)
                .rowData(rowData)
                .build();
    }
}
