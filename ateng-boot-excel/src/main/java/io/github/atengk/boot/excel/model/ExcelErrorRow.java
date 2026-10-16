package io.github.atengk.boot.excel.model;

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
}
