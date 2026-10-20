package io.github.atengk.boot.excel.builder;

import io.github.atengk.boot.excel.annotation.ExcelTheme;
import io.github.atengk.boot.excel.enums.ExcelThemeEnum;
import io.github.atengk.boot.excel.converter.SmartImageConverter;
import io.github.atengk.boot.excel.handler.ColumnLockSheetWriteHandler;
import io.github.atengk.boot.excel.handler.ExcelThemeWriteHandler;
import io.github.atengk.boot.excel.handler.FormulaProtectionCellWriteHandler;
import io.github.atengk.boot.excel.handler.I18nHeadWriteHandler;
import io.github.atengk.boot.excel.handler.ImageRowHeightWriteHandler;
import io.github.atengk.boot.excel.handler.MergeCellWriteHandler;
import io.github.atengk.boot.excel.handler.PromptHeadWriteHandler;
import io.github.atengk.boot.excel.handler.SensitiveCellWriteHandler;
import io.github.atengk.boot.excel.handler.SmartAutoColumnWidthWriteHandler;
import io.github.atengk.boot.excel.handler.WatermarkSheetWriteHandler;
import io.github.atengk.boot.excel.properties.ExcelStyleProperties;
import io.github.atengk.boot.excel.util.ExcelUtil;
import org.apache.fesod.sheet.ExcelWriter;
import org.apache.fesod.sheet.FesodSheet;
import org.apache.fesod.sheet.write.builder.ExcelWriterSheetBuilder;
import org.apache.fesod.sheet.write.metadata.WriteSheet;
import org.springframework.util.Assert;

import java.io.Closeable;
import java.io.OutputStream;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.BiConsumer;

/**
 * Excel 链式 Fluent 构建器
 * <p>
 * 支持多 Sheet 连续编排、混合无实体动态表头与实体类导出、自动注入企业级拦截器与安全资源释放。
 * 实现了 {@link AutoCloseable} 与 {@link Closeable}，天然支持 try-with-resources 安全编程。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-16
 */
public class ExcelBuilder implements AutoCloseable, Closeable {

    private final ExcelWriter excelWriter;
    private final BiConsumer<ExcelWriterSheetBuilder, Class<?>> sheetConfigurator;
    private final I18nHeadWriteHandler i18nHeadWriteHandler = new I18nHeadWriteHandler();
    private int currentSheetNo = 0;
    private boolean finished = false;

    // 链式自定义样式状态
    private ExcelThemeEnum theme;
    private Boolean zebraStripe;
    private Boolean autoColumnWidth;
    private Integer minColumnWidth;
    private Integer maxColumnWidth;
    private String fontName;
    private boolean styleDisabled = false;
    private String watermarkText;
    private String protectionPassword;

    /**
     * 基于输出流构造 Fluent 构建器
     *
     * @param outputStream 目标输出流（不可为空）
     */
    public ExcelBuilder(OutputStream outputStream) {
        this(outputStream, null);
    }

    /**
     * 基于输出流与 Sheet 配置回调构造 Fluent 构建器
     *
     * @param outputStream      目标输出流（不可为空）
     * @param sheetConfigurator 实体 Sheet 级配置回调（如枚举转换器与下拉验证注入）
     */
    public ExcelBuilder(OutputStream outputStream, BiConsumer<ExcelWriterSheetBuilder, Class<?>> sheetConfigurator) {
        Assert.notNull(outputStream, "OutputStream 目标输出流不可为空");
        this.sheetConfigurator = sheetConfigurator;
        this.excelWriter = FesodSheet.write(outputStream)
                .registerWriteHandler(this.i18nHeadWriteHandler)
                .registerWriteHandler(new SensitiveCellWriteHandler())
                .registerWriteHandler(new PromptHeadWriteHandler())
                .registerWriteHandler(new MergeCellWriteHandler())
                .registerWriteHandler(new FormulaProtectionCellWriteHandler())
                .build();
    }

    /**
     * 流式指定导出的国际化语言环境
     *
     * @param locale 国际化语言环境
     * @return 当前构建器实例
     */
    public ExcelBuilder locale(Locale locale) {
        this.i18nHeadWriteHandler.setLocale(locale);
        return this;
    }

    /**
     * 流式指定后续 Sheet 的模版主题
     *
     * @param theme 模版主题枚举
     * @return 当前构建器实例
     */
    public ExcelBuilder theme(ExcelThemeEnum theme) {
        this.theme = theme;
        return this;
    }

    /**
     * 流式指定后续 Sheet 是否开启斑马纹交替渲染
     *
     * @param zebraStripe 是否开启斑马纹
     * @return 当前构建器实例
     */
    public ExcelBuilder zebraStripe(boolean zebraStripe) {
        this.zebraStripe = zebraStripe;
        return this;
    }

