package io.github.atengk.boot.excel.streaming;

import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.excel.annotation.ExcelTheme;
import io.github.atengk.boot.excel.converter.EnumMappingCache;
import io.github.atengk.boot.excel.converter.ExcelEnumConverter;
import io.github.atengk.boot.excel.converter.SmartImageConverter;
import io.github.atengk.boot.excel.enums.ExcelThemeEnum;
import io.github.atengk.boot.excel.handler.*;
import io.github.atengk.boot.excel.properties.ExcelStyleProperties;
import io.github.atengk.boot.excel.util.ExcelUtil;
import org.apache.fesod.sheet.ExcelWriter;
import org.apache.fesod.sheet.FesodSheet;
import org.apache.fesod.sheet.enums.CellDataTypeEnum;
import org.apache.fesod.sheet.metadata.AbstractParameterBuilder;
import org.apache.fesod.sheet.write.builder.ExcelWriterBuilder;
import org.apache.fesod.sheet.write.builder.ExcelWriterSheetBuilder;
import org.apache.fesod.sheet.write.metadata.WriteSheet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;

import java.io.Closeable;
import java.io.OutputStream;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * 海量数据流式分页导出执行模板
 * <p>
 * 提供解耦内存爆炸风险的恒定低内存流式写出能力。支持标准页码分页与主键游标分页两种拉取驱动；
 * 单工作表行数达到配置上限（默认 1,000,000 行，Excel 极限为 1,048,576 行）时，自动无缝跨 Sheet 轮转分割，
 * 新工作表 100% 自动重绘完整表头并无缝继承全部样式、脱敏与安全拦截器。
 * 实现了 {@link AutoCloseable} 与 {@link Closeable}，严格闭环底层物理流生命周期。
 * </p>
 *
 * @param <T> 导出实体模型类型
 * @author Ateng
 * @since 2026-10-20
 */
public class StreamingPageExportTemplate<T> implements AutoCloseable, Closeable {

    private static final Logger log = LoggerFactory.getLogger(StreamingPageExportTemplate.class);

    private static final int EXCEL_MAX_SHEET_ROWS = 1_048_570;

    private final OutputStream outputStream;
    private final Class<T> headClass;

    // 配置项
    private String baseSheetName = "Sheet1";
    private int maxRowsPerSheet = 1_000_000;
    private int defaultPageSize = 1000;
    private SheetNameFormatter sheetNameFormatter = SheetNameFormatter.DEFAULT;
    private BiConsumer<ExcelWriterSheetBuilder, Class<?>> sheetConfigurator;

    // 样式与安全状态
    private ExcelThemeEnum theme;
    private Boolean zebraStripe;
    private Boolean autoColumnWidth;
    private Integer minColumnWidth;
    private Integer maxColumnWidth;
    private String fontName;
    private boolean styleDisabled = false;
    private String watermarkText;
    private String protectionPassword;

    // 运行态状态
    private ExcelWriter excelWriter;
    private WriteSheet currentWriteSheet;
    private int currentSheetIndex = 0;
    private long currentSheetRowCount = 0;
    private long totalExportedCount = 0;
    private boolean firstSheetInitialized = false;
    private boolean finished = false;

    /**
     * 构造流式分页导出模板实例
     *
     * @param outputStream 目标输出流（不可为空）
     * @param headClass    导出的实体类型 Class（不可为空）
     */
    public StreamingPageExportTemplate(OutputStream outputStream, Class<T> headClass) {
        Assert.notNull(outputStream, "OutputStream 目标输出流不可为空");
        Assert.notNull(headClass, "headClass 导出的实体 Class 类型不可为空");
        this.outputStream = outputStream;
        this.headClass = headClass;
    }

    // ==================== 链式配置方法 ====================

