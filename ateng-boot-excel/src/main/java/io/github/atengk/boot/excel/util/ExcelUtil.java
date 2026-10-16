package io.github.atengk.boot.excel.util;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.excel.handler.SensitiveCellWriteHandler;
import io.github.atengk.boot.excel.properties.ExcelProperties;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.fesod.sheet.FesodSheet;
import org.apache.fesod.sheet.context.AnalysisContext;
import org.apache.fesod.sheet.read.listener.ReadListener;
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

        FesodSheet.write(outputStream, clazz)
                .registerWriteHandler(DEFAULT_SENSITIVE_WRITE_HANDLER)
                .sheet(targetSheetName)
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
        Assert.notNull(response, "HttpServletResponse 响应对象不可为空");
        Assert.hasText(fileName, "导出文件名不可为空");
        Assert.notNull(clazz, "导出的实体 Class 类型不可为空");

        // 1. 设置标准响应头与编码
        response.setContentType(XLSX_CONTENT_TYPE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        String safeFileName = fileName.endsWith(XLSX_EXTENSION) ? fileName : fileName + XLSX_EXTENSION;
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(safeFileName, StandardCharsets.UTF_8)
                .build();
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, disposition.toString());

        // 2. 流式写出数据
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

        List<T> list = FesodSheet.read(inputStream)
                .head(clazz)
                .sheet()
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

        FesodSheet.read(inputStream, clazz, listener)
                .sheet()
                .doRead();
    }
}
