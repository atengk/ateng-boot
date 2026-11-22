package io.github.atengk.boot.excel.model;

import java.io.Serial;
import java.io.Serializable;

/**
 * 不可变有效导入行与 1-based 物理行号关联载体
 * <p>
 * 用于无侵入式地将通过前置校验的业务实体与原始 Excel 中的 1-based 物理行号绑定，
 * 避免跳过空行、前序行校验失败或多级表头导致后续数据库批量校验无法定位真实物理行号。
 * </p>
 *
 * @param rowIndex 原始 Excel 物理行号（从 1 开始，如表头占 1 行则首行数据为 2）
 * @param data     通过前置校验的实体对象实例
 * @param <T>      数据实体泛型
 * @author Ateng
 * @since 2026-11-23
 */
public record ExcelRowData<T>(int rowIndex, T data) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    public ExcelRowData {
        if (rowIndex < 1) {
            throw new IllegalArgumentException("rowIndex 必须为大于等于 1 的物理行号: " + rowIndex);
        }
    }
}