    /**
     * 设置基础工作表名称（默认为 "Sheet1"）
     *
     * @param baseSheetName 基础工作表名称
     * @return 当前模板实例
     */
    public StreamingPageExportTemplate<T> baseSheetName(String baseSheetName) {
        if (baseSheetName != null && !baseSheetName.isBlank()) {
            this.baseSheetName = baseSheetName;
        }
        return this;
    }

    /**
     * 设置单个工作表最大允许写入行数（超限时自动换 Sheet，必须介于 1 与 1,048,570 之间）
     *
     * @param maxRowsPerSheet 单表最大行数
     * @return 当前模板实例
     */
    public StreamingPageExportTemplate<T> maxRowsPerSheet(int maxRowsPerSheet) {
        Assert.isTrue(maxRowsPerSheet > 0, "maxRowsPerSheet 必须大于 0");
        this.maxRowsPerSheet = Math.min(maxRowsPerSheet, EXCEL_MAX_SHEET_ROWS);
        return this;
    }

    /**
     * 设置默认分页步长大小（默认为 1000）
     *
     * @param defaultPageSize 默认分页大小
     * @return 当前模板实例
     */
    public StreamingPageExportTemplate<T> defaultPageSize(int defaultPageSize) {
        Assert.isTrue(defaultPageSize > 0, "defaultPageSize 必须大于 0");
        this.defaultPageSize = defaultPageSize;
        return this;
    }

    /**
     * 设置跨 Sheet 换页时的工作表名称格式化策略
     *
     * @param sheetNameFormatter 名称格式化器
     * @return 当前模板实例
     */
    public StreamingPageExportTemplate<T> sheetNameFormatter(SheetNameFormatter sheetNameFormatter) {
        if (sheetNameFormatter != null) {
            this.sheetNameFormatter = sheetNameFormatter;
        }
        return this;
    }

    /**
     * 设置商务模版主题
     *
     * @param theme 主题枚举
     * @return 当前模板实例
     */
    public StreamingPageExportTemplate<T> theme(ExcelThemeEnum theme) {
        this.theme = theme;
        return this;
    }

    /**
     * 设置是否开启斑马纹交替底色
     *
     * @param zebraStripe 是否开启斑马纹
     * @return 当前模板实例
     */
    public StreamingPageExportTemplate<T> zebraStripe(boolean zebraStripe) {
        this.zebraStripe = zebraStripe;
        return this;
    }

    /**
     * 设置是否开启智能自适应列宽
     *
     * @param autoColumnWidth 是否开启自适应列宽
     * @return 当前模板实例
     */
    public StreamingPageExportTemplate<T> autoColumnWidth(boolean autoColumnWidth) {
        this.autoColumnWidth = autoColumnWidth;
        return this;
    }

    /**
     * 设置智能自适应列宽字符上下限
     *
     * @param minWidth 最小字符宽度
     * @param maxWidth 最大字符宽度
     * @return 当前模板实例
     */
    public StreamingPageExportTemplate<T> autoColumnWidth(int minWidth, int maxWidth) {
        this.autoColumnWidth = true;
        this.minColumnWidth = minWidth;
        this.maxColumnWidth = maxWidth;
        return this;
    }

    /**
     * 设置全局字体名称
     *
     * @param fontName 字体名称（如 "微软雅黑"）
     * @return 当前模板实例
     */
    public StreamingPageExportTemplate<T> fontName(String fontName) {
        this.fontName = fontName;
        return this;
    }

    /**
     * 禁用企业级样式体系（输出纯净裸表）
     *
     * @return 当前模板实例
     */
    public StreamingPageExportTemplate<T> disableStyle() {
        this.styleDisabled = true;
        return this;
    }

    /**
     * 设置工作表防拍照半透明暗水印文本
     *
     * @param watermarkText 水印内容
     * @return 当前模板实例
     */
    public StreamingPageExportTemplate<T> watermark(String watermarkText) {
        this.watermarkText = watermarkText;
        return this;
    }

