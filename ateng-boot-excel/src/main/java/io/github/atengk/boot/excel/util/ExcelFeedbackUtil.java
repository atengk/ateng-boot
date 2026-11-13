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

            // 2. 构建工作簿级无损错误样式克隆缓存（保留原数字/日期格式与边框字体，叠加浅粉红警告背景）
            Map<Short, CellStyle> errorStyleCache = new HashMap<>();

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

            // 4. 逐个单元格无损克隆并应用标红背景与插入批注
            for (CellErrorAggregate aggregate : cellErrorMap.values()) {
                Row row = sheet.getRow(aggregate.rowIndex());
                if (row == null) {
                    row = sheet.createRow(aggregate.rowIndex());
                }

                Cell cell = row.getCell(aggregate.columnIndex());
                if (cell == null) {
                    cell = row.createCell(aggregate.columnIndex());
                }

                // 无损保留原单元格样式并填充标红警示底纹
                cell.setCellStyle(getOrCreateErrorHighlightStyle(workbook, cell.getCellStyle(), errorStyleCache));

                // 防止原单元格已存在批注时 POI 抛出重复批注异常
                if (cell.getCellComment() != null) {
                    cell.removeCellComment();
                }

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
     * 基于工作簿级样式缓存无损克隆原单元格样式并叠加浅粉红警示背景色
     *
     * @param workbook      目标工作簿
     * @param originalStyle 原单元格样式（可为 null）
     * @param styleCache    工作簿级原样式索引到错误样式的缓存映射
     * @return 带警示底纹且完整保留原数字/日期格式与边框字体的单元格样式
     */
    public static CellStyle getOrCreateErrorHighlightStyle(Workbook workbook,
                                                           CellStyle originalStyle,
                                                           Map<Short, CellStyle> styleCache) {
        Assert.notNull(workbook, "Workbook 不可为空");
        Assert.notNull(styleCache, "styleCache 不可为空");
        short cacheKey = (originalStyle != null) ? originalStyle.getIndex() : (short) -1;
        return styleCache.computeIfAbsent(cacheKey, k -> {
            CellStyle cloned = workbook.createCellStyle();
            if (originalStyle != null) {
                cloned.cloneStyleFrom(originalStyle);
            }
            cloned.setFillForegroundColor(IndexedColors.ROSE.getIndex());
            cloned.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            return cloned;
        });
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
