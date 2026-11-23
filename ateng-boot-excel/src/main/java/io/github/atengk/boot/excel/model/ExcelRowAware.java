package io.github.atengk.boot.excel.model;

/**
 * 可选导入模型物理行号回填契约接口
 * <p>
 * 当导入实体或 DTO/VO 按需实现本接口后，{@link io.github.atengk.boot.excel.listener.BatchValidationListener}
 * 在解析每一行数据时会主动将当前行的真实 1-based 物理行号回填至 {@link #setRowIndex(Integer)}；
 * 建议在对应实体属性上标注 {@code @ExcelIgnore}，防止 Apache Fesod 误将该内部追踪字段映射为 Excel 导入导出列。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-23
 */
public interface ExcelRowAware {

    /**
     * 回填当前行在原始 Excel 工作簿中的 1-based 物理行号
     *
     * @param rowIndex 1-based 物理行号（如首行数据通常为 2）
     */
    void setRowIndex(Integer rowIndex);

    /**
     * 获取当前实体持有的 1-based 物理行号（默认返回 {@code null}，供未额外暴露 Getter 的轻量实现复用）
     *
     * @return 1-based 物理行号，未回填或未存储时返回 {@code null}
     */
    default Integer getRowIndex() {
        return null;
    }
}
