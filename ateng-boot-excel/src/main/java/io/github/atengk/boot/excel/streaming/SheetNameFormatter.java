package io.github.atengk.boot.excel.streaming;

/**
 * 跨 Sheet 自动换页时的工作表名称格式化器
 *
 * @author Ateng
 * @since 2026-10-20
 */
@FunctionalInterface
public interface SheetNameFormatter {

    /**
     * 默认格式化策略：首个工作表保留原名，后续工作表追加下划线序号（例如 {@code Sheet1}, {@code Sheet1_2}, {@code Sheet1_3}）
     */
    SheetNameFormatter DEFAULT = (baseSheetName, sheetIndex) ->
            (sheetIndex == 0) ? baseSheetName : (baseSheetName + "_" + (sheetIndex + 1));

    /**
     * 根据基础名称与工作表序号计算目标工作表名称
     *
     * @param baseSheetName 基础工作表名称（例如 {@code "Sheet1"}）
     * @param sheetIndex    工作表物理序号（0-based，首个工作表为 0）
     * @return 格式化后的目标工作表名称
     */
    String format(String baseSheetName, int sheetIndex);
}