    /**
     * 流式指定后续 Sheet 是否开启智能自适应列宽
     *
     * @param autoColumnWidth 是否开启自适应列宽
     * @return 当前构建器实例
     */
    public ExcelBuilder autoColumnWidth(boolean autoColumnWidth) {
        this.autoColumnWidth = autoColumnWidth;
        return this;
    }

    /**
     * 流式指定后续 Sheet 智能自适应列宽的最小与最大字符阈值
     *
     * @param minWidth 最小列宽字符数
     * @param maxWidth 最大列宽字符数
     * @return 当前构建器实例
     */
    public ExcelBuilder autoColumnWidth(int minWidth, int maxWidth) {
        this.autoColumnWidth = true;
        this.minColumnWidth = minWidth;
        this.maxColumnWidth = maxWidth;
        return this;
    }

    /**
     * 流式指定后续 Sheet 的全局字体名称
     *
     * @param fontName 字体名称（如 "微软雅黑"）
     * @return 当前构建器实例
     */
    public ExcelBuilder fontName(String fontName) {
        this.fontName = fontName;
        return this;
    }

    /**
     * 针对当前构建器彻底停用企业级样式体系
     *
     * @return 当前构建器实例
     */
    public ExcelBuilder disableStyle() {
        this.styleDisabled = true;
        return this;
    }

    /**
     * 流式指定后续 Sheet 的防拍照半透明暗水印文本
     *
     * @param watermarkText 水印文本内容
     * @return 当前构建器实例
     */
    public ExcelBuilder watermark(String watermarkText) {
        this.watermarkText = watermarkText;
        return this;
    }

    /**
     * 流式指定后续 Sheet 的只读锁定保护密码
     *
     * @param protectionPassword 只读锁定保护密码
     * @return 当前构建器实例
     */
    public ExcelBuilder protect(String protectionPassword) {
        this.protectionPassword = protectionPassword;
        return this;
    }

    /**
     * 写入一个强类型实体模型 Sheet（自动递增 Sheet 编号）
     *
     * @param sheetName 工作表名称
     * @param headClass 实体模型 Class
     * @param data      待写入数据集合（为 null 或空时依然输出表头空表）
     * @param <T>       实体泛型
     * @return 当前构建器实例
     */
    public <T> ExcelBuilder sheet(String sheetName, Class<T> headClass, Collection<T> data) {
        return sheet(currentSheetNo++, sheetName, headClass, data);
    }

    /**
     * 写入一个指定编号的强类型实体模型 Sheet
     *
     * @param sheetNo   Sheet 编号（0-based）
     * @param sheetName 工作表名称
     * @param headClass 实体模型 Class
     * @param data      待写入数据集合（为 null 或空时依然输出表头空表）
     * @param <T>       实体泛型
     * @return 当前构建器实例
     */
    public <T> ExcelBuilder sheet(Integer sheetNo, String sheetName, Class<T> headClass, Collection<T> data) {
        Assert.notNull(headClass, "headClass 实体类型不可为空");
        ExcelWriterSheetBuilder sheetBuilder = FesodSheet.writerSheet(sheetNo, sheetName).head(headClass);
        if (sheetConfigurator != null) {
            sheetConfigurator.accept(sheetBuilder, headClass);
        }
        if (ImageRowHeightWriteHandler.hasExcelImage(headClass)) {
            sheetBuilder.registerWriteHandler(new ImageRowHeightWriteHandler(headClass));
            sheetBuilder.registerConverter(new SmartImageConverter());
        }
        if ((this.watermarkText != null && !this.watermarkText.isBlank()) || (this.protectionPassword != null && !this.protectionPassword.isBlank())) {
            sheetBuilder.registerWriteHandler(new WatermarkSheetWriteHandler(this.watermarkText, this.protectionPassword));
        }
        applyStyle(sheetBuilder, headClass);
        if (ColumnLockSheetWriteHandler.hasColumnLock(headClass)) {
            sheetBuilder.registerWriteHandler(new ColumnLockSheetWriteHandler(headClass, this.protectionPassword));
        }
        WriteSheet writeSheet = sheetBuilder.build();
        Collection<T> writeData = (data != null) ? data : Collections.emptyList();
        excelWriter.write(writeData, writeSheet);
        resetSheetStyle();
        return this;
    }

    /**
     * 写入一个无实体动态表头 Sheet（自动递增 Sheet 编号）
     *
     * @param sheetName 工作表名称
     * @param headList  动态表头定义（支持 {@code List<String>} 或 {@code List<List<String>>}）
     * @param dataList  动态行数据（支持 {@code List<List<?>>} 或 {@code List<Map<String, ?>>}）
     * @return 当前构建器实例
     */
    public ExcelBuilder sheetDynamic(String sheetName, List<?> headList, List<?> dataList) {
        return sheetDynamic(currentSheetNo++, sheetName, headList, dataList);
    }