    /**
     * 设置工作表只读保护密码
     *
     * @param protectionPassword 保护密码
     * @return 当前模板实例
     */
    public StreamingPageExportTemplate<T> protect(String protectionPassword) {
        this.protectionPassword = protectionPassword;
        return this;
    }

    /**
     * 设置实体 Sheet 自定义配置回调（用于挂载特殊拦截器与字典注入）
     *
     * @param sheetConfigurator 回调函数
     * @return 当前模板实例
     */
    public StreamingPageExportTemplate<T> sheetConfigurator(BiConsumer<ExcelWriterSheetBuilder, Class<?>> sheetConfigurator) {
        this.sheetConfigurator = sheetConfigurator;
        return this;
    }

    // ==================== 核心写入与流式导出契约 ====================

    /**
     * 写入一个批次的数据列表，内部根据行数水位自动处理超限跨 Sheet 轮换与即时 Flush
     *
     * @param batch 当前批次数据集合
     */
    public synchronized void writeBatch(List<T> batch) {
        if (finished) {
            throw new IllegalStateException("StreamingPageExportTemplate 已关闭，无法继续写入数据");
        }
        ensureFirstSheetCreated();
        if (batch == null || batch.isEmpty()) {
            return;
        }

        int remainingInBatch = batch.size();
        int batchOffset = 0;

        while (remainingInBatch > 0) {
            long capacityInCurrentSheet = maxRowsPerSheet - currentSheetRowCount;
            if (capacityInCurrentSheet <= 0) {
                // 当前工作表已满，触发自动分割切入下一个 Sheet
                openNextSheet();
                capacityInCurrentSheet = maxRowsPerSheet;
            }

            int writeSize = (int) Math.min(remainingInBatch, capacityInCurrentSheet);
            List<T> subList = batch.subList(batchOffset, batchOffset + writeSize);

            excelWriter.write(subList, currentWriteSheet);
            currentSheetRowCount += writeSize;
            totalExportedCount += writeSize;
            batchOffset += writeSize;
            remainingInBatch -= writeSize;
        }
    }

    /**
     * 基于标准分页模型 {@link PageResult} 驱动流式拉取并自动写出至结束
     *
     * @param pageLoader 分页数据拉取函数（入参为当前页码，从 1 开始；出参为分页结果）
     * @return 总计导出行数
     */
    public long exportByPage(Function<Integer, PageResult<T>> pageLoader) {
        Assert.notNull(pageLoader, "pageLoader 分页查询函数不可为空");
        int currentPage = 1;
        while (true) {
            PageResult<T> pageResult = pageLoader.apply(currentPage);
            if (pageResult == null || pageResult.getRecords() == null || pageResult.getRecords().isEmpty()) {
                ensureFirstSheetCreated();
                break;
            }

            List<T> records = pageResult.getRecords();
            writeBatch(records);

            // 若本页记录数小于页大小，或者已达到总数统计，则代表全部数据已读取完毕
            if (records.size() < pageResult.getSize() || (pageResult.getTotal() > 0 && totalExportedCount >= pageResult.getTotal())) {
                break;
            }
            currentPage++;
        }
        finish();
        return totalExportedCount;
    }

    /**
     * 基于固定步长的分页函数驱动流式拉取并自动写出至结束
     *
     * @param pageSize   每页拉取条数
     * @param pageLoader 分页数据拉取函数（入参为当前页码，从 1 开始；出参为数据列表）
     * @return 总计导出行数
     */
    public long exportByPage(int pageSize, Function<Integer, List<T>> pageLoader) {
        Assert.isTrue(pageSize > 0, "pageSize 必须大于 0");
        Assert.notNull(pageLoader, "pageLoader 分页查询函数不可为空");
        int currentPage = 1;
        while (true) {
            List<T> records = pageLoader.apply(currentPage);
            if (records == null || records.isEmpty()) {
                ensureFirstSheetCreated();
                break;
            }

            writeBatch(records);

            if (records.size() < pageSize) {
                break;
            }
            currentPage++;
        }
        finish();
        return totalExportedCount;
    }

