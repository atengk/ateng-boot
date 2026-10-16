package io.github.atengk.boot.excel.builder;

import io.github.atengk.boot.excel.handler.MergeCellWriteHandler;
import io.github.atengk.boot.excel.handler.PromptHeadWriteHandler;
import io.github.atengk.boot.excel.handler.SensitiveCellWriteHandler;
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
    private int currentSheetNo = 0;
    private boolean finished = false;

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
                .registerWriteHandler(new SensitiveCellWriteHandler())
                .registerWriteHandler(new PromptHeadWriteHandler())
                .registerWriteHandler(new MergeCellWriteHandler())
                .build();
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
        WriteSheet writeSheet = sheetBuilder.build();
        Collection<T> writeData = (data != null) ? data : Collections.emptyList();
        excelWriter.write(writeData, writeSheet);
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
        WriteSheet writeSheet = sheetBuilder.build();
        Collection<?> writeData = (normalizedData != null) ? (Collection<?>) normalizedData : Collections.emptyList();
        excelWriter.write(writeData, writeSheet);
        return this;
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
