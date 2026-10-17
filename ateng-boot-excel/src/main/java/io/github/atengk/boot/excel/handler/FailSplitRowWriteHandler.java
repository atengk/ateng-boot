package io.github.atengk.boot.excel.handler;

import io.github.atengk.boot.excel.model.ExcelErrorRow;
import org.apache.fesod.sheet.write.handler.CellWriteHandler;
import org.apache.fesod.sheet.write.handler.RowWriteHandler;
import org.apache.fesod.sheet.write.handler.context.CellWriteHandlerContext;
import org.apache.fesod.sheet.write.handler.context.RowWriteHandlerContext;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFClientAnchor;
import org.apache.poi.xssf.usermodel.XSSFRichTextString;

import java.util.*;

/**
 * 差错数据隔离导出拦截处理器
 * <p>
 * 配合 {@code ExcelUtil.exportFailedRows} 运行时，在原有实体列最后自动追加【失败原因】表头与聚合错误说明，
 * 并对发生格式或规则错误的具体单元格自动标红（浅粉色背景）与挂载悬浮批注，便于用户直观修正并直接重传。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-17
 */
public class FailSplitRowWriteHandler implements RowWriteHandler, CellWriteHandler {

    public static final String ERROR_COLUMN_TITLE = "失败原因";

    private final Map<Integer, String> errorSummaryByRowIndex;
    private final Map<String, String> errorByRowAndCol;
    private final Map<String, String> errorByRowAndField;
    private final List<Integer> distinctRowIndices;

    private CellStyle errorHeadStyle;
    private CellStyle errorContentStyle;
    private CellStyle highlightedErrorStyle;

    public FailSplitRowWriteHandler(List<ExcelErrorRow> errorRows) {
        this.errorSummaryByRowIndex = new HashMap<>();
        this.errorByRowAndCol = new HashMap<>();
        this.errorByRowAndField = new HashMap<>();
        Set<Integer> distinctSet = new LinkedHashSet<>();

        if (errorRows != null) {
            for (ExcelErrorRow err : errorRows) {
                if (err.getRowIndex() == null) {
                    continue;
                }
                int r = err.getRowIndex();
                distinctSet.add(r);
                // 1. 聚合整行错误提示
                String msg = err.getMessage() != null ? err.getMessage() : "校验失败";
                String colDesc = err.getColumnName() != null ? err.getColumnName() : err.getFieldName();
                String item = (colDesc != null && !colDesc.isBlank()) ? (colDesc + ": " + msg) : msg;

                errorSummaryByRowIndex.merge(r, item, (oldVal, newVal) -> oldVal + "；" + newVal);

                // 2. 映射单个单元格错误
                if (err.getColumnIndex() != null) {
                    errorByRowAndCol.put(r + "_" + err.getColumnIndex(), msg);
                }
                if (err.getFieldName() != null) {
                    errorByRowAndField.put(r + "_" + err.getFieldName(), msg);
                }
            }
        }
        this.distinctRowIndices = new ArrayList<>(distinctSet);
    }

    @Override
    public void afterRowDispose(RowWriteHandlerContext context) {
        if (context == null || context.getRow() == null) {
            return;
        }

        Row row = context.getRow();
        Workbook workbook = row.getSheet().getWorkbook();
        int lastCol = Math.max(0, (int) row.getLastCellNum());

        boolean isHead = Boolean.TRUE.equals(context.getHead());

        if (isHead) {
            // 表头行：追加【失败原因】标题
            Cell headCell = row.createCell(lastCol);
            headCell.setCellValue(ERROR_COLUMN_TITLE);

            if (errorHeadStyle == null) {
                errorHeadStyle = workbook.createCellStyle();
                if (lastCol > 0 && row.getCell(lastCol - 1) != null) {
                    errorHeadStyle.cloneStyleFrom(row.getCell(lastCol - 1).getCellStyle());
                } else {
                    errorHeadStyle.setAlignment(HorizontalAlignment.CENTER);
                    errorHeadStyle.setVerticalAlignment(VerticalAlignment.CENTER);
                    errorHeadStyle.setBorderTop(BorderStyle.THIN);
                    errorHeadStyle.setBorderBottom(BorderStyle.THIN);
                    errorHeadStyle.setBorderLeft(BorderStyle.THIN);
                    errorHeadStyle.setBorderRight(BorderStyle.THIN);
                    Font font = workbook.createFont();
                    font.setFontName("微软雅黑");
                    font.setBold(true);
                    errorHeadStyle.setFont(font);
                }
            }
            headCell.setCellStyle(errorHeadStyle);
        } else {
            // 数据行：追加聚合错误说明
            Integer relativeRowIndex = context.getRelativeRowIndex();
            // relativeRowIndex 为 0 对应数据首行，在 Excel 物理行中通常为 rowIndex
            // 尝试按 relativeRowIndex 匹配或者按物理行号遍历
            String summary = findErrorSummary(context.getRowIndex(), relativeRowIndex);

            Cell errorCell = row.createCell(lastCol);
            errorCell.setCellValue(summary != null ? summary : "");

            if (errorContentStyle == null) {
                errorContentStyle = workbook.createCellStyle();
                if (lastCol > 0 && row.getCell(lastCol - 1) != null) {
                    errorContentStyle.cloneStyleFrom(row.getCell(lastCol - 1).getCellStyle());
                } else {
                    errorContentStyle.setVerticalAlignment(VerticalAlignment.CENTER);
                    errorContentStyle.setBorderTop(BorderStyle.THIN);
                    errorContentStyle.setBorderBottom(BorderStyle.THIN);
                    errorContentStyle.setBorderLeft(BorderStyle.THIN);
                    errorContentStyle.setBorderRight(BorderStyle.THIN);
                }
            }
            errorCell.setCellStyle(errorContentStyle);
        }
    }