    /**
     * 写入一个指定编号的无实体动态表头 Sheet
     *
     * @param sheetNo   Sheet 编号（0-based）
     * @param sheetName 工作表名称
     * @param headList  动态表头定义
     * @param dataList  动态行数据
     * @return 当前构建器实例
     */
    public ExcelBuilder sheetDynamic(Integer sheetNo, String sheetName, List<?> headList, List<?> dataList) {
        List<List<String>> fesodHead = ExcelUtil.normalizeHead(headList);
        List<?> normalizedData = ExcelUtil.normalizeData(dataList, headList);
        ExcelWriterSheetBuilder sheetBuilder = FesodSheet.writerSheet(sheetNo, sheetName).head(fesodHead);
        if ((this.watermarkText != null && !this.watermarkText.isBlank()) || (this.protectionPassword != null && !this.protectionPassword.isBlank())) {
            sheetBuilder.registerWriteHandler(new WatermarkSheetWriteHandler(this.watermarkText, this.protectionPassword));
        }
        applyStyle(sheetBuilder, null);
        WriteSheet writeSheet = sheetBuilder.build();
        Collection<?> writeData = (normalizedData != null) ? (Collection<?>) normalizedData : Collections.emptyList();
        excelWriter.write(writeData, writeSheet);
        resetSheetStyle();
        return this;
    }

    private void resetSheetStyle() {
        this.theme = null;
        this.zebraStripe = null;
        this.autoColumnWidth = null;
        this.minColumnWidth = null;
        this.maxColumnWidth = null;
        this.fontName = null;
        this.watermarkText = null;
        this.protectionPassword = null;
    }

    private void applyStyle(ExcelWriterSheetBuilder sheetBuilder, Class<?> headClass) {
        if (styleDisabled) {
            return;
        }

        ExcelStyleProperties styleProps = ExcelUtil.getStyleProperties();
        ExcelTheme annotation = (headClass != null) ? headClass.getAnnotation(ExcelTheme.class) : null;

        if (annotation != null && !annotation.enabled() && this.theme == null) {
            return;
        }
        if (annotation == null && !styleProps.isEnabled() && this.theme == null) {
            return;
        }

        ExcelThemeEnum finalTheme;
        if (this.theme != null) {
            finalTheme = this.theme;
        } else if (annotation != null) {
            if (!annotation.value().isBlank()) {
                finalTheme = ExcelThemeEnum.fromName(annotation.value());
            } else {
                finalTheme = annotation.theme();
            }
        } else {
            finalTheme = ExcelThemeEnum.fromName(styleProps.getTheme());
        }

        boolean finalZebra;
        if (this.zebraStripe != null) {
            finalZebra = this.zebraStripe;
        } else if (annotation != null) {
            finalZebra = annotation.zebraStripe();
        } else {
            finalZebra = styleProps.isZebraStripe();
        }

        String finalFont;
        if (this.fontName != null && !this.fontName.isBlank()) {
            finalFont = this.fontName;
        } else if (annotation != null && !annotation.fontName().isBlank()) {
            finalFont = annotation.fontName();
        } else {
            finalFont = styleProps.getFontName();
        }

        sheetBuilder.registerWriteHandler(new ExcelThemeWriteHandler(finalTheme, finalZebra, finalFont));

        boolean finalAutoWidth;
        if (this.autoColumnWidth != null) {
            finalAutoWidth = this.autoColumnWidth;
        } else if (annotation != null) {
            finalAutoWidth = annotation.autoColumnWidth();
        } else {
            finalAutoWidth = styleProps.isAutoColumnWidth();
        }

        if (finalAutoWidth) {
            int minW = (this.minColumnWidth != null) ? this.minColumnWidth
                    : ((annotation != null && annotation.minColumnWidth() > 0) ? annotation.minColumnWidth() : styleProps.getMinColumnWidth());
            int maxW = (this.maxColumnWidth != null) ? this.maxColumnWidth
                    : ((annotation != null && annotation.maxColumnWidth() > 0) ? annotation.maxColumnWidth() : styleProps.getMaxColumnWidth());
            int padding = (styleProps.getPaddingColumnWidth() > 0) ? styleProps.getPaddingColumnWidth() : SmartAutoColumnWidthWriteHandler.DEFAULT_PADDING;
            sheetBuilder.registerWriteHandler(new SmartAutoColumnWidthWriteHandler(minW, maxW, padding));
        }
    }

    /**
     * 刷新缓冲区并释放所有写出资源
     */
    public void finish() {
        if (!finished) {
            excelWriter.finish();
            finished = true;
        }
    }

    @Override
    public void close() {
        finish();
    }
}
