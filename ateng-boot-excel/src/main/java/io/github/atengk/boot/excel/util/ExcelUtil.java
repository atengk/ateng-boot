package io.github.atengk.boot.excel.util;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.excel.annotation.ExcelEnum;
import io.github.atengk.boot.excel.annotation.ExcelTheme;
import io.github.atengk.boot.excel.enums.ExcelThemeEnum;
import io.github.atengk.boot.excel.enums.HeaderValidationStrategy;
import io.github.atengk.boot.excel.builder.ExcelBuilder;
import io.github.atengk.boot.excel.converter.EnumMappingCache;
import io.github.atengk.boot.excel.converter.ExcelEnumConverter;
import io.github.atengk.boot.excel.converter.SmartImageConverter;
import io.github.atengk.boot.excel.exception.ExcelHeaderValidationException;
import io.github.atengk.boot.excel.exception.ExcelValidationException;
import io.github.atengk.boot.excel.converter.FormulaProtectionStringConverter;
import io.github.atengk.boot.excel.handler.ColumnLockSheetWriteHandler;
import io.github.atengk.boot.excel.handler.DropdownSheetWriteHandler;
import io.github.atengk.boot.excel.handler.ExcelThemeWriteHandler;
import io.github.atengk.boot.excel.handler.FailSplitRowWriteHandler;
import io.github.atengk.boot.excel.handler.FormulaProtectionCellWriteHandler;
import io.github.atengk.boot.excel.handler.I18nHeadWriteHandler;
import io.github.atengk.boot.excel.handler.ImageRowHeightWriteHandler;
import io.github.atengk.boot.excel.handler.MergeCellWriteHandler;
import io.github.atengk.boot.excel.handler.PromptHeadWriteHandler;
import io.github.atengk.boot.excel.handler.SensitiveCellWriteHandler;
import io.github.atengk.boot.excel.handler.SmartAutoColumnWidthWriteHandler;
import io.github.atengk.boot.excel.handler.WatermarkSheetWriteHandler;
import io.github.atengk.boot.excel.listener.BatchValidationListener;
import io.github.atengk.boot.excel.listener.ExcelHeaderValidator;
import io.github.atengk.boot.excel.model.ExcelErrorRow;
import io.github.atengk.boot.excel.model.ExcelReadResult;
import io.github.atengk.boot.excel.properties.ExcelI18nProperties;
import io.github.atengk.boot.excel.properties.ExcelProperties;
import io.github.atengk.boot.excel.properties.ExcelSecurityProperties;
import io.github.atengk.boot.excel.properties.ExcelStreamingExportProperties;
import io.github.atengk.boot.excel.properties.ExcelStyleProperties;
import io.github.atengk.boot.excel.properties.ExcelValidationProperties;
import io.github.atengk.boot.excel.streaming.StreamingPageExportTemplate;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.fesod.sheet.ExcelWriter;
import org.apache.fesod.sheet.FesodSheet;
import org.apache.fesod.sheet.annotation.ExcelIgnore;
import org.apache.fesod.sheet.annotation.ExcelIgnoreUnannotated;
import org.apache.fesod.sheet.annotation.ExcelProperty;
import org.apache.fesod.sheet.context.AnalysisContext;
import org.apache.fesod.sheet.enums.CellDataTypeEnum;
import org.apache.fesod.sheet.metadata.AbstractParameterBuilder;
import io.github.atengk.boot.excel.builder.ExcelReaderBuilder;
import org.apache.fesod.sheet.read.listener.ReadListener;
import org.apache.fesod.sheet.write.builder.ExcelWriterBuilder;
import org.apache.fesod.sheet.write.metadata.WriteSheet;
import org.apache.fesod.sheet.write.metadata.fill.FillConfig;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.util.Assert;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Excel 核心门面操作工具类
 * <p>
 * 提供开箱即用的 Excel 流式导出、Web 响应安全下载、强类型同步读取与分批流式消费能力。
 * 底层基于 Apache Fesod (Incubating) 事件驱动流式架构，有效防御 OOM 风险。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-16
 */
public final class ExcelUtil {

    private static final String DEFAULT_SHEET_NAME = "Sheet1";
    private static final String XLSX_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet;charset=utf-8";
    private static final String XLSX_EXTENSION = ".xlsx";

    /**
     * 默认脱敏单元格写出拦截器（无状态线程安全单例）
     */
    private static final SensitiveCellWriteHandler DEFAULT_SENSITIVE_WRITE_HANDLER = new SensitiveCellWriteHandler();

    /**
     * 默认表头批注与输入提示拦截器（无状态线程安全单例）
     */
    private static final PromptHeadWriteHandler DEFAULT_PROMPT_HEAD_WRITE_HANDLER = new PromptHeadWriteHandler();

    /**
     * 默认多语言国际化表头动态转译写入拦截器（无状态线程安全单例）
     */
    private static final I18nHeadWriteHandler DEFAULT_I18N_HEAD_WRITE_HANDLER = I18nHeadWriteHandler.unmodifiable();

    /**
     * 默认公式注入安全防御写入拦截器（无状态线程安全单例）
     */
    private static final FormulaProtectionCellWriteHandler DEFAULT_FORMULA_PROTECTION_WRITE_HANDLER = new FormulaProtectionCellWriteHandler();

    /**
     * 默认公式注入安全防御字符串反转译转换器（无状态线程安全单例）
     */
    private static final FormulaProtectionStringConverter DEFAULT_FORMULA_PROTECTION_STRING_CONVERTER = new FormulaProtectionStringConverter();

    /**
     * 流式读取全局默认批次大小（可通过配置文件 ateng.boot.excel.default-batch-size 调整）
     */
    private static volatile int defaultBatchSize = 1000;

    /**
     * 同步全量读取最大安全行数上限（默认 100,000，超过时快速失败以防御内存 OOM；可通过配置文件 ateng.boot.excel.max-sync-rows 调整）
     */
    private static volatile int maxSyncRows = 100_000;

    /**
     * 全局样式与模版主题配置（可通过配置文件 ateng.boot.excel.style.* 调整）
     */
    private static volatile ExcelStyleProperties defaultStyleProperties = new ExcelStyleProperties();

    /**
     * 流式分页导出全局默认配置（可通过配置文件 ateng.boot.excel.streaming-export.* 调整）
     */
    private static volatile ExcelStreamingExportProperties defaultStreamingExportProperties = new ExcelStreamingExportProperties();

    /**
     * 导入数据校验与熔断全局配置（可通过配置文件 ateng.boot.excel.validation.* 调整）
     */
    private static volatile ExcelValidationProperties defaultValidationProperties = new ExcelValidationProperties();

    /**
     * 安全防护与公式注入防御全局配置（可通过配置文件 ateng.boot.excel.security.* 调整）
     */
    private static volatile ExcelSecurityProperties defaultSecurityProperties = new ExcelSecurityProperties();

    /**
     * 多语言国际化表头全局配置（可通过配置文件 ateng.boot.excel.i18n.* 调整）
     */
    private static volatile ExcelI18nProperties defaultI18nProperties = new ExcelI18nProperties();

    private ExcelUtil() {
        // 私有构造，工具类禁止实例化
    }

    /**
     * 基于配置属性初始化全局默认参数
     *
     * @param properties 配置属性对象
     */
    public static void initProperties(ExcelProperties properties) {
        if (properties != null) {
            if (properties.getDefaultBatchSize() > 0) {
                defaultBatchSize = properties.getDefaultBatchSize();
            }
            if (properties.getMaxSyncRows() > 0) {
                maxSyncRows = properties.getMaxSyncRows();
            }
            if (properties.getStyle() != null) {
                defaultStyleProperties = properties.getStyle();
            }
            if (properties.getStreamingExport() != null) {
                defaultStreamingExportProperties = properties.getStreamingExport();
            }
            if (properties.getValidation() != null) {
                defaultValidationProperties = properties.getValidation();
            }
            if (properties.getSecurity() != null) {
                defaultSecurityProperties = properties.getSecurity();
            }
            if (properties.getI18n() != null) {
                defaultI18nProperties = properties.getI18n();
            }
        }
    }

    /**
     * 获取当前生效的多语言国际化表头配置属性对象
     *
     * @return 国际化配置属性对象
     */
    public static ExcelI18nProperties getI18nProperties() {
        return defaultI18nProperties;
    }

    /**
     * 手动更新多语言国际化配置属性（主要用于测试与动态覆盖）
     *
     * @param properties 国际化配置属性
     */
    public static void setI18nProperties(ExcelI18nProperties properties) {
        if (properties != null) {
            defaultI18nProperties = properties;
        }
    }

    /**
     * 获取当前生效的安全防护与公式注入防御配置属性对象
     *
     * @return 安全防护配置对象
     */
    public static ExcelSecurityProperties getSecurityProperties() {
        return defaultSecurityProperties;
    }

    /**
     * 获取当前生效的导入校验与错误熔断配置属性对象
     *
     * @return 导入校验配置对象
     */
    public static ExcelValidationProperties getValidationProperties() {
        return defaultValidationProperties;
    }

    /**
     * 获取当前生效的流式分页导出配置属性对象
     *
     * @return 流式分页配置对象
     */
    public static ExcelStreamingExportProperties getStreamingExportProperties() {
        return defaultStreamingExportProperties;
    }

    /**
     * 获取当前生效的同步全量读取最大安全行数上限
     *
     * @return 同步读取安全行数上限
     */
    public static int getMaxSyncRows() {
        return maxSyncRows;
    }

    /**
     * 设置同步全量读取最大安全行数上限（设为 0 或负数则关闭行数限制）
     *
     * @param rows 安全行数上限
     */
    public static void setMaxSyncRows(int rows) {
        maxSyncRows = rows;
    }

    /**
     * 获取当前生效的全局样式配置对象
     *
     * @return 全局样式配置
     */
    public static ExcelStyleProperties getStyleProperties() {
        return defaultStyleProperties;
    }

