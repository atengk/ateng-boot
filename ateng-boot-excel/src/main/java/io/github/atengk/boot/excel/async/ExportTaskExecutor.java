package io.github.atengk.boot.excel.async;

import java.io.OutputStream;

/**
 * 异步导出任务具体执行回调接口
 *
 * @author Ateng
 * @since 2026-10-17
 */
@FunctionalInterface
public interface ExportTaskExecutor {

    /**
     * 执行具体的 Excel 流式生成写出
     *
     * @param outputStream 目标文件输出流
     * @throws Exception 写出异常
     */
    void execute(OutputStream outputStream) throws Exception;
}
