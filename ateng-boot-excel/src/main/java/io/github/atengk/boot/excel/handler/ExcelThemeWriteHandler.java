package io.github.atengk.boot.excel.handler;

import io.github.atengk.boot.excel.enums.ExcelTheme;
import org.apache.fesod.sheet.write.handler.context.CellWriteHandlerContext;
import org.apache.fesod.sheet.write.metadata.holder.WriteWorkbookHolder;
import org.apache.fesod.sheet.write.metadata.style.WriteCellStyle;
import org.apache.fesod.sheet.write.metadata.style.WriteFont;
import org.apache.fesod.sheet.write.style.HorizontalCellStyleStrategy;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.DefaultIndexedColorMap;
import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 企业级主题与斑马纹样式写入处理器
 * <p>
 * 基于 Apache Fesod 的 {@link HorizontalCellStyleStrategy} 构建，统一挂载现代字体、细实线网格边框、
 * 居中排版以及偶数/奇数行浅底纹斑马纹交替渲染。支持标准色板与高精度 24-bit RGB 色阶。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-17
 */
public class ExcelThemeWriteHandler extends HorizontalCellStyleStrategy {

    public static final String DEFAULT_FONT_NAME = "微软雅黑";

    private final ExcelTheme theme;
    private final boolean zebraStripe;
    private final String fontName;

    /**
     * 工作簿级高精度 RGB 样式缓存（避免重复创建超过 64k 限制）
     */
    private final Map<Workbook, CellStyle> headStyleCache = new HashMap<>(4);
    private final Map<Workbook, CellStyle> zebraStyleCache = new HashMap<>(4);

    public ExcelThemeWriteHandler() {
        this(ExcelTheme.BUSINESS_BLUE, true, DEFAULT_FONT_NAME);
    }

    public ExcelThemeWriteHandler(ExcelTheme theme) {
        this(theme, true, DEFAULT_FONT_NAME);
    }

    public ExcelThemeWriteHandler(ExcelTheme theme, boolean zebraStripe) {
        this(theme, zebraStripe, DEFAULT_FONT_NAME);
    }

    public ExcelThemeWriteHandler(ExcelTheme theme, boolean zebraStripe, String fontName) {
        super();
        this.theme = (theme != null) ? theme : ExcelTheme.BUSINESS_BLUE;
        this.zebraStripe = zebraStripe;
        this.fontName = (fontName != null && !fontName.isBlank()) ? fontName : DEFAULT_FONT_NAME;

        // 1. 初始化表头样式
        setHeadWriteCellStyle(buildHeadStyle(this.theme, this.fontName));

        // 2. 初始化正文内容样式列表（支持斑马纹交替）
        setContentWriteCellStyleList(buildContentStyles(this.theme, this.zebraStripe, this.fontName));
    }

