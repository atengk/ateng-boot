package io.github.atengk.boot.excel.exception;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.excel.model.ExcelErrorRow;
import lombok.Getter;

import java.util.Collections;
import java.util.List;

/**
 * Excel 导入校验异常
 * <p>
 * 当 Excel 数据行未通过 JSR-303 规则校验时抛出，携带完整的结构化错误行信息集合。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-16
 */
@Getter
public class ExcelValidationException extends BusinessException {

    private final List<ExcelErrorRow> errorRows;

    /**
     * 基于结构化错误行集合构造校验异常（自动组装默认摘要信息）
     *
     * @param errorRows 结构化错误行集合
     */
    public ExcelValidationException(List<ExcelErrorRow> errorRows) {
        this(buildDefaultMessage(errorRows), errorRows);
    }

    /**
     * 基于自定义异常信息与结构化错误行集合构造校验异常
     *
     * @param message   自定义异常描述信息
     * @param errorRows 结构化错误行集合
     */
    public ExcelValidationException(String message, List<ExcelErrorRow> errorRows) {
        super(CommonResultCode.BAD_REQUEST.getCode(), message);
        this.errorRows = (errorRows != null) ? Collections.unmodifiableList(errorRows) : Collections.emptyList();
    }

    private static String buildDefaultMessage(List<ExcelErrorRow> errorRows) {
        if (errorRows == null || errorRows.isEmpty()) {
            return "Excel 数据校验失败";
        }
        StringBuilder sb = new StringBuilder("Excel 数据校验失败，共发现 ").append(errorRows.size()).append(" 处错误：");
        int limit = Math.min(errorRows.size(), 5);
        for (int i = 0; i < limit; i++) {
            ExcelErrorRow error = errorRows.get(i);
            sb.append("\n- 第 ").append(error.getRowIndex()).append(" 行 [").append(error.getColumnName() != null ? error.getColumnName() : error.getFieldName()).append("]: ").append(error.getMessage());
        }
        if (errorRows.size() > limit) {
            sb.append("\n... 剩余 ").append(errorRows.size() - limit).append(" 处错误已省略");
        }
        return sb.toString();
    }
}