    /**
     * 自动装配企业级模版主题与智能自适应列宽拦截器
     *
     * @param writerBuilder 写出器构建器
     * @param clazz         导出的实体 Class（为 null 时使用全局默认配置）
     */
    public static void configureStyle(ExcelWriterBuilder writerBuilder, Class<?> clazz) {
        if (writerBuilder == null) {
            return;
        }

        ExcelStyleProperties styleProps = defaultStyleProperties;
        ExcelTheme annotation = (clazz != null) ? clazz.getAnnotation(ExcelTheme.class) : null;

        if (annotation != null && !annotation.enabled()) {
            return; // 显式注解关闭
        }
        if (annotation == null && !styleProps.isEnabled()) {
            return; // 全局关闭
        }

        ExcelThemeEnum theme;
        if (annotation != null) {
            if (!annotation.value().isBlank()) {
                theme = ExcelThemeEnum.fromName(annotation.value());
            } else {
                theme = annotation.theme();
            }
        } else {
            theme = ExcelThemeEnum.fromName(styleProps.getTheme());
        }

        boolean zebraStripe = (annotation != null) ? annotation.zebraStripe() : styleProps.isZebraStripe();
        boolean autoColumnWidth = (annotation != null) ? annotation.autoColumnWidth() : styleProps.isAutoColumnWidth();
        int minWidth = (annotation != null && annotation.minColumnWidth() > 0) ? annotation.minColumnWidth() : styleProps.getMinColumnWidth();
        int maxWidth = (annotation != null && annotation.maxColumnWidth() > 0) ? annotation.maxColumnWidth() : styleProps.getMaxColumnWidth();
        int padding = (styleProps.getPaddingColumnWidth() > 0) ? styleProps.getPaddingColumnWidth() : SmartAutoColumnWidthWriteHandler.DEFAULT_PADDING;
        String fontName = (annotation != null && !annotation.fontName().isBlank()) ? annotation.fontName() : styleProps.getFontName();

        writerBuilder.registerWriteHandler(new ExcelThemeWriteHandler(theme, zebraStripe, fontName));
        if (autoColumnWidth) {
            writerBuilder.registerWriteHandler(new SmartAutoColumnWidthWriteHandler(minWidth, maxWidth, padding));
        }
    }

    /**
     * 流式导出数据至指定的 OutputStream
     *
     * @param outputStream 目标输出流（不可为空）
     * @param data         待导出的数据集合（为 null 时视为空集合）
     * @param clazz        导出的实体类型 Class（不可为空）
     * @param <T>          实体数据泛型
     */
    public static <T> void export(OutputStream outputStream, List<T> data, Class<T> clazz) {
        export(outputStream, DEFAULT_SHEET_NAME, data, clazz);
    }

    /**
     * 流式导出数据至指定的 OutputStream（实体类型在前签名重载）
     *
     * @param outputStream 目标输出流（不可为空）
     * @param clazz        导出的实体类型 Class（不可为空）
     * @param data         待导出的数据集合（为 null 时视为空集合）
     * @param <T>          实体数据泛型
     */
    public static <T> void export(OutputStream outputStream, Class<T> clazz, List<T> data) {
        export(outputStream, DEFAULT_SHEET_NAME, data, clazz);
    }

    private static <T> ExcelWriterBuilder createStandardWriterBuilder(OutputStream outputStream, Class<T> clazz) {
        return createStandardWriterBuilder(outputStream, clazz, null, null);
    }

    private static <T> ExcelWriterBuilder createStandardWriterBuilder(OutputStream outputStream, Class<T> clazz,
                                                                      Collection<String> includeColumns,
                                                                      Collection<String> excludeColumns) {
        var writerBuilder = FesodSheet.write(outputStream, clazz)
                .registerWriteHandler(DEFAULT_I18N_HEAD_WRITE_HANDLER)
                .registerWriteHandler(DEFAULT_SENSITIVE_WRITE_HANDLER)
                .registerWriteHandler(DEFAULT_FORMULA_PROTECTION_WRITE_HANDLER);
        List<String> projectedColumns = resolveProjectedColumns(clazz, includeColumns, excludeColumns);
        if (projectedColumns != null) {
            writerBuilder.includeColumnFieldNames(projectedColumns);
            writerBuilder.orderByIncludeColumn(true);
        }
        if (MergeCellWriteHandler.hasMergeAnnotation(clazz)) {
            writerBuilder.registerWriteHandler(new MergeCellWriteHandler());
        }
        if (ImageRowHeightWriteHandler.hasExcelImage(clazz)) {
            writerBuilder.registerWriteHandler(new ImageRowHeightWriteHandler(clazz));
            writerBuilder.registerConverter(new SmartImageConverter());
        }
        configureStyle(writerBuilder, clazz);
        if (ColumnLockSheetWriteHandler.hasColumnLock(clazz)) {
            writerBuilder.registerWriteHandler(new ColumnLockSheetWriteHandler(clazz));
        }
        registerEnumConverters(writerBuilder, clazz);
        return writerBuilder;
    }

    /**
     * 流式导出数据至指定的 OutputStream，并指定 Sheet 名称
     *
     * @param outputStream 目标输出流（不可为空）
     * @param sheetName    工作表名称（为空时默认 Sheet1）
     * @param data         待导出的数据集合（为 null 时视为空集合）
     * @param clazz        导出的实体类型 Class（不可为空）
     * @param <T>          实体数据泛型
     */
    public static <T> void export(OutputStream outputStream, String sheetName, List<T> data, Class<T> clazz) {
        export(outputStream, sheetName, data, clazz, null, null);
    }

    /**
     * 流式导出数据至指定的 OutputStream，并指定动态包含的列
     *
     * @param outputStream   目标输出流（不可为空）
     * @param data           待导出的数据集合（为 null 时视为空集合）
     * @param clazz          导出的实体类型 Class（不可为空）
     * @param includeColumns 动态包含的属性字段名称集合（为空或 null 则不限制）
     * @param <T>            实体数据泛型
     */
    public static <T> void export(OutputStream outputStream, List<T> data, Class<T> clazz, Collection<String> includeColumns) {
        export(outputStream, DEFAULT_SHEET_NAME, data, clazz, includeColumns, null);
    }

    /**
     * 流式导出数据至指定的 OutputStream，并指定动态包含与排除的列
     *
     * @param outputStream   目标输出流（不可为空）
     * @param data           待导出的数据集合（为 null 时视为空集合）
     * @param clazz          导出的实体类型 Class（不可为空）
     * @param includeColumns 动态包含的属性字段名称集合（为空或 null 则不限制）
     * @param excludeColumns 动态排除的属性字段名称集合（为空或 null 则不限制）
     * @param <T>            实体数据泛型
     */
    public static <T> void export(OutputStream outputStream, List<T> data, Class<T> clazz,
                                  Collection<String> includeColumns, Collection<String> excludeColumns) {
        export(outputStream, DEFAULT_SHEET_NAME, data, clazz, includeColumns, excludeColumns);
    }

    /**
     * 流式导出数据至指定的 OutputStream，并指定 Sheet 名称与包含的列
     *
     * @param outputStream   目标输出流（不可为空）
     * @param sheetName      工作表名称（为空时默认 Sheet1）
     * @param data           待导出的数据集合（为 null 时视为空集合）
     * @param clazz          导出的实体类型 Class（不可为空）
     * @param includeColumns 动态包含的属性字段名称集合（为空或 null 则不限制）
     * @param <T>            实体数据泛型
     */
    public static <T> void export(OutputStream outputStream, String sheetName, List<T> data, Class<T> clazz,
                                  Collection<String> includeColumns) {
        export(outputStream, sheetName, data, clazz, includeColumns, null);
    }

    /**
     * 流式导出数据至指定的 OutputStream，并指定 Sheet 名称与动态包含/排除的列
     *
     * @param outputStream   目标输出流（不可为空）
     * @param sheetName      工作表名称（为空时默认 Sheet1）
     * @param data           待导出的数据集合（为 null 时视为空集合）
     * @param clazz          导出的实体类型 Class（不可为空）
     * @param includeColumns 动态包含的属性字段名称集合（为空或 null 则不限制）
     * @param excludeColumns 动态排除的属性字段名称集合（为空或 null 则不限制）
     * @param <T>            实体数据泛型
     */
    public static <T> void export(OutputStream outputStream, String sheetName, List<T> data, Class<T> clazz,
                                  Collection<String> includeColumns, Collection<String> excludeColumns) {
        Assert.notNull(outputStream, "OutputStream 目标输出流不可为空");
        Assert.notNull(clazz, "导出的实体 Class 类型不可为空");

        String targetSheetName = (sheetName != null && !sheetName.isBlank()) ? sheetName : DEFAULT_SHEET_NAME;
        List<T> exportList = (data != null) ? data : Collections.emptyList();

        var writerBuilder = createStandardWriterBuilder(outputStream, clazz, includeColumns, excludeColumns);
        writerBuilder.sheet(targetSheetName)
                .doWrite(exportList);
    }

    /**
     * Web 环境下流式导出 Excel 并直接写入 HttpServletResponse
     * <p>
     * 自动配置遵循 RFC 5987 / RFC 6266 标准的 UTF-8 防乱码响应头与标准 XLSX MIME 类型。
     * </p>
     *
     * @param response 响应对象（不可为空）
     * @param fileName 导出的文件名（不可为空，自动补充 .xlsx 后缀）
     * @param data     待导出的数据集合
     * @param clazz    导出的实体类型 Class（不可为空）
     * @param <T>      实体数据泛型
     */
    public static <T> void export(HttpServletResponse response, String fileName, List<T> data, Class<T> clazz) {
        export(response, fileName, DEFAULT_SHEET_NAME, data, clazz, null, null);
    }

    /**
     * Web 环境下流式导出 Excel 并直接写入 HttpServletResponse（实体类型在前签名重载）
     *
     * @param response 响应对象（不可为空）
     * @param fileName 导出的文件名（不可为空，自动补充 .xlsx 后缀）
     * @param clazz    导出的实体类型 Class（不可为空）
     * @param data     待导出的数据集合
     * @param <T>      实体数据泛型
     */
    public static <T> void export(HttpServletResponse response, String fileName, Class<T> clazz, List<T> data) {
        export(response, fileName, DEFAULT_SHEET_NAME, data, clazz, null, null);
    }

    /**
     * Web 环境下流式导出 Excel 并直接写入 HttpServletResponse，并指定包含的列
     *
     * @param response       响应对象（不可为空）
     * @param fileName       导出的文件名（不可为空，自动补充 .xlsx 后缀）
     * @param data           待导出的数据集合
     * @param clazz          导出的实体类型 Class（不可为空）
     * @param includeColumns 包含的属性字段名称集合（为空或 null 则不限制）
     * @param <T>            实体数据泛型
     */
    public static <T> void export(HttpServletResponse response, String fileName, List<T> data, Class<T> clazz,
                                  Collection<String> includeColumns) {
        export(response, fileName, DEFAULT_SHEET_NAME, data, clazz, includeColumns, null);
    }