    /**
     * 基于主键/时间戳游标的流式拉取驱动（专为超大数据量消除数据库深分页慢 SQL 打造）
     *
     * @param initialCursor   初始游标值（如 0L 或 null）
     * @param pageSize        每次游标拉取的步长大小
     * @param cursorLoader    游标数据拉取函数（参数 1 为当前游标值，参数 2 为本次 limit 条数）
     * @param cursorExtractor 游标提取器（从本批次最后一条实体中提取下一次查询的游标值）
     * @param <K>             游标类型（如 Long 或 String）
     * @return 总计导出行数
     */
    public <K> long exportByCursor(
            K initialCursor,
            int pageSize,
            BiFunction<K, Integer, List<T>> cursorLoader,
            Function<T, K> cursorExtractor) {
        Assert.isTrue(pageSize > 0, "pageSize 必须大于 0");
        Assert.notNull(cursorLoader, "cursorLoader 游标拉取函数不可为空");
        Assert.notNull(cursorExtractor, "cursorExtractor 游标提取器不可为空");

        K currentCursor = initialCursor;
        while (true) {
            List<T> records = cursorLoader.apply(currentCursor, pageSize);
            if (records == null || records.isEmpty()) {
                ensureFirstSheetCreated();
                break;
            }

            writeBatch(records);

            T lastRecord = records.get(records.size() - 1);
            currentCursor = cursorExtractor.apply(lastRecord);

            if (records.size() < pageSize) {
                break;
            }
        }
        finish();
        return totalExportedCount;
    }

    /**
     * 完成全部写出并刷盘，关闭底层 Fesod 写入引擎
     */
    public synchronized void finish() {
        if (!finished) {
            try {
                ensureFirstSheetCreated();
                if (excelWriter != null) {
                    excelWriter.finish();
                }
            } finally {
                finished = true;
            }
        }
    }

    @Override
    public void close() {
        finish();
    }

    // ==================== 内部状态与工作表流转 ====================

    private void ensureFirstSheetCreated() {
        if (firstSheetInitialized) {
            return;
        }
        // 1. 初始化工作簿级 ExcelWriter
        ExcelWriterBuilder writerBuilder = FesodSheet.write(outputStream, headClass)
                .registerWriteHandler(new SensitiveCellWriteHandler())
                .registerWriteHandler(new PromptHeadWriteHandler())
                .registerWriteHandler(new FormulaProtectionCellWriteHandler());

        if (MergeCellWriteHandler.hasMergeAnnotation(headClass)) {
            writerBuilder.registerWriteHandler(new MergeCellWriteHandler());
        }
        if (ImageRowHeightWriteHandler.hasExcelImage(headClass)) {
            writerBuilder.registerWriteHandler(new ImageRowHeightWriteHandler(headClass));
            writerBuilder.registerConverter(new SmartImageConverter());
        }
        registerEnumConverters(writerBuilder, headClass);

        this.excelWriter = writerBuilder.build();

        // 2. 初始化首个 Sheet
        this.currentSheetIndex = 0;
        this.currentSheetRowCount = 0;
        String sheetName = sheetNameFormatter.format(baseSheetName, 0);

        ExcelWriterSheetBuilder sheetBuilder = FesodSheet.writerSheet(0, sheetName).head(headClass);
        applySheetConfigurations(sheetBuilder);
        this.currentWriteSheet = sheetBuilder.build();

        // 空跑一次 write(emptyList) 确保当无数据写入时至少正常生成包含表头的单 Sheet
        this.excelWriter.write(Collections.emptyList(), currentWriteSheet);
        this.firstSheetInitialized = true;
    }

