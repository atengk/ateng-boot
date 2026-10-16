package io.github.atengk.boot.excel.handler;

import io.github.atengk.boot.excel.annotation.ExcelPrompt;
import org.apache.fesod.sheet.metadata.property.ExcelContentProperty;
import org.apache.fesod.sheet.write.handler.CellWriteHandler;
import org.apache.fesod.sheet.write.handler.context.CellWriteHandlerContext;
import org.apache.poi.ss.usermodel.*;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模板输入提示与表头批注写入处理器
 * <p>
 * 拦截表头单元格渲染事件，自动识别字段上的 {@link ExcelPrompt} 注解，
 * 在表头单元格嵌入悬浮批注（Comment），指导用户按规范录入。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-16
 */
public class PromptHeadWriteHandler implements CellWriteHandler {

    private static final String DEFAULT_COMMENT_AUTHOR = "系统规范";
    private static final int DEFAULT_COMMENT_WIDTH_COLS = 3;
    private static final int DEFAULT_COMMENT_HEIGHT_ROWS = 2;

    private static final Map<Field, Optional<ExcelPrompt>> PROMPT_CACHE = new ConcurrentHashMap<>();

    @Override
    public void afterCellDispose(CellWriteHandlerContext context) {
        // 1. 仅对表头单元格执行处理
        if (context == null || !Boolean.TRUE.equals(context.getHead())) {
            return;
        }

        if (context.getWriteSheetHolder() == null) {
            return;
        }

        ExcelContentProperty contentProperty = context.getExcelContentProperty();
        if (contentProperty == null || contentProperty.getField() == null) {
            return;
        }

        Field field = contentProperty.getField();
        ExcelPrompt prompt = PROMPT_CACHE
                .computeIfAbsent(field, f -> Optional.ofNullable(f.getAnnotation(ExcelPrompt.class)))
                .orElse(null);

        if (prompt == null) {
            return;
        }

        Cell cell = context.getCell();
        Sheet sheet = context.getWriteSheetHolder().getSheet();
        if (cell == null || sheet == null) {
            return;
        }

        // 2. 表头嵌入悬浮批注（Comment）
        Workbook workbook = sheet.getWorkbook();
        CreationHelper factory = workbook.getCreationHelper();
        Drawing<?> drawing = sheet.getDrawingPatriarch() != null
                ? sheet.getDrawingPatriarch()
                : sheet.createDrawingPatriarch();

        int col = cell.getColumnIndex();
        int row = cell.getRowIndex();

        ClientAnchor anchor = factory.createClientAnchor();
        anchor.setCol1(col);
        anchor.setCol2(col + DEFAULT_COMMENT_WIDTH_COLS);
        anchor.setRow1(row);
        anchor.setRow2(row + DEFAULT_COMMENT_HEIGHT_ROWS);

        Comment comment = drawing.createCellComment(anchor);
        String commentText = (prompt.title() != null && !prompt.title().isBlank())
                ? "[" + prompt.title() + "]\n" + prompt.value()
                : prompt.value();
        comment.setString(factory.createRichTextString(commentText));
        comment.setAuthor(DEFAULT_COMMENT_AUTHOR);
        cell.setCellComment(comment);
    }
}