    /**
     * Web 环境下流式导出 Excel 并直接写入 HttpServletResponse，并指定包含与排除的列
     *
     * @param response       响应对象（不可为空）
     * @param fileName       导出的文件名（不可为空，自动补充 .xlsx 后缀）
     * @param data           待导出的数据集合
     * @param clazz          导出的实体类型 Class（不可为空）
     * @param includeColumns 包含的属性字段名称集合（为空或 null 则不限制）
     * @param excludeColumns 排除的属性字段名称集合（为空或 null 则不限制）
     * @param <T>            实体数据泛型
     */
    public static <T> void export(HttpServletResponse response, String fileName, List<T> data, Class<T> clazz,
                                  Collection<String> includeColumns, Collection<String> excludeColumns) {
        export(response, fileName, DEFAULT_SHEET_NAME, data, clazz, includeColumns, excludeColumns);
    }

    /**
     * Web 环境下流式导出 Excel 并直接写入 HttpServletResponse，并指定 Sheet 名称
     *
     * @param response  响应对象（不可为空）
     * @param fileName  导出的文件名（不可为空，自动补充 .xlsx 后缀）
     * @param sheetName 工作表名称（为空时默认 Sheet1）
     * @param data      待导出的数据集合
     * @param clazz     导出的实体类型 Class（不可为空）
     * @param <T>       实体数据泛型
     */
    public static <T> void export(HttpServletResponse response, String fileName, String sheetName, List<T> data, Class<T> clazz) {
        export(response, fileName, sheetName, data, clazz, null, null);
    }

    /**
     * Web 环境下流式导出 Excel 并直接写入 HttpServletResponse，并指定 Sheet 名称与包含的列
     *
     * @param response       响应对象（不可为空）
     * @param fileName       导出的文件名（不可为空，自动补充 .xlsx 后缀）
     * @param sheetName      工作表名称（为空时默认 Sheet1）
     * @param data           待导出的数据集合
     * @param clazz          导出的实体类型 Class（不可为空）
     * @param includeColumns 包含的属性字段名称集合（为空或 null 则不限制）
     * @param <T>            实体数据泛型
     */
    public static <T> void export(HttpServletResponse response, String fileName, String sheetName, List<T> data, Class<T> clazz,
                                  Collection<String> includeColumns) {
        export(response, fileName, sheetName, data, clazz, includeColumns, null);
    }