    private void openNextSheet() {
        currentSheetIndex++;
        currentSheetRowCount = 0;
        String nextSheetName = sheetNameFormatter.format(baseSheetName, currentSheetIndex);
        log.info("流式导出数据达到单表上限，自动开启新工作表 [{}] (序号: {})", nextSheetName, currentSheetIndex);

        ExcelWriterSheetBuilder sheetBuilder = FesodSheet.writerSheet(currentSheetIndex, nextSheetName).head(headClass);
        applySheetConfigurations(sheetBuilder);
        this.currentWriteSheet = sheetBuilder.build();

        // 写入空列表触发新 Sheet 重新渲染完整表头
        this.excelWriter.write(Collections.emptyList(), currentWriteSheet);
    }

    private void applySheetConfigurations(ExcelWriterSheetBuilder sheetBuilder) {
        if (sheetConfigurator != null) {
            sheetConfigurator.accept(sheetBuilder, headClass);
        }
        sheetBuilder.registerWriteHandler(new DropdownSheetWriteHandler(headClass));
        registerEnumConverters(sheetBuilder, headClass);

        if ((this.watermarkText != null && !this.watermarkText.isBlank()) || (this.protectionPassword != null && !this.protectionPassword.isBlank())) {
            sheetBuilder.registerWriteHandler(new WatermarkSheetWriteHandler(this.watermarkText, this.protectionPassword));
        }

        applyStyle(sheetBuilder, headClass);
        if (ColumnLockSheetWriteHandler.hasColumnLock(headClass)) {
            sheetBuilder.registerWriteHandler(new ColumnLockSheetWriteHandler(headClass, this.protectionPassword));
        }
    }

    private void applyStyle(ExcelWriterSheetBuilder sheetBuilder, Class<?> targetClass) {
        if (styleDisabled) {
            return;
        }

        ExcelStyleProperties styleProps = ExcelUtil.getStyleProperties();
        ExcelTheme annotation = (targetClass != null) ? targetClass.getAnnotation(ExcelTheme.class) : null;

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
            int finalMin = (this.minColumnWidth != null) ? this.minColumnWidth
                    : ((annotation != null && annotation.minColumnWidth() > 0) ? annotation.minColumnWidth() : styleProps.getMinColumnWidth());
            int finalMax = (this.maxColumnWidth != null) ? this.maxColumnWidth
                    : ((annotation != null && annotation.maxColumnWidth() > 0) ? annotation.maxColumnWidth() : styleProps.getMaxColumnWidth());
            int padding = (styleProps.getPaddingColumnWidth() > 0) ? styleProps.getPaddingColumnWidth() : SmartAutoColumnWidthWriteHandler.DEFAULT_PADDING;
            sheetBuilder.registerWriteHandler(new SmartAutoColumnWidthWriteHandler(finalMin, finalMax, padding));
        }
    }

    private static void registerEnumConverters(AbstractParameterBuilder<?, ?> builder, Class<?> targetClass) {
        if (builder == null || targetClass == null) {
            return;
        }
        Set<Class<?>> enumClasses = EnumMappingCache.getEntityEnumClasses(targetClass);
        for (Class<?> enumClass : enumClasses) {
            builder.registerConverter(new ExcelEnumConverter(enumClass, null, null));
            builder.registerConverter(new ExcelEnumConverter(enumClass, null, CellDataTypeEnum.STRING));
        }
    }

    // ==================== 状态访问器 ====================

    /**
     * 获取当前已导出的总行数
     *
     * @return 总行数
     */
    public long getTotalExportedCount() {
        return totalExportedCount;
    }

    /**
     * 获取当前正在写入的工作表序号（0-based）
     *
     * @return 工作表序号
     */
    public int getCurrentSheetIndex() {
        return currentSheetIndex;
    }

    /**
     * 获取当前工作表已写入的行数
     *
     * @return 当前工作表行数
     */
    public long getCurrentSheetRowCount() {
        return currentSheetRowCount;
    }
}