    @Override
    public void afterCellDispose(CellWriteHandlerContext context) {
        super.afterCellDispose(context);
        if (context == null || context.getCell() == null || !theme.hasCustomRgb() || context.getFirstCellData() == null) {
            return;
        }

        Cell cell = context.getCell();
        Workbook workbook = cell.getSheet().getWorkbook();
        if (!(workbook instanceof org.apache.poi.xssf.usermodel.XSSFWorkbook) && !(workbook instanceof org.apache.poi.xssf.streaming.SXSSFWorkbook)) {
            return;
        }

        boolean isHead = Boolean.TRUE.equals(context.getHead());
        WriteWorkbookHolder holder = context.getWriteWorkbookHolder();

        if (isHead && theme.getHeaderRgb() != null) {
            CellStyle customHeadStyle = headStyleCache.computeIfAbsent(workbook, wb -> {
                CellStyle baseStyle = holder != null
                        ? holder.createCellStyle(context.getFirstCellData().getWriteCellStyle(), null, context.getFirstCellData().getType())
                        : null;
                CellStyle style = wb.createCellStyle();
                if (baseStyle != null) {
                    style.cloneStyleFrom(baseStyle);
                }
                if (style instanceof XSSFCellStyle xssfStyle) {
                    xssfStyle.setFillForegroundColor(new XSSFColor(theme.getHeaderRgb(), new DefaultIndexedColorMap()));
                    xssfStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                }
                return style;
            });
            cell.setCellStyle(customHeadStyle);
            context.setIgnoreFillStyle(Boolean.TRUE);
        } else if (!isHead && zebraStripe && theme.getZebraRgb() != null) {
            Integer relativeRowIndex = context.getRelativeRowIndex();
            if (relativeRowIndex != null && relativeRowIndex % 2 == 1) {
                CellStyle customZebraStyle = zebraStyleCache.computeIfAbsent(workbook, wb -> {
                    CellStyle baseStyle = holder != null
                            ? holder.createCellStyle(context.getFirstCellData().getWriteCellStyle(), null, context.getFirstCellData().getType())
                            : null;
                    CellStyle style = wb.createCellStyle();
                    if (baseStyle != null) {
                        style.cloneStyleFrom(baseStyle);
                    }
                    if (style instanceof XSSFCellStyle xssfStyle) {
                        xssfStyle.setFillForegroundColor(new XSSFColor(theme.getZebraRgb(), new DefaultIndexedColorMap()));
                        xssfStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
                    }
                    return style;
                });
                cell.setCellStyle(customZebraStyle);
                context.setIgnoreFillStyle(Boolean.TRUE);
            }
        }
    }

    private static WriteCellStyle buildHeadStyle(ExcelTheme theme, String fontName) {
        WriteCellStyle headStyle = new WriteCellStyle();
        if (theme == ExcelTheme.MINIMAL_CLEAN) {
            headStyle.setFillPatternType(FillPatternType.NO_FILL);
        } else {
            headStyle.setFillPatternType(FillPatternType.SOLID_FOREGROUND);
            headStyle.setFillForegroundColor(theme.getHeaderColorIndex());
        }

        headStyle.setHorizontalAlignment(HorizontalAlignment.CENTER);
        headStyle.setVerticalAlignment(VerticalAlignment.CENTER);
        headStyle.setWrapped(true);

        // 细实线边框
        headStyle.setBorderTop(BorderStyle.THIN);
        headStyle.setBorderBottom(BorderStyle.THIN);
        headStyle.setBorderLeft(BorderStyle.THIN);
        headStyle.setBorderRight(BorderStyle.THIN);

        // 11pt 加粗字体（统一黑色/系统默认色，与全量模版统一）
        WriteFont headFont = new WriteFont();
        headFont.setFontName(fontName);
        headFont.setFontHeightInPoints((short) 11);
        headFont.setBold(true);
        headStyle.setWriteFont(headFont);

        return headStyle;
    }

    private static List<WriteCellStyle> buildContentStyles(ExcelTheme theme, boolean zebraStripe, String fontName) {
        List<WriteCellStyle> list = new ArrayList<>(2);
        list.add(createContentStyle(theme.getContentBaseColorIndex(), fontName));
        if (zebraStripe && theme != ExcelTheme.MINIMAL_CLEAN) {
            list.add(createContentStyle(theme.getZebraColorIndex(), fontName));
        }
        return list;
    }

    private static WriteCellStyle createContentStyle(short foregroundColor, String fontName) {
        WriteCellStyle style = new WriteCellStyle();
        style.setFillPatternType(FillPatternType.SOLID_FOREGROUND);
        style.setFillForegroundColor(foregroundColor);
        style.setVerticalAlignment(VerticalAlignment.CENTER);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);

        WriteFont font = new WriteFont();
        font.setFontName(fontName);
        font.setFontHeightInPoints((short) 10);
        style.setWriteFont(font);
        return style;
    }

    public ExcelTheme getTheme() {
        return theme;
    }

    public boolean isZebraStripe() {
        return zebraStripe;
    }

    public String getFontName() {
        return fontName;
    }
}