    /**
     * Web 环境下流式导出 Excel 并直接写入 HttpServletResponse，并指定 Sheet 名称与包含/排除的列
     *
     * @param response       响应对象（不可为空）
     * @param fileName       导出的文件名（不可为空，自动补充 .xlsx 后缀）
     * @param sheetName      工作表名称（为空时默认 Sheet1）
     * @param data           待导出的数据集合
     * @param clazz          导出的实体类型 Class（不可为空）
     * @param includeColumns 包含的属性字段名称集合（为空或 null 则不限制）
     * @param excludeColumns 排除的属性字段名称集合（为空或 null 则不限制）
     * @param <T>            实体数据泛型
     */
    public static <T> void export(HttpServletResponse response, String fileName, String sheetName, List<T> data, Class<T> clazz,
                                  Collection<String> includeColumns, Collection<String> excludeColumns) {
        prepareWebResponse(response, fileName);
        Assert.notNull(clazz, "导出的实体 Class 类型不可为空");

        try {
            export(response.getOutputStream(), sheetName, data, clazz, includeColumns, excludeColumns);
        } catch (IOException e) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(), "Excel 导出写入响应流失败: " + e.getMessage(), e);
        }
    }

    /**
     * 创建基于指定输出流的海量数据流式分页导出模板
     *
     * @param outputStream 目标输出流（不可为空）
     * @param clazz        导出的实体类型 Class（不可为空）
     * @param <T>          实体泛型
     * @return 流式分页导出模板实例
     */
    public static <T> StreamingPageExportTemplate<T> streaming(OutputStream outputStream, Class<T> clazz) {
        StreamingPageExportTemplate<T> template = new StreamingPageExportTemplate<>(outputStream, clazz);
        template.maxRowsPerSheet(defaultStreamingExportProperties.getMaxRowsPerSheet());
        template.defaultPageSize(defaultStreamingExportProperties.getDefaultPageSize());
        template.sheetConfigurator(ExcelUtil::configureSheet);
        return template;
    }

    /**
     * Web 环境下创建基于 HttpServletResponse 的海量数据流式分页导出模板
     *
     * @param response 响应对象（不可为空）
     * @param fileName 导出的文件名（自动补充 .xlsx 后缀）
     * @param clazz    导出的实体类型 Class（不可为空）
     * @param <T>      实体泛型
     * @return 流式分页导出模板实例
     */
    public static <T> StreamingPageExportTemplate<T> streaming(HttpServletResponse response, String fileName, Class<T> clazz) {
        prepareWebResponse(response, fileName);
        try {
            return streaming(response.getOutputStream(), clazz);
        } catch (IOException ex) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(), "创建流式分页导出模板失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 流式分页导出数据至指定的 OutputStream（基于 PageResult 驱动）
     *
     * @param outputStream 目标输出流（不可为空）
     * @param clazz        导出的实体类型 Class（不可为空）
     * @param pageLoader   分页拉取函数（入参为页码，从 1 开始）
     * @param <T>          实体泛型
     * @return 实际导出的总行数
     */
    public static <T> long exportStreaming(OutputStream outputStream, Class<T> clazz, Function<Integer, PageResult<T>> pageLoader) {
        return exportStreaming(outputStream, DEFAULT_SHEET_NAME, clazz, pageLoader);
    }

    /**
     * 流式分页导出数据至指定的 OutputStream，并指定基础 Sheet 名称（基于 PageResult 驱动）
     *
     * @param outputStream 目标输出流（不可为空）
     * @param sheetName    基础工作表名称
     * @param clazz        导出的实体类型 Class（不可为空）
     * @param pageLoader   分页拉取函数（入参为页码，从 1 开始）
     * @param <T>          实体泛型
     * @return 实际导出的总行数
     */
    public static <T> long exportStreaming(OutputStream outputStream, String sheetName, Class<T> clazz, Function<Integer, PageResult<T>> pageLoader) {
        try (StreamingPageExportTemplate<T> template = streaming(outputStream, clazz)) {
            template.baseSheetName(sheetName);
            return template.exportByPage(pageLoader);
        }
    }

    /**
     * Web 环境下流式分页导出至 HttpServletResponse（基于 PageResult 驱动）
     *
     * @param response   响应对象（不可为空）
     * @param fileName   导出的文件名（自动补充 .xlsx 后缀）
     * @param clazz      导出的实体类型 Class（不可为空）
     * @param pageLoader 分页拉取函数（入参为页码，从 1 开始）
     * @param <T>        实体泛型
     * @return 实际导出的总行数
     */
    public static <T> long exportStreaming(HttpServletResponse response, String fileName, Class<T> clazz, Function<Integer, PageResult<T>> pageLoader) {
        return exportStreaming(response, fileName, DEFAULT_SHEET_NAME, clazz, pageLoader);
    }

    /**
     * Web 环境下流式分页导出至 HttpServletResponse，并指定基础 Sheet 名称（基于 PageResult 驱动）
     *
     * @param response   响应对象（不可为空）
     * @param fileName   导出的文件名（自动补充 .xlsx 后缀）
     * @param sheetName  基础工作表名称
     * @param clazz      导出的实体类型 Class（不可为空）
     * @param pageLoader 分页拉取函数（入参为页码，从 1 开始）
     * @param <T>        实体泛型
     * @return 实际导出的总行数
     */
    public static <T> long exportStreaming(HttpServletResponse response, String fileName, String sheetName, Class<T> clazz, Function<Integer, PageResult<T>> pageLoader) {
        prepareWebResponse(response, fileName);
        try {
            return exportStreaming(response.getOutputStream(), sheetName, clazz, pageLoader);
        } catch (IOException ex) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(), "Web 流式分页导出 Excel 失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 流式游标分页导出数据至指定的 OutputStream（基于主键/时间戳游标驱动，消除慢 SQL）
     *
     * @param outputStream    目标输出流
     * @param clazz           导出的实体类型 Class
     * @param initialCursor   初始游标
     * @param pageSize        每次拉取步长
     * @param cursorLoader    游标拉取函数
     * @param cursorExtractor 游标提取器
     * @param <T>             实体泛型
     * @param <K>             游标泛型
     * @return 实际导出的总行数
     */
    public static <T, K> long exportStreamingByCursor(
            OutputStream outputStream,
            Class<T> clazz,
            K initialCursor,
            int pageSize,
            BiFunction<K, Integer, List<T>> cursorLoader,
            Function<T, K> cursorExtractor) {
        return exportStreamingByCursor(outputStream, DEFAULT_SHEET_NAME, clazz, initialCursor, pageSize, cursorLoader, cursorExtractor);
    }

    /**
     * 流式游标分页导出数据至指定的 OutputStream，并指定基础 Sheet 名称（基于主键/时间戳游标驱动）
     *
     * @param outputStream    目标输出流
     * @param sheetName       基础工作表名称
     * @param clazz           导出的实体类型 Class
     * @param initialCursor   初始游标
     * @param pageSize        每次拉取步长
     * @param cursorLoader    游标拉取函数
     * @param cursorExtractor 游标提取器
     * @param <T>             实体泛型
     * @param <K>             游标泛型
     * @return 实际导出的总行数
     */
    public static <T, K> long exportStreamingByCursor(
            OutputStream outputStream,
            String sheetName,
            Class<T> clazz,
            K initialCursor,
            int pageSize,
            BiFunction<K, Integer, List<T>> cursorLoader,
            Function<T, K> cursorExtractor) {
        try (StreamingPageExportTemplate<T> template = streaming(outputStream, clazz)) {
            template.baseSheetName(sheetName);
            return template.exportByCursor(initialCursor, pageSize, cursorLoader, cursorExtractor);
        }
    }

    /**
     * Web 环境下流式游标分页导出至 HttpServletResponse（基于主键/时间戳游标驱动）
     *
     * @param response        响应对象
     * @param fileName        导出的文件名
     * @param clazz           导出的实体类型 Class
     * @param initialCursor   初始游标
     * @param pageSize        每次拉取步长
     * @param cursorLoader    游标拉取函数
     * @param cursorExtractor 游标提取器
     * @param <T>             实体泛型
     * @param <K>             游标泛型
     * @return 实际导出的总行数
     */
    public static <T, K> long exportStreamingByCursor(
            HttpServletResponse response,
            String fileName,
            Class<T> clazz,
            K initialCursor,
            int pageSize,
            BiFunction<K, Integer, List<T>> cursorLoader,
            Function<T, K> cursorExtractor) {
        return exportStreamingByCursor(response, fileName, DEFAULT_SHEET_NAME, clazz, initialCursor, pageSize, cursorLoader, cursorExtractor);
    }

    /**
     * Web 环境下流式游标分页导出至 HttpServletResponse，并指定基础 Sheet 名称
     *
     * @param response        响应对象
     * @param fileName        导出的文件名
     * @param sheetName       基础工作表名称
     * @param clazz           导出的实体类型 Class
     * @param initialCursor   初始游标
     * @param pageSize        每次拉取步长
     * @param cursorLoader    游标拉取函数
     * @param cursorExtractor 游标提取器
     * @param <T>             实体泛型
     * @param <K>             游标泛型
     * @return 实际导出的总行数
     */
    public static <T, K> long exportStreamingByCursor(
            HttpServletResponse response,
            String fileName,
            String sheetName,
            Class<T> clazz,
            K initialCursor,
            int pageSize,
            BiFunction<K, Integer, List<T>> cursorLoader,
            Function<T, K> cursorExtractor) {
        prepareWebResponse(response, fileName);
        try {
            return exportStreamingByCursor(response.getOutputStream(), sheetName, clazz, initialCursor, pageSize, cursorLoader, cursorExtractor);
        } catch (IOException ex) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(), "Web 游标流式分页导出 Excel 失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 同步全量读取 InputStream 数据为强类型实体列表
     * <p>
     * 适用于小文件读取。若数据量极大（超过 1 万行），推荐使用 {@link #readBatch} 进行分批流式消费。
     * </p>
     *
     * @param inputStream 输入流（不可为空）
     * @param clazz       读取映射的目标实体 Class（不可为空）
     * @param <T>         实体数据泛型
     * @return 解析所得的数据列表（无数据时返回空集合，绝不返回 null）
     */
    public static <T> List<T> read(InputStream inputStream, Class<T> clazz) {
        Assert.notNull(inputStream, "InputStream 输入流不可为空");
        Assert.notNull(clazz, "读取的目标实体 Class 类型不可为空");

        var readerBuilder = FesodSheet.read(inputStream)
                .head(clazz);
        registerEnumConverters(readerBuilder, clazz);
        List<T> list = readerBuilder.sheet()
                .doReadSync();
        if (maxSyncRows > 0 && list != null && list.size() > maxSyncRows) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(),
                    "Excel 数据行数超出同步读取安全上限（" + maxSyncRows + " 行），当前共 " + list.size() + " 行，请改用 readBatch 进行分批流式消费以防内存溢出");
        }
        return (list != null) ? list : Collections.emptyList();
    }

    /**
     * 流式读取 Excel 数据并交由指定的 ReadListener 自定义处理
     *
     * @param inputStream 输入流（不可为空）
     * @param clazz       读取映射的目标实体 Class（不可为空）
     * @param listener    自定义读取监听器（不可为空）
     * @param <T>         实体数据泛型
     */
    public static <T> void read(InputStream inputStream, Class<T> clazz, ReadListener<T> listener) {
        Assert.notNull(inputStream, "InputStream 输入流不可为空");
        Assert.notNull(clazz, "读取的目标实体 Class 类型不可为空");
        Assert.notNull(listener, "ReadListener 监听器不可为空");

        var readerBuilder = FesodSheet.read(inputStream, clazz, listener);
        registerEnumConverters(readerBuilder, clazz);
        readerBuilder.sheet()
                .doRead();
    }

    /**
     * 流式分批读取 InputStream 数据并回调消费（采用全局默认批次大小）
     *
     * @param inputStream 输入流（不可为空）
     * @param clazz       读取映射的目标实体 Class（不可为空）
     * @param consumer    分批数据消费回调（不可为空）
     * @param <T>         实体数据泛型
     */
    public static <T> void readBatch(InputStream inputStream, Class<T> clazz, Consumer<List<T>> consumer) {
        readBatch(inputStream, clazz, defaultBatchSize, consumer);
    }

    /**
     * 流式分批读取 InputStream 数据并回调消费
     * <p>
     * 每次累计达到指定的 {@code batchSize} 时触发回调处理，并立即清理堆内存引用，有效防御大文件导入 OOM。
     * </p>
     *
     * @param inputStream 输入流（不可为空）
     * @param clazz       读取映射的目标实体 Class（不可为空）
     * @param batchSize   每次消费的分批阈值（必须大于 0）
     * @param consumer    分批数据消费回调（不可为空）
     * @param <T>         实体数据泛型
     */
    public static <T> void readBatch(InputStream inputStream, Class<T> clazz, int batchSize, Consumer<List<T>> consumer) {
        Assert.notNull(inputStream, "InputStream 输入流不可为空");
        Assert.notNull(clazz, "读取的目标实体 Class 类型不可为空");
        Assert.isTrue(batchSize > 0, "分批大小 batchSize 必须大于 0");
        Assert.notNull(consumer, "批次数据消费者 Consumer 不可为空");

        ReadListener<T> listener = new ReadListener<>() {
            private final List<T> bufferList = new ArrayList<>(batchSize);

            @Override
            public void invoke(T data, AnalysisContext context) {
                if (data != null) {
                    bufferList.add(data);
                    if (bufferList.size() >= batchSize) {
                        flush();
                    }
                }
            }

            @Override
            public void doAfterAllAnalysed(AnalysisContext context) {
                if (!bufferList.isEmpty()) {
                    flush();
                }
            }

            private void flush() {
                consumer.accept(Collections.unmodifiableList(new ArrayList<>(bufferList)));
                bufferList.clear();
            }
        };

        var readerBuilder = FesodSheet.read(inputStream, clazz, listener);
        registerEnumConverters(readerBuilder, clazz);
        readerBuilder.sheet()
                .doRead();
    }

    /**
     * 强类型读取 Excel 并执行 JSR-303 行级数据校验（默认错误聚合模式，采用全局配置的表头策略与熔断阈值）
     * <p>
     * 校验全部通过时返回数据列表；若存在至少一行校验失败，抛出包含结构化错误详情的 {@link ExcelValidationException}。
     * </p>
     *
     * @param inputStream 输入流（不可为空）
     * @param clazz       读取映射的目标实体 Class（不可为空）
     * @param <T>         实体数据泛型
     * @return 解析成功的合法实体数据集合
     * @throws ExcelValidationException 当存在行级校验错误时抛出
     */
    public static <T> List<T> readWithValidation(InputStream inputStream, Class<T> clazz) {
        return readWithValidation(inputStream, clazz, defaultValidationProperties.getHeaderStrategy(), false);
    }

    /**
     * 强类型读取 Excel 并执行 JSR-303 行级数据校验（采用全局配置的表头策略与熔断阈值）
     *
     * @param inputStream 输入流（不可为空）
     * @param clazz       读取映射的目标实体 Class（不可为空）
     * @param failFast    是否快速失败（true: 遇到第一个错误立即抛出异常中断；false: 聚合所有错误行）
     * @param <T>         实体数据泛型
     * @return 解析成功的合法实体数据集合
     * @throws ExcelValidationException 当存在行级校验错误时抛出
     */
    public static <T> List<T> readWithValidation(InputStream inputStream, Class<T> clazz, boolean failFast) {
        return readWithValidation(inputStream, clazz, defaultValidationProperties.getHeaderStrategy(), failFast);
    }

    /**
     * 强类型读取 Excel 并执行前置表头契约与 JSR-303 行级数据校验
     *
     * @param inputStream    输入流（不可为空）
     * @param clazz          读取映射的目标实体 Class（不可为空）
     * @param headerStrategy 表头契约校验策略（STRICT: 严格匹配; LOOSE: 宽松全包含; DISABLED: 禁用）
     * @param <T>            实体数据泛型
     * @return 解析成功的合法实体数据集合
     * @throws ExcelValidationException       当存在行级数据校验错误时抛出
     * @throws ExcelHeaderValidationException 当表头契约失配时立即 Fail-Fast 抛出
     */
    public static <T> List<T> readWithValidation(InputStream inputStream, Class<T> clazz, HeaderValidationStrategy headerStrategy) {
        return readWithValidation(inputStream, clazz, headerStrategy, false);
    }

    /**
     * 强类型读取 Excel 并执行前置表头契约与 JSR-303 行级数据校验
     *
     * @param inputStream    输入流（不可为空）
     * @param clazz          读取映射的目标实体 Class（不可为空）
     * @param headerStrategy 表头契约校验策略（STRICT: 严格匹配; LOOSE: 宽松全包含; DISABLED: 禁用）
     * @param failFast       是否快速失败（true: 遇到第一个数据错误立即中断；false: 聚合错误行）
     * @param <T>            实体数据泛型
     * @return 解析成功的合法实体数据集合
     * @throws ExcelValidationException       当存在行级数据校验错误时抛出
     * @throws ExcelHeaderValidationException 当表头契约失配时立即 Fail-Fast 抛出
     */
    public static <T> List<T> readWithValidation(InputStream inputStream, Class<T> clazz, HeaderValidationStrategy headerStrategy, boolean failFast) {
        Assert.notNull(inputStream, "InputStream 输入流不可为空");
        Assert.notNull(clazz, "读取的目标实体 Class 类型不可为空");

        HeaderValidationStrategy strategy = headerStrategy != null ? headerStrategy : defaultValidationProperties.getHeaderStrategy();
        BatchValidationListener<T> listener = new BatchValidationListener<>(
                clazz,
                defaultBatchSize,
                failFast,
                defaultValidationProperties.getMaxErrorRows(),
                strategy,
                null,
                null,
                null
        );
        var readerBuilder = FesodSheet.read(inputStream, clazz, listener);
        registerEnumConverters(readerBuilder, clazz);
        readerBuilder.sheet().doRead();

        if (listener.hasErrors()) {
            if (listener.isCircuitBroken()) {
                throw new ExcelValidationException(
                        String.format("Excel 导入数据校验失败且达到熔断上限 [%d 行]，已终止后续解析", listener.getMaxErrorRows()),
                        listener.getErrorList()
                );
            }
            throw new ExcelValidationException(listener.getErrorList());
        }

        List<T> validDataList = listener.getValidDataList();
        if (maxSyncRows > 0 && validDataList != null && validDataList.size() > maxSyncRows) {
            throw new BusinessException(CommonResultCode.BAD_REQUEST.getCode(),
                    "Excel 数据行数超出同步读取安全上限（" + maxSyncRows + " 行），当前共 " + validDataList.size() + " 行，请改用 readBatch 进行分批流式消费以防内存溢出");
        }
        return validDataList;
    }

    /**
     * 强类型读取 Excel 并返回包含合法数据与全部错误行的复合结果（不抛异常，采用全局配置的表头策略与熔断阈值）
     *
     * @param inputStream 输入流（不可为空）
     * @param clazz       读取映射的目标实体 Class（不可为空）
     * @param <T>         实体数据泛型
     * @return 包含合法数据与错误行信息的 {@link ExcelReadResult}
     */
    public static <T> ExcelReadResult<T> readWithResult(InputStream inputStream, Class<T> clazz) {
        return readWithResult(inputStream, clazz, defaultValidationProperties.getHeaderStrategy(), defaultValidationProperties.getMaxErrorRows());
    }

    /**
     * 强类型读取 Excel 并返回包含合法数据与全部错误行的复合结果（不抛异常，指定表头校验策略）
     *
     * @param inputStream    输入流（不可为空）
     * @param clazz          读取映射的目标实体 Class（不可为空）
     * @param headerStrategy 表头契约校验策略
     * @param <T>            实体数据泛型
     * @return 包含合法数据、错误行信息及熔断状态的 {@link ExcelReadResult}
     */
    public static <T> ExcelReadResult<T> readWithResult(InputStream inputStream, Class<T> clazz, HeaderValidationStrategy headerStrategy) {
        return readWithResult(inputStream, clazz, headerStrategy, defaultValidationProperties.getMaxErrorRows());
    }

    /**
     * 强类型读取 Excel 并返回包含合法数据与全部错误行的复合结果（不抛异常，指定表头校验策略与错误熔断阈值）
     *
     * @param inputStream    输入流（不可为空）
     * @param clazz          读取映射的目标实体 Class（不可为空）
     * @param headerStrategy 表头契约校验策略
     * @param maxErrorRows   最大错误熔断行数（达到后立即停止解析）
     * @param <T>            实体数据泛型
     * @return 包含合法数据、错误行信息及熔断状态的 {@link ExcelReadResult}
     */
    public static <T> ExcelReadResult<T> readWithResult(InputStream inputStream, Class<T> clazz, HeaderValidationStrategy headerStrategy, int maxErrorRows) {
        Assert.notNull(inputStream, "InputStream 输入流不可为空");
        Assert.notNull(clazz, "读取的目标实体 Class 类型不可为空");

        HeaderValidationStrategy strategy = headerStrategy != null ? headerStrategy : defaultValidationProperties.getHeaderStrategy();
        int maxErrors = maxErrorRows > 0 ? maxErrorRows : defaultValidationProperties.getMaxErrorRows();

        BatchValidationListener<T> listener = new BatchValidationListener<>(
                clazz,
                defaultBatchSize,
                false,
                maxErrors,
                strategy,
                null,
                null,
                null
        );
        var readerBuilder = FesodSheet.read(inputStream, clazz, listener);
        registerEnumConverters(readerBuilder, clazz);
        readerBuilder.sheet().doRead();

        return listener.toResult();
    }

    /**
     * 独立对输入流中的 Excel 执行前置表头契约校验（仅扫描表头行即刻关闭，不解析后续数据行）
     *
     * @param inputStream 输入流（不可为空）
     * @param clazz       目标模型 Class（不可为空）
     * @param strategy    表头契约校验策略（不可为空）
     * @throws ExcelHeaderValidationException 当表头不满足契约时抛出
     */
    public static void validateHeader(InputStream inputStream, Class<?> clazz, HeaderValidationStrategy strategy) {
        Assert.notNull(inputStream, "InputStream 输入流不可为空");
        Assert.notNull(clazz, "目标模型 Class 不可为空");
        Assert.notNull(strategy, "HeaderValidationStrategy 校验策略不可为空");

        if (strategy == HeaderValidationStrategy.DISABLED) {
            return;
        }
        ExcelHeaderValidator<?> validator = new ExcelHeaderValidator<>(clazz, strategy);
        var readerBuilder = FesodSheet.read(inputStream, validator);
        readerBuilder.sheet().headRowNumber(validator.getExpectedHeadRowCount()).doRead();
    }

    /**
     * 流式分批读取 Excel 并逐行执行 JSR-303 数据校验（采用全局配置的表头策略与熔断阈值）
     *
     * @param inputStream   输入流（不可为空）
     * @param clazz         读取映射的目标实体 Class（不可为空）
     * @param batchSize     合法数据分批消费大小（大于 0）
     * @param dataConsumer  合法数据分批回调消费者（可为 null）
     * @param errorConsumer 聚合错误回调消费者（可为 null）
     * @param <T>           实体数据泛型
     */
    public static <T> void readBatchWithValidation(InputStream inputStream,
                                                   Class<T> clazz,
                                                   int batchSize,
                                                   Consumer<List<T>> dataConsumer,
                                                   Consumer<List<ExcelErrorRow>> errorConsumer) {
        readBatchWithValidation(inputStream, clazz, batchSize, defaultValidationProperties.getHeaderStrategy(), defaultValidationProperties.getMaxErrorRows(), dataConsumer, errorConsumer);
    }

    /**
     * 流式分批读取 Excel 并逐行执行 JSR-303 数据校验（指定表头校验策略与错误熔断阈值）
     *
     * @param inputStream    输入流（不可为空）
     * @param clazz          读取映射的目标实体 Class（不可为空）
     * @param batchSize      合法数据分批消费大小（大于 0）
     * @param headerStrategy 表头契约校验策略
     * @param maxErrorRows   最大错误熔断行数
     * @param dataConsumer   合法数据分批回调消费者（可为 null）
     * @param errorConsumer  聚合错误回调消费者（可为 null）
     * @param <T>            实体数据泛型
     */
    public static <T> void readBatchWithValidation(InputStream inputStream,
                                                   Class<T> clazz,
                                                   int batchSize,
                                                   HeaderValidationStrategy headerStrategy,
                                                   int maxErrorRows,
                                                   Consumer<List<T>> dataConsumer,
                                                   Consumer<List<ExcelErrorRow>> errorConsumer) {
        Assert.notNull(inputStream, "InputStream 输入流不可为空");
        Assert.notNull(clazz, "读取的目标实体 Class 类型不可为空");

        HeaderValidationStrategy strategy = headerStrategy != null ? headerStrategy : defaultValidationProperties.getHeaderStrategy();
        int maxErrors = maxErrorRows > 0 ? maxErrorRows : defaultValidationProperties.getMaxErrorRows();

        BatchValidationListener<T> listener = new BatchValidationListener<>(
                clazz,
                batchSize,
                false,
                maxErrors,
                strategy,
                dataConsumer,
                errorConsumer,
                null
        );

        var readerBuilder = FesodSheet.read(inputStream, clazz, listener);
        registerEnumConverters(readerBuilder, clazz);
        readerBuilder.sheet().doRead();
    }

    /**
     * 生成带错误标红与悬浮批注的反馈 Excel 文件
     *
     * @param originalExcelStream  原始上传的 Excel 输入流（不可为空）
     * @param feedbackOutputStream 标红输出流（不可为空）
     * @param errorRows            校验失败错误行集合
     */
    public static void exportErrorFeedback(InputStream originalExcelStream,
                                          OutputStream feedbackOutputStream,
                                          List<ExcelErrorRow> errorRows) {
        try {
            ExcelFeedbackUtil.renderErrorFeedback(originalExcelStream, feedbackOutputStream, errorRows);
        } catch (IOException ex) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(), "生成 Excel 错误反馈文件失败: " + ex.getMessage());
        }
    }

    /**
     * Web 环境下直接输出带错误标红与悬浮批注的反馈 Excel 文件
     *
     * @param response            HTTP 响应（不可为空）
     * @param fileName            下载文件名（自动补全 .xlsx）
     * @param originalExcelStream 原始上传的 Excel 输入流（不可为空）
     * @param errorRows           校验失败错误行集合
     */
    public static void exportErrorFeedback(HttpServletResponse response,
                                          String fileName,
                                          InputStream originalExcelStream,
                                          List<ExcelErrorRow> errorRows) {
        prepareWebResponse(response, fileName);

        try {
            exportErrorFeedback(originalExcelStream, response.getOutputStream(), errorRows);
        } catch (IOException ex) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(), "Web 导出 Excel 错误反馈文件失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 差错数据隔离提取导出：仅将校验失败的数据提取并生成专属纠错 Excel 文件
     * <p>
     * 在原实体列末尾自动追加【失败原因】列并填充错误说明，同时自动将出错的单元格背景标红（浅粉色高亮）并挂载悬浮批注，
     * 业务人员修改后可直接重新上传该纠错表格，避免全量重新上传引发主键或唯一索引冲突。
     * </p>
     *
     * @param outputStream 目标输出流（不可为空）
     * @param clazz        导出的实体类型 Class（不可为空）
     * @param errorRows    校验失败错误行列表（自动提取 rowData 进行去重写出）
     * @param <T>          实体数据泛型
     */
    public static <T> void exportFailedRows(OutputStream outputStream, Class<T> clazz, List<ExcelErrorRow> errorRows) {
        Assert.notNull(errorRows, "错误行列表不可为空");
        Map<Integer, T> uniqueRows = new LinkedHashMap<>();
        for (ExcelErrorRow err : errorRows) {
            if (err.getRowData() != null) {
                @SuppressWarnings("unchecked")
                T rowBean = (T) err.getRowData();
                uniqueRows.putIfAbsent(err.getRowIndex(), rowBean);
            }
        }
        exportFailedRows(outputStream, clazz, new ArrayList<>(uniqueRows.values()), errorRows);
    }

    /**
     * 差错数据隔离提取导出：指定失败实体列表与错误明细导出专属纠错 Excel
     *
     * @param outputStream 目标输出流（不可为空）
     * @param clazz        导出的实体类型 Class（不可为空）
     * @param failedList   失败的实体对象列表
     * @param errorRows    校验失败错误明细列表
     * @param <T>          实体数据泛型
     */
    public static <T> void exportFailedRows(OutputStream outputStream, Class<T> clazz, List<T> failedList, List<ExcelErrorRow> errorRows) {
        Assert.notNull(outputStream, "OutputStream 输出流不可为空");
        Assert.notNull(clazz, "导出的实体 Class 类型不可为空");
        List<T> exportList = (failedList != null) ? failedList : Collections.emptyList();

        var writerBuilder = createStandardWriterBuilder(outputStream, clazz);
        // 挂载差错数据隔离与高亮拦截器
        writerBuilder.registerWriteHandler(new FailSplitRowWriteHandler(errorRows));

        writerBuilder.sheet("纠错清单")
                .doWrite(exportList);
    }

    /**
     * Web 环境下差错数据隔离提取导出并直接写入 HttpServletResponse
     *
     * @param response  HTTP 响应对象（不可为空）
     * @param fileName  导出的文件名（自动补充 .xlsx 后缀）
     * @param clazz     导出的实体类型 Class（不可为空）
     * @param errorRows 校验失败错误行列表
     * @param <T>       实体数据泛型
     */
    public static <T> void exportFailedRows(HttpServletResponse response, String fileName, Class<T> clazz, List<ExcelErrorRow> errorRows) {
        prepareWebResponse(response, fileName);
        try {
            exportFailedRows(response.getOutputStream(), clazz, errorRows);
        } catch (IOException e) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(), "导出 Excel 纠错清单失败: " + e.getMessage(), e);
        }
    }

    /**
     * Web 环境下差错数据隔离提取导出并直接写入 HttpServletResponse（显式指定失败实体列表）
     *
     * @param response   HTTP 响应对象（不可为空）
     * @param fileName   导出的文件名（自动补充 .xlsx 后缀）
     * @param clazz      导出的实体类型 Class（不可为空）
     * @param failedList 失败的实体对象列表
     * @param errorRows  校验失败错误明细列表
     * @param <T>        实体数据泛型
     */
    public static <T> void exportFailedRows(HttpServletResponse response, String fileName, Class<T> clazz, List<T> failedList, List<ExcelErrorRow> errorRows) {
        prepareWebResponse(response, fileName);
        try {
            exportFailedRows(response.getOutputStream(), clazz, failedList, errorRows);
        } catch (IOException e) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(), "导出 Excel 纠错清单失败: " + e.getMessage(), e);
        }
    }

    /**
     * 流式导出带防拍照半透明暗水印的 Excel（写入 OutputStream）
     *
     * @param outputStream  目标输出流（不可为空）
     * @param data          待导出的数据集合
     * @param clazz         导出的实体类型 Class（不可为空）
     * @param watermarkText 水印文本（例如 {@code "机密 · 张三 1001 · 2026-10-17"}）
     * @param <T>           实体数据泛型
     */
    public static <T> void exportWithWatermark(OutputStream outputStream, List<T> data, Class<T> clazz, String watermarkText) {
        exportWithWatermark(outputStream, DEFAULT_SHEET_NAME, data, clazz, watermarkText, null);
    }

    /**
     * 流式导出带防拍照半透明暗水印与只读保护密码的 Excel（写入 OutputStream）
     *
     * @param outputStream       目标输出流（不可为空）
     * @param sheetName          工作表名称
     * @param data               待导出的数据集合
     * @param clazz              导出的实体类型 Class（不可为空）
     * @param watermarkText      水印文本
     * @param protectionPassword 只读密码（为空则不锁定）
     * @param <T>                实体数据泛型
     */
    public static <T> void exportWithWatermark(OutputStream outputStream, String sheetName, List<T> data, Class<T> clazz, String watermarkText, String protectionPassword) {
        Assert.notNull(outputStream, "OutputStream 目标输出流不可为空");
        Assert.notNull(clazz, "导出的实体 Class 类型不可为空");

        String targetSheetName = (sheetName != null && !sheetName.isBlank()) ? sheetName : DEFAULT_SHEET_NAME;
        List<T> exportList = (data != null) ? data : Collections.emptyList();

        var writerBuilder = createStandardWriterBuilder(outputStream, clazz);

        if ((watermarkText != null && !watermarkText.isBlank()) || (protectionPassword != null && !protectionPassword.isBlank())) {
            writerBuilder.registerWriteHandler(new WatermarkSheetWriteHandler(watermarkText, protectionPassword));
        }

        writerBuilder.sheet(targetSheetName)
                .doWrite(exportList);
    }

    /**
     * Web 环境下流式导出带防拍照半透明暗水印的 Excel
     *
     * @param response      HTTP 响应（不可为空）
     * @param fileName      导出的文件名（自动补充 .xlsx 后缀）
     * @param data          待导出的数据集合
     * @param clazz         导出的实体类型 Class（不可为空）
     * @param watermarkText 水印文本
     * @param <T>           实体数据泛型
     */
    public static <T> void exportWithWatermark(HttpServletResponse response, String fileName, List<T> data, Class<T> clazz, String watermarkText) {
        exportWithWatermark(response, fileName, data, clazz, watermarkText, null);
    }

    /**
     * Web 环境下流式导出带防拍照半透明暗水印与只读保护密码的 Excel
     *
     * @param response           HTTP 响应（不可为空）
     * @param fileName           导出的文件名（自动补充 .xlsx 后缀）
     * @param data               待导出的数据集合
     * @param clazz              导出的实体类型 Class（不可为空）
     * @param watermarkText      水印文本
     * @param protectionPassword 只读密码（为空则不锁定）
     * @param <T>                实体数据泛型
     */
    public static <T> void exportWithWatermark(HttpServletResponse response, String fileName, List<T> data, Class<T> clazz, String watermarkText, String protectionPassword) {
        prepareWebResponse(response, fileName);
        try {
            exportWithWatermark(response.getOutputStream(), DEFAULT_SHEET_NAME, data, clazz, watermarkText, protectionPassword);
        } catch (IOException e) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(), "Web 导出带水印 Excel 失败: " + e.getMessage(), e);
        }
    }

    /**
     * 对指定 POI Sheet 工作表实施只读密码保护锁定
     *
     * @param sheet    POI Sheet 对象（不可为空）
     * @param password 锁定保护密码（不可为空）
     */
    public static void protectSheet(org.apache.poi.ss.usermodel.Sheet sheet, String password) {
        if (sheet != null && password != null && !password.isBlank()) {
            sheet.protectSheet(password);
        }
    }

    /**
     * 导出带有智能下拉选择约束与提示说明的空白导入模板（写入 OutputStream）
     *
     * @param outputStream 目标输出流（不可为空）
     * @param clazz        实体类型 Class（不可为空）
     * @param <T>          实体泛型
     */
    public static <T> void exportTemplate(OutputStream outputStream, Class<T> clazz) {
        exportTemplate(outputStream, DEFAULT_SHEET_NAME, clazz);
    }

    /**
     * 导出带有智能下拉选择约束与提示说明的空白导入模板（写入 OutputStream，指定工作表名称）
     *
     * @param outputStream 目标输出流（不可为空）
     * @param sheetName    工作表名称（为空时默认 Sheet1）
     * @param clazz        实体类型 Class（不可为空）
     * @param <T>          实体泛型
     */
    public static <T> void exportTemplate(OutputStream outputStream, String sheetName, Class<T> clazz) {
        Assert.notNull(outputStream, "OutputStream 目标输出流不可为空");
        Assert.notNull(clazz, "实体 Class 类型不可为空");
        String targetSheetName = (sheetName != null && !sheetName.isBlank()) ? sheetName : DEFAULT_SHEET_NAME;

        var writerBuilder = FesodSheet.write(outputStream, clazz)
                .registerWriteHandler(DEFAULT_I18N_HEAD_WRITE_HANDLER)
                .registerWriteHandler(new DropdownSheetWriteHandler(clazz))
                .registerWriteHandler(DEFAULT_PROMPT_HEAD_WRITE_HANDLER)
                .registerWriteHandler(DEFAULT_FORMULA_PROTECTION_WRITE_HANDLER);
        configureStyle(writerBuilder, clazz);
        if (ColumnLockSheetWriteHandler.hasColumnLock(clazz)) {
            writerBuilder.registerWriteHandler(new ColumnLockSheetWriteHandler(clazz));
        }
        writerBuilder.sheet(targetSheetName)
                .doWrite(Collections.emptyList());
    }

    /**
     * Web 环境下直接下载带有智能下拉选择约束与提示说明的空白导入模板
     *
     * @param response 响应对象（不可为空）
     * @param fileName 下载文件名（不可为空，自动补充 .xlsx 后缀）
     * @param clazz    实体类型 Class（不可为空）
     * @param <T>      实体泛型
     */
    public static <T> void exportTemplate(HttpServletResponse response, String fileName, Class<T> clazz) {
        exportTemplate(response, fileName, DEFAULT_SHEET_NAME, clazz);
    }

    /**
     * Web 环境下直接下载带有智能下拉选择约束与提示说明的空白导入模板（指定工作表名称）
     *
     * @param response  响应对象（不可为空）
     * @param fileName  下载文件名（不可为空，自动补充 .xlsx 后缀）
     * @param sheetName 工作表名称（为空时默认 Sheet1）
     * @param clazz     实体类型 Class（不可为空）
     * @param <T>       实体泛型
     */
    public static <T> void exportTemplate(HttpServletResponse response, String fileName, String sheetName, Class<T> clazz) {
        prepareWebResponse(response, fileName);
        Assert.notNull(clazz, "实体 Class 类型不可为空");

        try {
            exportTemplate(response.getOutputStream(), sheetName, clazz);
        } catch (IOException ex) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(), "Web 导出 Excel 模板失败: " + ex.getMessage(), ex);
        }
    }

    // ==================== 复杂报表模板填充 (fill) ====================

    /**
     * 填充数据至预设 Excel 模板并输出至 OutputStream（支持单对象占位符与列表循环）
     *
     * @param out        目标输出流（不可为空）
     * @param templateIn 模板输入流（不可为空）
     * @param data       待填充数据（支持 Collection 列表、Map 混合结构或单对象 JavaBean）
     */
    public static void fill(OutputStream out, InputStream templateIn, Object data) {
        fill(out, templateIn, DEFAULT_SHEET_NAME, data, null);
    }

    /**
     * 填充数据至预设 Excel 模板并输出至 OutputStream（支持自定义填充配置）
     *
     * @param out        目标输出流（不可为空）
     * @param templateIn 模板输入流（不可为空）
     * @param data       待填充数据
     * @param fillConfig 填充配置（如 forceNewRow）
     */
    public static void fill(OutputStream out, InputStream templateIn, Object data, FillConfig fillConfig) {
        fill(out, templateIn, DEFAULT_SHEET_NAME, data, fillConfig);
    }

    /**
     * 填充数据至预设 Excel 模板并输出至 OutputStream，指定目标工作表名称
     *
     * @param out        目标输出流（不可为空）
     * @param templateIn 模板输入流（不可为空）
     * @param sheetName  工作表名称（为空时默认 Sheet1）
     * @param data       待填充数据
     * @param fillConfig 填充配置（可为 null）
     */
    public static void fill(OutputStream out, InputStream templateIn, String sheetName, Object data, FillConfig fillConfig) {
        if (data instanceof Collection<?> list) {
            fill(out, templateIn, sheetName, null, list, fillConfig);
        } else if (data instanceof Map<?, ?> map) {
            Collection<?> extractedList = null;
            Map<Object, Object> simpleMap = new HashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (entry.getValue() instanceof Collection<?> col && extractedList == null) {
                    extractedList = col;
                } else {
                    simpleMap.put(entry.getKey(), entry.getValue());
                }
            }
            fill(out, templateIn, sheetName, simpleMap.isEmpty() ? null : simpleMap, extractedList, fillConfig);
        } else {
            fill(out, templateIn, sheetName, data, null, fillConfig);
        }
    }

    /**
     * 复合填充：同时支持单对象占位符与列表集合循环填充
     *
     * @param out        目标输出流（不可为空）
     * @param templateIn 模板输入流（不可为空）
     * @param sheetName  工作表名称（为空时默认 Sheet1）
     * @param objectData 单对象/Map 占位符数据（可为 null）
     * @param listData   循环列表数据（可为 null）
     * @param fillConfig 填充配置（如 forceNewRow；为 null 时默认 forceNewRow(true)）
     */
    public static void fill(OutputStream out, InputStream templateIn, String sheetName, Object objectData, Collection<?> listData, FillConfig fillConfig) {
        Assert.notNull(out, "OutputStream 目标输出流不可为空");
        Assert.notNull(templateIn, "templateIn 模板输入流不可为空");

        String targetSheetName = (sheetName != null && !sheetName.isBlank()) ? sheetName : DEFAULT_SHEET_NAME;

        try (ExcelWriter excelWriter = FesodSheet.write(out).withTemplate(templateIn).build()) {
            WriteSheet writeSheet = FesodSheet.writerSheet(targetSheetName).build();

            // 1. 优先填充列表数据
            if (listData != null && !listData.isEmpty()) {
                FillConfig config = (fillConfig != null) ? fillConfig : FillConfig.builder().forceNewRow(Boolean.TRUE).build();
                excelWriter.fill(listData, config, writeSheet);
            }

            // 2. 填充单对象/Map 占位符
            if (objectData != null) {
                excelWriter.fill(objectData, writeSheet);
            }

            excelWriter.finish();
        } catch (Exception ex) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(), "Excel 模板填充导出失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * Web 环境下基于 Excel 模板填充数据并直接写入 HttpServletResponse
     *
     * @param response   响应对象（不可为空）
     * @param fileName   导出的文件名（自动补充 .xlsx 后缀）
     * @param templateIn 模板输入流（不可为空）
     * @param data       待填充数据
     */
    public static void fill(HttpServletResponse response, String fileName, InputStream templateIn, Object data) {
        fill(response, fileName, DEFAULT_SHEET_NAME, templateIn, data, null);
    }

    /**
     * Web 环境下基于 Excel 模板填充数据并直接写入 HttpServletResponse（支持自定义填充配置）
     *
     * @param response   响应对象（不可为空）
     * @param fileName   导出的文件名
     * @param templateIn 模板输入流
     * @param data       待填充数据
     * @param fillConfig 填充配置
     */
    public static void fill(HttpServletResponse response, String fileName, InputStream templateIn, Object data, FillConfig fillConfig) {
        fill(response, fileName, DEFAULT_SHEET_NAME, templateIn, data, fillConfig);
    }

    /**
     * Web 环境下基于 Excel 模板填充数据并直接写入 HttpServletResponse（指定工作表名称）
     *
     * @param response   响应对象（不可为空）
     * @param fileName   导出的文件名
     * @param sheetName  工作表名称
     * @param templateIn 模板输入流
     * @param data       待填充数据
     * @param fillConfig 填充配置
     */
    public static void fill(HttpServletResponse response, String fileName, String sheetName, InputStream templateIn, Object data, FillConfig fillConfig) {
        prepareWebResponse(response, fileName);
        try {
            fill(response.getOutputStream(), templateIn, sheetName, data, fillConfig);
        } catch (IOException ex) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(), "Web 模板填充导出失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * Web 环境下复合填充（同时支持单对象占位符与列表循环填充）
     *
     * @param response   响应对象（不可为空）
     * @param fileName   导出的文件名
     * @param sheetName  工作表名称
     * @param templateIn 模板输入流
     * @param objectData 单对象/Map 占位符数据
     * @param listData   列表集合数据
     * @param fillConfig 填充配置
     */
    public static void fill(HttpServletResponse response, String fileName, String sheetName, InputStream templateIn, Object objectData, Collection<?> listData, FillConfig fillConfig) {
        prepareWebResponse(response, fileName);
        try {
            fill(response.getOutputStream(), templateIn, sheetName, objectData, listData, fillConfig);
        } catch (IOException ex) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(), "Web 复合模板填充导出失败: " + ex.getMessage(), ex);
        }
    }

    // ==================== 无实体动态导出 (exportDynamic) ====================

    /**
     * 无实体动态流式导出至 OutputStream
     *
     * @param out      目标输出流（不可为空）
     * @param headList 动态表头（支持 {@code List<String>} 或 {@code List<List<String>>}）
     * @param dataList 动态行数据集合（支持 {@code List<List<?>>} 或 {@code List<Map<String, ?>>}）
     */
    public static void exportDynamic(OutputStream out, List<?> headList, List<?> dataList) {
        exportDynamic(out, DEFAULT_SHEET_NAME, headList, dataList);
    }

    /**
     * 无实体动态流式导出至 OutputStream，指定工作表名称
     *
     * @param out       目标输出流（不可为空）
     * @param sheetName 工作表名称
     * @param headList  动态表头
     * @param dataList  动态行数据集合
     */
    public static void exportDynamic(OutputStream out, String sheetName, List<?> headList, List<?> dataList) {
        Assert.notNull(out, "OutputStream 目标输出流不可为空");
        Assert.notNull(headList, "headList 动态表头定义不可为空");

        String targetSheetName = (sheetName != null && !sheetName.isBlank()) ? sheetName : DEFAULT_SHEET_NAME;
        List<List<String>> fesodHead = normalizeHead(headList);
        List<?> normalizedData = normalizeData(dataList, headList);

        var writerBuilder = FesodSheet.write(out)
                .head(fesodHead)
                .registerWriteHandler(DEFAULT_I18N_HEAD_WRITE_HANDLER)
                .registerWriteHandler(DEFAULT_FORMULA_PROTECTION_WRITE_HANDLER);
        configureStyle(writerBuilder, null);
        writerBuilder.sheet(targetSheetName)
                .doWrite(normalizedData);
    }

    /**
     * 无实体基于 Map 列表与显式列键映射导出至 OutputStream
     *
     * @param out       目标输出流
     * @param sheetName 工作表名称
     * @param headList  动态表头列表
     * @param dataKeys  Map 中对应的键列表（与表头一一对应）
     * @param dataList  Map 数据集合
     */
    public static void exportDynamic(OutputStream out, String sheetName, List<String> headList, List<String> dataKeys, List<Map<String, Object>> dataList) {
        Assert.notNull(out, "OutputStream 目标输出流不可为空");
        Assert.notNull(headList, "headList 动态表头不可为空");

        String targetSheetName = (sheetName != null && !sheetName.isBlank()) ? sheetName : DEFAULT_SHEET_NAME;
        List<List<String>> fesodHead = normalizeHead(headList);
        List<String> keys = (dataKeys != null && !dataKeys.isEmpty()) ? dataKeys : headList;

        List<List<Object>> rows = new ArrayList<>();
        if (dataList != null) {
            for (Map<String, Object> map : dataList) {
                List<Object> row = new ArrayList<>(keys.size());
                for (String key : keys) {
                    row.add(map != null ? map.get(key) : null);
                }
                rows.add(row);
            }
        }

        var writerBuilder = FesodSheet.write(out)
                .head(fesodHead)
                .registerWriteHandler(DEFAULT_I18N_HEAD_WRITE_HANDLER)
                .registerWriteHandler(DEFAULT_FORMULA_PROTECTION_WRITE_HANDLER);
        configureStyle(writerBuilder, null);
        writerBuilder.sheet(targetSheetName)
                .doWrite(rows);
    }

    /**
     * Web 环境下无实体动态流式导出并直接写入 HttpServletResponse
     *
     * @param response 响应对象（不可为空）
     * @param fileName 导出的文件名
     * @param headList 动态表头
     * @param dataList 动态行数据集合
     */
    public static void exportDynamic(HttpServletResponse response, String fileName, List<?> headList, List<?> dataList) {
        exportDynamic(response, fileName, DEFAULT_SHEET_NAME, headList, dataList);
    }

    /**
     * Web 环境下无实体动态流式导出并直接写入 HttpServletResponse（指定工作表名称）
     *
     * @param response  响应对象（不可为空）
     * @param fileName  导出的文件名
     * @param sheetName 工作表名称
     * @param headList  动态表头
     * @param dataList  动态行数据集合
     */
    public static void exportDynamic(HttpServletResponse response, String fileName, String sheetName, List<?> headList, List<?> dataList) {
        prepareWebResponse(response, fileName);
        try {
            exportDynamic(response.getOutputStream(), sheetName, headList, dataList);
        } catch (IOException ex) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(), "Web 动态导出 Excel 失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * Web 环境下基于 Map 列表与显式列键映射动态导出至 HttpServletResponse
     *
     * @param response  响应对象
     * @param fileName  导出的文件名
     * @param sheetName 工作表名称
     * @param headList  动态表头列表
     * @param dataKeys  Map 对应的键列表
     * @param dataList  Map 数据集合
     */
    public static void exportDynamic(HttpServletResponse response, String fileName, String sheetName, List<String> headList, List<String> dataKeys, List<Map<String, Object>> dataList) {
        prepareWebResponse(response, fileName);
        try {
            exportDynamic(response.getOutputStream(), sheetName, headList, dataKeys, dataList);
        } catch (IOException ex) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(), "Web 动态导出 Excel 失败: " + ex.getMessage(), ex);
        }
    }

    // ==================== 多 Sheet Fluent 构建器 (builder) ====================

    /**
     * 创建基于 OutputStream 的多 Sheet 链式 Fluent 构建器
     *
     * @param out 目标输出流（不可为空）
     * @return 链式构建器实例
     */
    public static ExcelBuilder builder(OutputStream out) {
        return new ExcelBuilder(out, ExcelUtil::configureSheet);
    }

    /**
     * Web 环境下创建基于 HttpServletResponse 的多 Sheet 链式 Fluent 构建器
     *
     * @param response 响应对象（不可为空）
     * @param fileName 导出的文件名（自动补充 .xlsx 后缀）
     * @return 链式构建器实例
     */
    public static ExcelBuilder builder(HttpServletResponse response, String fileName) {
        prepareWebResponse(response, fileName);
        try {
            return new ExcelBuilder(response.getOutputStream(), ExcelUtil::configureSheet);
        } catch (IOException ex) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(), "创建 ExcelBuilder 失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 创建基于 InputStream 的异构多工作表 Fluent 流式读取构建器
     *
     * @param inputStream 目标输入流（不可为空）
     * @return 链式多工作表读取构建器实例
     */
    public static ExcelReaderBuilder reader(InputStream inputStream) {
        return new ExcelReaderBuilder(inputStream, defaultBatchSize, maxSyncRows);
    }

    /**
     * 创建基于 InputStream 与密码的异构多工作表 Fluent 流式读取构建器
     *
     * @param inputStream 目标输入流（不可为空）
     * @param password    工作簿访问密码
     * @return 链式多工作表读取构建器实例
     */
    public static ExcelReaderBuilder reader(InputStream inputStream, String password) {
        return new ExcelReaderBuilder(inputStream, defaultBatchSize, maxSyncRows).password(password);
    }

    // ==================== 动态数据规范化工具方法 ====================

    /**
     * 将动态表头入参规范化为 Apache Fesod 原生支持的 {@code List<List<String>>} 结构
     *
     * @param headList 动态表头（支持 {@code List<String>} 或嵌套 {@code List<List<String>>}）
     * @return 规范化的嵌套表头集合
     */
    public static List<List<String>> normalizeHead(List<?> headList) {
        if (headList == null || headList.isEmpty()) {
            return Collections.emptyList();
        }
        List<List<String>> result = new ArrayList<>(headList.size());
        for (Object item : headList) {
            if (item instanceof Collection<?> col) {
                List<String> inner = new ArrayList<>(col.size());
                for (Object sub : col) {
                    inner.add(sub != null ? String.valueOf(sub) : "");
                }
                result.add(inner);
            } else {
                result.add(Collections.singletonList(item != null ? String.valueOf(item) : ""));
            }
        }
        return result;
    }

    /**
     * 将动态数据集合规范化为可供流式导出的行值列表
     *
     * @param dataList 动态原始数据集合
     * @param headList 对应表头定义
     * @return 规范化后的二维行列表
     */
    public static List<?> normalizeData(List<?> dataList, List<?> headList) {
        if (dataList == null || dataList.isEmpty()) {
            return Collections.emptyList();
        }
        Object first = dataList.get(0);
        if (first instanceof Collection<?> || first instanceof Object[]) {
            List<List<Object>> rows = new ArrayList<>(dataList.size());
            for (Object row : dataList) {
                if (row instanceof Collection<?> col) {
                    rows.add(new ArrayList<>(col));
                } else if (row instanceof Object[] arr) {
                    List<Object> list = new ArrayList<>(arr.length);
                    Collections.addAll(list, arr);
                    rows.add(list);
                }
            }
            return rows;
        }
        if (first instanceof Map<?, ?>) {
            List<String> flatHeaders = extractFlatHeaders(headList);
            List<List<Object>> rows = new ArrayList<>(dataList.size());
            for (Object row : dataList) {
                if (row instanceof Map<?, ?> map) {
                    List<Object> rowValues = new ArrayList<>();
                    boolean matchedAnyHeader = false;
                    for (String header : flatHeaders) {
                        if (map.containsKey(header)) {
                            matchedAnyHeader = true;
                            break;
                        }
                    }
                    if (matchedAnyHeader) {
                        for (String header : flatHeaders) {
                            rowValues.add(map.get(header));
                        }
                    } else {
                        rowValues.addAll(map.values());
                    }
                    rows.add(rowValues);
                }
            }
            return rows;
        }
        return dataList;
    }

    private static List<String> extractFlatHeaders(List<?> headList) {
        if (headList == null || headList.isEmpty()) {
            return Collections.emptyList();
        }
        List<String> flat = new ArrayList<>(headList.size());
        for (Object item : headList) {
            if (item instanceof List<?> list && !list.isEmpty()) {
                flat.add(String.valueOf(list.get(list.size() - 1)));
            } else {
                flat.add(item != null ? String.valueOf(item) : "");
            }
        }
        return flat;
    }

    /**
     * 统一配置遵循 RFC 5987 / RFC 6266 标准的 Web 下载响应头与 MIME 类型
     *
     * @param response 响应对象
     * @param fileName 下载文件名
     */
    private static void prepareWebResponse(HttpServletResponse response, String fileName) {
        Assert.notNull(response, "HttpServletResponse 响应对象不可为空");
        Assert.hasText(fileName, "导出文件名不可为空");

        response.setContentType(XLSX_CONTENT_TYPE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        String safeFileName = fileName.endsWith(XLSX_EXTENSION) ? fileName : fileName + XLSX_EXTENSION;
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(safeFileName, StandardCharsets.UTF_8)
                .build();
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, disposition.toString());
    }

    /**
     * 自动向 ParameterBuilder 注册实体类关联枚举类型的双向转换器（使用类级并发缓存）
     *
     * @param builder ParameterBuilder (ExcelWriterBuilder 或 ExcelReaderBuilder)
     * @param clazz   实体 Class
     */
    public static void registerEnumConverters(AbstractParameterBuilder<?, ?> builder, Class<?> clazz) {
        if (builder == null) {
            return;
        }
        builder.registerConverter(DEFAULT_FORMULA_PROTECTION_STRING_CONVERTER);
        if (clazz == null) {
            return;
        }
        Set<Class<?>> enumClasses = EnumMappingCache.getEntityEnumClasses(clazz);
        for (Class<?> enumClass : enumClasses) {
            builder.registerConverter(new ExcelEnumConverter(enumClass, null, null));
            builder.registerConverter(new ExcelEnumConverter(enumClass, null, CellDataTypeEnum.STRING));
        }
    }

    /**
     * 配置实体模型 Sheet 级拦截器与枚举转换器
     */
    private static void configureSheet(org.apache.fesod.sheet.write.builder.ExcelWriterSheetBuilder sheetBuilder, Class<?> headClass) {
        if (sheetBuilder == null || headClass == null) {
            return;
        }
        sheetBuilder.registerWriteHandler(new DropdownSheetWriteHandler(headClass));
        if (ColumnLockSheetWriteHandler.hasColumnLock(headClass)) {
            sheetBuilder.registerWriteHandler(new ColumnLockSheetWriteHandler(headClass));
        }
        registerEnumConverters(sheetBuilder, headClass);
    }

    private static final Map<Class<?>, List<String>> DECLARED_FIELD_NAMES_CACHE = new ConcurrentHashMap<>();

    /**
     * 获取类及其父类中所有参与 Excel 导出的声明字段名称列表（按声明顺序与 @ExcelProperty 索引排序）
     *
     * @param clazz 目标 VO 类型
     * @return 字段名称列表
     */
    public static List<String> getDeclaredFieldNames(Class<?> clazz) {
        if (clazz == null || clazz == Object.class) {
            return Collections.emptyList();
        }
        return DECLARED_FIELD_NAMES_CACHE.computeIfAbsent(clazz, ExcelUtil::scanDeclaredFieldNames);
    }

    private static List<String> scanDeclaredFieldNames(Class<?> clazz) {
        List<Class<?>> hierarchy = new ArrayList<>();
        Class<?> current = clazz;
        while (current != null && current != Object.class) {
            hierarchy.add(0, current); // 父类在前
            current = current.getSuperclass();
        }
        List<Field> allFields = new ArrayList<>();
        for (Class<?> clz : hierarchy) {
            for (Field field : clz.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && !Modifier.isTransient(field.getModifiers())) {
                    if (!field.isAnnotationPresent(ExcelIgnore.class)
                            && !field.isAnnotationPresent(ExcelIgnoreUnannotated.class)) {
                        allFields.add(field);
                    }
                }
            }
        }
        // 按照 @ExcelProperty(index) 排序：显式指定 index 的按 index 排序，未指定或负数的排在后面并保持声明顺序
        List<Field> sortedFields = new ArrayList<>(allFields);
        sortedFields.sort((f1, f2) -> {
            ExcelProperty p1 = f1.getAnnotation(ExcelProperty.class);
            ExcelProperty p2 = f2.getAnnotation(ExcelProperty.class);
            int idx1 = (p1 != null && p1.index() >= 0) ? p1.index() : Integer.MAX_VALUE;
            int idx2 = (p2 != null && p2.index() >= 0) ? p2.index() : Integer.MAX_VALUE;
            if (idx1 != idx2) {
                return Integer.compare(idx1, idx2);
            }
            return Integer.compare(allFields.indexOf(f1), allFields.indexOf(f2));
        });

        List<String> fieldNames = new ArrayList<>(sortedFields.size());
        for (Field f : sortedFields) {
            fieldNames.add(f.getName());
        }
        return Collections.unmodifiableList(fieldNames);
    }

    /**
     * 计算并解析最终生效的投影字段名称列表
     * <p>
     * 规则说明：
     * 1. 当 includeColumns 与 excludeColumns 均为空或 null 时，返回 null，表示不执行自定义列裁剪；
     * 2. 当 includeColumns 为有序集合（如 List 或 LinkedHashSet）时，优先保留调用方指定的列顺序；
     * 3. 当 includeColumns 为无序集合（如 Set.of 或 HashSet）时，按实体类默认自然声明顺序排列；
     * 4. 自动剔除实体中不存在的非法字段（防御性容错），并排除 excludeColumns 中的字段；
     * 5. 若过滤后无任何有效保留列，返回 null，平滑回退默认行为。
     * </p>
     *
     * @param clazz          目标实体 Class
     * @param includeColumns 包含列白名单
     * @param excludeColumns 排除列黑名单
     * @return 最终生效的有序字段名称列表；若无需投影则返回 null
     */
    public static List<String> resolveProjectedColumns(Class<?> clazz,
                                                       Collection<String> includeColumns,
                                                       Collection<String> excludeColumns) {
        if (clazz == null || clazz == Object.class) {
            return null;
        }
        boolean hasIncludes = (includeColumns != null && !includeColumns.isEmpty());
        boolean hasExcludes = (excludeColumns != null && !excludeColumns.isEmpty());
        if (!hasIncludes && !hasExcludes) {
            return null;
        }

        List<String> allDeclared = getDeclaredFieldNames(clazz);
        if (allDeclared.isEmpty()) {
            return null;
        }

        Set<String> declaredSet = new HashSet<>(allDeclared);
        Set<String> excludeSet = hasExcludes ? new HashSet<>(excludeColumns) : Collections.emptySet();
        List<String> projected = new ArrayList<>();

        if (hasIncludes) {
            boolean isExplicitlyOrdered = (includeColumns instanceof List || includeColumns instanceof LinkedHashSet);
            if (isExplicitlyOrdered) {
                for (String col : includeColumns) {
                    if (col != null && declaredSet.contains(col) && !excludeSet.contains(col) && !projected.contains(col)) {
                        projected.add(col);
                    }
                }
            } else {
                Set<String> includeSet = new HashSet<>(includeColumns);
                for (String col : allDeclared) {
                    if (includeSet.contains(col) && !excludeSet.contains(col)) {
                        projected.add(col);
                    }
                }
            }
        } else {
            for (String col : allDeclared) {
                if (!excludeSet.contains(col)) {
                    projected.add(col);
                }
            }
        }

        return projected.isEmpty() ? null : projected;
    }
}