    @Override
    public void afterCellDispose(CellWriteHandlerContext context) {
        if (context == null || context.getCell() == null || Boolean.TRUE.equals(context.getHead())) {
            return;
        }

        Cell cell = context.getCell();
        int colIndex = cell.getColumnIndex();
        String errorMsg = findCellError(context.getRowIndex(), context.getRelativeRowIndex(), colIndex, context.getHeadData());

        if (errorMsg != null) {
            Workbook workbook = cell.getSheet().getWorkbook();
            if (highlightedErrorStyle == null) {
                highlightedErrorStyle = workbook.createCellStyle();
                highlightedErrorStyle.cloneStyleFrom(cell.getCellStyle());
                highlightedErrorStyle.setFillForegroundColor(IndexedColors.ROSE.getIndex());
                highlightedErrorStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            }
            cell.setCellStyle(highlightedErrorStyle);

            // 挂载悬浮批注
            attachCellComment(cell, errorMsg);
        }
    }

    private String findErrorSummary(Integer physicalRowIndex, Integer relativeRowIndex) {
        // 优先按 relativeRowIndex 映射到去重后的原始错误行号（用于差错隔离纠错清单导出）
        if (relativeRowIndex != null && relativeRowIndex < distinctRowIndices.size()) {
            int originalRow = distinctRowIndices.get(relativeRowIndex);
            if (errorSummaryByRowIndex.containsKey(originalRow)) {
                return errorSummaryByRowIndex.get(originalRow);
            }
        }
        // 兜底：按物理行号匹配
        if (physicalRowIndex != null && errorSummaryByRowIndex.containsKey(physicalRowIndex)) {
            return errorSummaryByRowIndex.get(physicalRowIndex);
        }
        if (physicalRowIndex != null && errorSummaryByRowIndex.containsKey(physicalRowIndex + 1)) {
            return errorSummaryByRowIndex.get(physicalRowIndex + 1);
        }
        return null;
    }

    private String findCellError(Integer physicalRowIndex, Integer relativeRowIndex, int colIndex, org.apache.fesod.sheet.metadata.Head headData) {
        // 1. 优先按 relativeRowIndex 映射到原始行号
        if (relativeRowIndex != null && relativeRowIndex < distinctRowIndices.size()) {
            int originalRow = distinctRowIndices.get(relativeRowIndex);
            String msg = errorByRowAndCol.get(originalRow + "_" + colIndex);
            if (msg != null) return msg;
            if (headData != null && headData.getFieldName() != null) {
                msg = errorByRowAndField.get(originalRow + "_" + headData.getFieldName());
                if (msg != null) return msg;
            }
        }
        // 2. 兜底：按行列索引检查
        if (physicalRowIndex != null) {
            String msg = errorByRowAndCol.get(physicalRowIndex + "_" + colIndex);
            if (msg != null) return msg;
            msg = errorByRowAndCol.get((physicalRowIndex + 1) + "_" + colIndex);
            if (msg != null) return msg;
        }
        // 3. 兜底：按字段名检查
        if (headData != null && headData.getFieldName() != null && physicalRowIndex != null) {
            String fieldName = headData.getFieldName();
            String msg = errorByRowAndField.get(physicalRowIndex + "_" + fieldName);
            if (msg != null) return msg;
            msg = errorByRowAndField.get((physicalRowIndex + 1) + "_" + fieldName);
            if (msg != null) return msg;
        }
        return null;
    }

    private void attachCellComment(Cell cell, String commentText) {
        try {
            Sheet sheet = cell.getSheet();
            Drawing<?> drawing = sheet.getDrawingPatriarch();
            if (drawing == null) {
                drawing = sheet.createDrawingPatriarch();
            }
            CreationHelper factory = sheet.getWorkbook().getCreationHelper();
            ClientAnchor anchor = factory.createClientAnchor();
            anchor.setCol1(cell.getColumnIndex());
            anchor.setCol2(cell.getColumnIndex() + 2);
            anchor.setRow1(cell.getRowIndex());
            anchor.setRow2(cell.getRowIndex() + 2);

            Comment comment = drawing.createCellComment(anchor);
            comment.setString(new XSSFRichTextString(commentText));
            comment.setAuthor("System");
            cell.setCellComment(comment);
        } catch (Exception ignored) {
            // 忽略批注挂载异常，保障数据正常写出
        }
    }
}
