package io.github.atengk.boot.excel.util;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.excel.annotation.ExcelEnum;
import io.github.atengk.boot.excel.converter.EnumMappingCache;
import io.github.atengk.boot.excel.converter.ExcelEnumConverter;
import io.github.atengk.boot.excel.exception.ExcelValidationException;
import io.github.atengk.boot.excel.handler.DropdownSheetWriteHandler;
import io.github.atengk.boot.excel.handler.PromptHeadWriteHandler;
import io.github.atengk.boot.excel.handler.SensitiveCellWriteHandler;
import io.github.atengk.boot.excel.listener.BatchValidationListener;
import io.github.atengk.boot.excel.model.ExcelErrorRow;
import io.github.atengk.boot.excel.model.ExcelReadResult;
import io.github.atengk.boot.excel.properties.ExcelProperties;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.fesod.sheet.FesodSheet;
import org.apache.fesod.sheet.context.AnalysisContext;
import org.apache.fesod.sheet.enums.CellDataTypeEnum;
import org.apache.fesod.sheet.metadata.AbstractParameterBuilder;
import org.apache.fesod.sheet.read.builder.ExcelReaderBuilder;
import org.apache.fesod.sheet.read.listener.ReadListener;
import org.apache.fesod.sheet.write.builder.ExcelWriterBuilder;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.util.Assert;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

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
     * 流式读取全局默认批次大小（可通过配置文件 ateng.boot.excel.default-batch-size 调整）
     */
    private static volatile int defaultBatchSize = 1000;

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
            if (properties.getTempDir() != null && !properties.getTempDir().isBlank()) {
                System.setProperty("java.io.tmpdir", properties.getTempDir());
            }
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
     * 流式导出数据至指定的 OutputStream，并指定 Sheet 名称
     *
     * @param outputStream 目标输出流（不可为空）
     * @param sheetName    工作表名称（为空时默认 Sheet1）
     * @param data         待导出的数据集合（为 null 时视为空集合）
     * @param clazz        导出的实体类型 Class（不可为空）
     * @param <T>          实体数据泛型
     */
    public static <T> void export(OutputStream outputStream, String sheetName, List<T> data, Class<T> clazz) {
        Assert.notNull(outputStream, "OutputStream 目标输出流不可为空");
        Assert.notNull(clazz, "导出的实体 Class 类型不可为空");

        String targetSheetName = (sheetName != null && !sheetName.isBlank()) ? sheetName : DEFAULT_SHEET_NAME;
        List<T> exportList = (data != null) ? data : Collections.emptyList();

        var writerBuilder = FesodSheet.write(outputStream, clazz)
                .registerWriteHandler(DEFAULT_SENSITIVE_WRITE_HANDLER);
        registerEnumConverters(writerBuilder, clazz);

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
        export(response, fileName, DEFAULT_SHEET_NAME, data, clazz);
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
        prepareWebResponse(response, fileName);
        Assert.notNull(clazz, "导出的实体 Class 类型不可为空");

        try {
            export(response.getOutputStream(), sheetName, data, clazz);
        } catch (IOException e) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(), "Excel 导出写入响应流失败: " + e.getMessage(), e);
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
        return (list != null) ? list : Collections.emptyList();
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
     * 强类型读取 Excel 并执行 JSR-303 行级数据校验（默认错误聚合模式）
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
        return readWithValidation(inputStream, clazz, false);
    }

    /**
     * 强类型读取 Excel 并执行 JSR-303 行级数据校验
     *
     * @param inputStream 输入流（不可为空）
     * @param clazz       读取映射的目标实体 Class（不可为空）
     * @param failFast    是否快速失败（true: 遇到第一个错误立即抛出异常中断；false: 聚合所有错误行）
     * @param <T>         实体数据泛型
     * @return 解析成功的合法实体数据集合
     * @throws ExcelValidationException 当存在行级校验错误时抛出
     */
    public static <T> List<T> readWithValidation(InputStream inputStream, Class<T> clazz, boolean failFast) {
        Assert.notNull(inputStream, "InputStream 输入流不可为空");
        Assert.notNull(clazz, "读取的目标实体 Class 类型不可为空");

        BatchValidationListener<T> listener = new BatchValidationListener<>(clazz, failFast);
        var readerBuilder = FesodSheet.read(inputStream, clazz, listener);
        registerEnumConverters(readerBuilder, clazz);
        readerBuilder.sheet()
                .doRead();

        if (listener.hasErrors()) {
            throw new ExcelValidationException(listener.getErrorList());
        }

        return listener.getValidDataList();
    }

    /**
     * 强类型读取 Excel 并返回包含合法数据与全部错误行的复合结果（不抛异常）
     *
     * @param inputStream 输入流（不可为空）
     * @param clazz       读取映射的目标实体 Class（不可为空）
     * @param <T>         实体数据泛型
     * @return 包含合法数据与错误行信息的 {@link ExcelReadResult}
     */
    public static <T> ExcelReadResult<T> readWithResult(InputStream inputStream, Class<T> clazz) {
        Assert.notNull(inputStream, "InputStream 输入流不可为空");
        Assert.notNull(clazz, "读取的目标实体 Class 类型不可为空");

        BatchValidationListener<T> listener = new BatchValidationListener<>(clazz, false);
        var readerBuilder = FesodSheet.read(inputStream, clazz, listener);
        registerEnumConverters(readerBuilder, clazz);
        readerBuilder.sheet()
                .doRead();

        return ExcelReadResult.<T>builder()
                .data(new ArrayList<>(listener.getValidDataList()))
                .errors(new ArrayList<>(listener.getErrorList()))
                .build();
    }

    /**
     * 流式分批读取 Excel 并逐行执行 JSR-303 数据校验
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
        Assert.notNull(inputStream, "InputStream 输入流不可为空");
        Assert.notNull(clazz, "读取的目标实体 Class 类型不可为空");

        BatchValidationListener<T> listener = new BatchValidationListener<>(
                clazz, batchSize, false, dataConsumer, errorConsumer, null
        );

        var readerBuilder = FesodSheet.read(inputStream, clazz, listener);
        registerEnumConverters(readerBuilder, clazz);
        readerBuilder.sheet()
                .doRead();
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

        FesodSheet.write(outputStream, clazz)
                .registerWriteHandler(new DropdownSheetWriteHandler(clazz))
                .registerWriteHandler(DEFAULT_PROMPT_HEAD_WRITE_HANDLER)
                .sheet(targetSheetName)
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
    private static void registerEnumConverters(AbstractParameterBuilder<?, ?> builder, Class<?> clazz) {
        if (builder == null || clazz == null) {
            return;
        }
        Set<Class<?>> enumClasses = EnumMappingCache.getEntityEnumClasses(clazz);
        for (Class<?> enumClass : enumClasses) {
            builder.registerConverter(new ExcelEnumConverter(enumClass, null, null));
            builder.registerConverter(new ExcelEnumConverter(enumClass, null, CellDataTypeEnum.STRING));
        }
    }
}


