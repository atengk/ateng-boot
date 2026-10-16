package io.github.atengk.boot.excel.handler;

import org.apache.fesod.sheet.enums.CellDataTypeEnum;
import org.apache.fesod.sheet.metadata.Head;
import org.apache.fesod.sheet.metadata.data.WriteCellData;
import org.apache.fesod.sheet.write.metadata.holder.WriteSheetHolder;
import org.apache.fesod.sheet.write.style.column.AbstractColumnWidthStyleStrategy;
import org.apache.poi.ss.usermodel.Cell;
import org.springframework.util.CollectionUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 智能自适应列宽处理器
 * <p>
 * 针对中文字符与英文字符进行精准加权计算（中文占 2.0 字符宽，英文数字占 1.0 字符宽），
 * 并内置安全内边距与 min/max 边界保护阈值，彻底避免短列表头截断与长数字显示为 ### 的问题。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-17
 */
public class SmartAutoColumnWidthWriteHandler extends AbstractColumnWidthStyleStrategy {

    /**
     * 默认最小列宽（字符数）
     */
    public static final int DEFAULT_MIN_COLUMN_WIDTH = 12;

    /**
     * 默认最大列宽（字符数）
     */
    public static final int DEFAULT_MAX_COLUMN_WIDTH = 60;

    /**
     * 默认安全内边距（字符数）
     */
    public static final int DEFAULT_PADDING = 2;

    private final int minColumnWidth;
    private final int maxColumnWidth;
    private final int padding;

    /**
     * 每个 Sheet 的每列最大字符宽度缓存 (sheetNo -> (columnIndex -> maxCharWidth))
     */
    private final Map<Integer, Map<Integer, Integer>> sheetColumnWidthCache = new HashMap<>(8);

    public SmartAutoColumnWidthWriteHandler() {
        this(DEFAULT_MIN_COLUMN_WIDTH, DEFAULT_MAX_COLUMN_WIDTH, DEFAULT_PADDING);
    }

    public SmartAutoColumnWidthWriteHandler(int minColumnWidth, int maxColumnWidth) {
        this(minColumnWidth, maxColumnWidth, DEFAULT_PADDING);
    }

    public SmartAutoColumnWidthWriteHandler(int minColumnWidth, int maxColumnWidth, int padding) {
        this.minColumnWidth = Math.max(1, minColumnWidth);
        this.maxColumnWidth = Math.max(this.minColumnWidth, maxColumnWidth);
        this.padding = Math.max(0, padding);
    }

    @Override
    protected void setColumnWidth(WriteSheetHolder writeSheetHolder, List<WriteCellData<?>> cellDataList,
                                  Cell cell, Head head, Integer relativeRowIndex, Boolean isHead) {
        boolean needSetWidth = Boolean.TRUE.equals(isHead) || !CollectionUtils.isEmpty(cellDataList);
        if (!needSetWidth || cell == null || writeSheetHolder == null) {
            return;
        }

        Map<Integer, Integer> columnWidthMap = sheetColumnWidthCache.computeIfAbsent(
                writeSheetHolder.getSheetNo(), k -> new HashMap<>(16));

        int calculatedWidth = computeDataWidth(cellDataList, cell, isHead);
        int finalWidth = Math.max(minColumnWidth, Math.min(calculatedWidth, maxColumnWidth));

        int colIndex = cell.getColumnIndex();
        Integer currentMaxWidth = columnWidthMap.get(colIndex);
        if (currentMaxWidth == null || finalWidth > currentMaxWidth) {
            columnWidthMap.put(colIndex, finalWidth);
            writeSheetHolder.getSheet().setColumnWidth(colIndex, finalWidth * 256);
        }
    }

    /**
     * 精确计算表头或单元格数据的可视宽度（字符数 + 安全内边距）
     */
    private int computeDataWidth(List<WriteCellData<?>> cellDataList, Cell cell, Boolean isHead) {
        if (Boolean.TRUE.equals(isHead)) {
            String headText = (cell.getStringCellValue() != null) ? cell.getStringCellValue() : "";
            return computeTextWidth(headText) + padding;
        }

        if (CollectionUtils.isEmpty(cellDataList)) {
            return minColumnWidth;
        }

        WriteCellData<?> cellData = cellDataList.get(0);
        if (cellData == null) {
            return minColumnWidth;
        }

        CellDataTypeEnum type = cellData.getType();
        if (type == null) {
            return minColumnWidth;
        }

        String rawStr;
        switch (type) {
            case STRING:
                rawStr = cellData.getStringValue();
                break;
            case BOOLEAN:
                rawStr = (cellData.getBooleanValue() != null) ? cellData.getBooleanValue().toString() : "";
                break;
            case NUMBER:
                rawStr = (cellData.getNumberValue() != null) ? cellData.getNumberValue().toString() : "";
                break;
            case DATE:
                rawStr = (cellData.getDateValue() != null) ? cellData.getDateValue().toString() : "";
                break;
            default:
                rawStr = "";
                break;
        }

        return computeTextWidth(rawStr) + padding;
    }

    /**
     * 加权计算文本的可视字符宽度
     * 中文及全角字符计 2.0 字符宽，半角字符计 1.0 字符宽
     */
    public static int computeTextWidth(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        double width = 0.0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c > 127) {
                width += 2.0;
            } else {
                width += 1.0;
            }
        }
        return (int) Math.ceil(width);
    }

    public int getMinColumnWidth() {
        return minColumnWidth;
    }

    public int getMaxColumnWidth() {
        return maxColumnWidth;
    }

    public int getPadding() {
        return padding;
    }
}
