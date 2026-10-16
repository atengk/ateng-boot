package io.github.atengk.boot.excel.util;

import io.github.atengk.boot.excel.model.ExcelErrorRow;
import org.apache.poi.ss.usermodel.*;
import org.springframework.util.Assert;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.*;

/**
 * Excel 校验错误可视化反馈工具类
 * <p>
 * 基于 Apache POI 原生能力在上传的原始 Excel 工作表中对校验失败的单元格填充警示背景色，
 * 并嵌入浮动批注（Comment）详细提示错误原因，供终端用户导出并直观排查修复。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-16
 */
public final class ExcelFeedbackUtil {

    private ExcelFeedbackUtil() {
        // 工具类私有构造
    }

    /**
     * 将错误信息标红并生成带批注的反馈 Excel 文件
     *
     * @param originalExcelStream 原始上传的 Excel 输入流（不可为空）
     * @param outputStream        标注后的反馈 Excel 输出流（不可为空）
     * @param errorRows           校验错误行列表（为空时原样复制输出）
     * @throws IOException IO 读写异常
     */
    public static void renderErrorFeedback(InputStream originalExcelStream,
                                          OutputStream outputStream,
                                          List<ExcelErrorRow> errorRows) throws IOException {
        Assert.notNull(originalExcelStream, "原始 Excel 输入流不可为空");
        Assert.notNull(outputStream, "输出流不可为空");

        try (Workbook workbook = WorkbookFactory.create(originalExcelStream)) {
            if (errorRows == null || errorRows.isEmpty()) {
                workbook.write(outputStream);
                return;
            }

            // 1. 获取首个工作表并安全获取或创建批注绘制器（防止覆盖已有图元）
            Sheet sheet = workbook.getSheetAt(0);
            Drawing<?> drawing = sheet.getDrawingPatriarch() != null
                    ? sheet.getDrawingPatriarch()
                    : sheet.createDrawingPatriarch();
            CreationHelper factory = workbook.getCreationHelper();

            // 2. 创建复用的错误高亮背景色样式（浅粉红色，柔和且醒目）
            CellStyle errorStyle = workbook.createCellStyle();
            errorStyle.setFillForegroundColor(IndexedColors.ROSE.getIndex());
            errorStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            // 3. 按强类型坐标值对象分组（CellCoord -> CellErrorAggregate）
            Map<CellCoord, CellErrorAggregate> cellErrorMap = new LinkedHashMap<>();
            for (ExcelErrorRow error : errorRows) {
                if (error.getRowIndex() == null) {
                    continue;
                }
                int r = error.getRowIndex() - 1; // 转换为 0-based POI 索引
                int c = (error.getColumnIndex() != null && error.getColumnIndex() >= 0) ? error.getColumnIndex() : 0;
                CellCoord coord = new CellCoord(r, c);

                cellErrorMap.computeIfAbsent(coord, k -> new CellErrorAggregate(r, c))
                        .addMessage(error.getMessage());
            }

            // 4. 逐个单元格应用标红背景与插入批注
            for (CellErrorAggregate aggregate : cellErrorMap.values()) {
                Row row = sheet.getRow(aggregate.rowIndex());
                if (row == null) {
                    row = sheet.createRow(aggregate.rowIndex());
                }

                Cell cell = row.getCell(aggregate.columnIndex());
                if (cell == null) {
                    cell = row.createCell(aggregate.columnIndex());
                }

                // 填充标红样式
                cell.setCellStyle(errorStyle);

                // 创建并绑定批注锚点
                ClientAnchor anchor = factory.createClientAnchor();
                anchor.setCol1(aggregate.columnIndex());
                anchor.setCol2(aggregate.columnIndex() + 3);
                anchor.setRow1(aggregate.rowIndex());
                anchor.setRow2(aggregate.rowIndex() + 2);

                Comment comment = drawing.createCellComment(anchor);
                comment.setString(factory.createRichTextString(aggregate.getFormattedMessage()));
                comment.setAuthor("校验系统");
                cell.setCellComment(comment);
            }

            // 5. 写出结果到目标流
            workbook.write(outputStream);
        }
    }

    /**
     * 单元格坐标强类型值对象
     */
    private record CellCoord(int row, int col) {}

    private static class CellErrorAggregate {
        private final int rowIndex;
        private final int columnIndex;
        private final List<String> messages = new ArrayList<>();

        public CellErrorAggregate(int rowIndex, int columnIndex) {
            this.rowIndex = rowIndex;
            this.columnIndex = columnIndex;
        }

        public int rowIndex() {
            return rowIndex;
        }

        public int columnIndex() {
            return columnIndex;
        }

        public void addMessage(String msg) {
            if (msg != null && !msg.isBlank()) {
                messages.add(msg);
            }
        }

        public String getFormattedMessage() {
            if (messages.isEmpty()) {
                return "数据格式有误";
            }
            if (messages.size() == 1) {
                return messages.get(0);
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < messages.size(); i++) {
                sb.append(i + 1).append(". ").append(messages.get(i));
                if (i < messages.size() - 1) {
                    sb.append("\n");
                }
            }
            return sb.toString();
        }
    }
}
