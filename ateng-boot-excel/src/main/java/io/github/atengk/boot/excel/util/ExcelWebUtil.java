package io.github.atengk.boot.excel.util;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.excel.builder.ExcelBuilder;
import io.github.atengk.boot.excel.model.ExcelErrorRow;
import io.github.atengk.boot.excel.streaming.StreamingPageExportTemplate;
import jakarta.servlet.http.HttpServletResponse;
import org.apache.fesod.sheet.write.metadata.fill.FillConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.Assert;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Excel Web HTTP 流式导出与下载专用门面工具类
 * <p>
 * 提供 Spring 标准 {@link ResponseEntity} 与 {@link StreamingResponseBody} 响应生成（一等公民），
 * 以及原生 {@link HttpServletResponse} 流式写出能力。
 * 全面遵循 RFC 5987 / RFC 6266 规范装配防乱码响应头与标准 XLSX MIME 类型，
 * 并对多容器（Tomcat、Jetty、Undertow 等）客户端网络中断异常（如 {@code ClientAbortException}、{@code Broken pipe} 等）
 * 实施优雅识别与抑制，杜绝 500 告警风暴，同时保障物理临时资源与清理钩子的绝对可靠执行。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-28
 */
public final class ExcelWebUtil {

    private static final Logger log = LoggerFactory.getLogger(ExcelWebUtil.class);

    /**
     * 默认工作表名称
     */
    public static final String DEFAULT_SHEET_NAME = "Sheet1";

    /**
     * 标准 XLSX MIME 类型字符串（UTF-8 字符集）
     */
    public static final String XLSX_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet;charset=utf-8";

    /**
     * 标准 XLSX MediaType 实例
     */
    public static final MediaType XLSX_MEDIA_TYPE = MediaType.parseMediaType(XLSX_CONTENT_TYPE);

    /**
     * 标准 XLSX 文件后缀
     */
    public static final String XLSX_EXTENSION = ".xlsx";

    private ExcelWebUtil() {
    }

    // ==================== 客户端中断防御与异常识别 ====================

    /**
     * 深度检查异常或其根因链是否由客户端提前中断/重置网络连接所引起
     * <p>
     * 覆盖主流 Servlet 容器与协议实现特征：
     * <ul>
     *     <li>Tomcat: {@code org.apache.catalina.connector.ClientAbortException}</li>
     *     <li>Jetty: {@code org.eclipse.jetty.io.EofException}</li>
     *     <li>Spring: {@code org.springframework.web.context.request.async.AsyncRequestNotUsableException}</li>
     *     <li>底层网络: "Broken pipe"、"Connection reset"、"An established connection was aborted" 等特征特征码</li>
     * </ul>
     * </p>
     *
     * @param throwable 待检测的异常对象
     * @return 若由客户端提前中止引发则返回 {@code true}，否则返回 {@code false}
     */
    public static boolean isClientAbortException(Throwable throwable) {
        if (throwable == null) {
            return false;
        }
        Throwable current = throwable;
        while (current != null) {
            String className = current.getClass().getName();
            String simpleName = current.getClass().getSimpleName();

            // 1. 知名 Servlet 容器客户端中止异常类名检查
            if ("ClientAbortException".equals(simpleName)
                    || "EofException".equals(simpleName)
                    || "AsyncRequestNotUsableException".equals(simpleName)
                    || className.contains("ClientAbortException")
                    || className.contains("jetty.io.EofException")) {
                return true;
            }

            // 2. 管道中断与连接重置网络异常特征检测
            String message = current.getMessage();
            if (message != null && !message.isBlank()) {
                String lower = message.toLowerCase(Locale.ROOT);
                if (lower.contains("broken pipe")
                        || lower.contains("connection reset")
                        || lower.contains("connection was aborted")
                        || lower.contains("connection abort")
                        || lower.contains("closed by the remote host")
                        || lower.contains("an established connection was aborted")
                        || lower.contains("stream is closed")
                        || lower.contains("client abort")
                        || lower.contains("premature end of chunk coded message")) {
                    return true;
                }
            }
            current = current.getCause();
        }
        return false;
    }

    // ==================== HTTP 响应头装配 ====================

    /**
     * 构造遵循 RFC 5987 / RFC 6266 标准的 Excel 下载 HTTP 响应头
     *
     * @param fileName 下载文件名（不可为空，缺失 .xlsx 后缀时自动补齐）
     * @return 预置完整防乱码与安全缓存策略的 HttpHeaders
     */
    public static HttpHeaders createDownloadHeaders(String fileName) {
        Assert.hasText(fileName, "导出文件名不可为空");

        String safeFileName = fileName.endsWith(XLSX_EXTENSION) ? fileName : fileName + XLSX_EXTENSION;
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(safeFileName, StandardCharsets.UTF_8)
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(disposition);
        headers.setContentType(XLSX_MEDIA_TYPE);
        headers.set(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate");
        headers.setPragma("no-cache");
        headers.setExpires(0L);
        return headers;
    }

    /**
     * 对原生 HttpServletResponse 配置遵循 RFC 5987 / RFC 6266 标准的 Excel 下载响应头与 MIME 类型
     *
     * @param response 响应对象（不可为空）
     * @param fileName 下载文件名（不可为空，缺失 .xlsx 后缀时自动补齐）
     */
    public static void prepareWebResponse(HttpServletResponse response, String fileName) {
        Assert.notNull(response, "HttpServletResponse 响应对象不可为空");
        Assert.hasText(fileName, "导出文件名不可为空");

        response.setContentType(XLSX_CONTENT_TYPE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        String safeFileName = fileName.endsWith(XLSX_EXTENSION) ? fileName : fileName + XLSX_EXTENSION;
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(safeFileName, StandardCharsets.UTF_8)
                .build();

        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, disposition.toString());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        response.setDateHeader(HttpHeaders.EXPIRES, 0);
    }

    // ==================== Spring ResponseEntity<StreamingResponseBody> 一等公民 ====================

    /**
     * 将自定义流式写出逻辑包装为具备客户端中断防卫与防乱码响应头的 Spring 原生 {@link ResponseEntity}
     *
     * @param fileName 下载文件名（自动补充 .xlsx 后缀）
     * @param body     流式写出任务
     * @return Spring 强类型响应实体
     */
    public static ResponseEntity<StreamingResponseBody> toResponseEntity(String fileName, StreamingResponseBody body) {
        return toResponseEntity(fileName, body, null);
    }

    /**
     * 将自定义流式写出逻辑包装为具备客户端中断防卫、防乱码响应头与确定性清理钩子的 Spring 原生 {@link ResponseEntity}
     *
     * @param fileName    下载文件名（自动补充 .xlsx 后缀）
     * @param body        流式写出任务
     * @param cleanupHook 无论流式写出成功、失败或被客户端中断后，均无条件执行的资源确定性清理钩子（可为 null）
     * @return Spring 强类型响应实体
     */
    public static ResponseEntity<StreamingResponseBody> toResponseEntity(String fileName, StreamingResponseBody body, Runnable cleanupHook) {
        Assert.hasText(fileName, "导出文件名不可为空");
        Assert.notNull(body, "StreamingResponseBody 响应体不可为空");

        HttpHeaders headers = createDownloadHeaders(fileName);
        StreamingResponseBody safeBody = outputStream -> {
            try {
                body.writeTo(outputStream);
            } catch (Throwable t) {
                if (isClientAbortException(t)) {
                    log.debug("客户端提前中止/重置 Excel 下载网络连接: {}", t.getMessage());
                    return;
                }
                if (t instanceof IOException ioEx) {
                    throw ioEx;
                }
                if (t instanceof RuntimeException runEx) {
                    throw runEx;
                }
                throw new IOException("Excel 流式写出失败: " + t.getMessage(), t);
            } finally {
                if (cleanupHook != null) {
                    try {
                        cleanupHook.run();
                    } catch (Throwable hookEx) {
                        log.warn("执行 Excel 下载清理钩子 (cleanupHook) 失败: {}", hookEx.getMessage(), hookEx);
                    }
                }
            }
        };

        return ResponseEntity.ok()
                .headers(headers)
                .contentType(XLSX_MEDIA_TYPE)
                .body(safeBody);
    }

    /**
     * 将实体数据集流式导出为 Spring 原生 {@link ResponseEntity}
     *
     * @param fileName 导出的文件名（自动补充 .xlsx 后缀）
     * @param data     待导出的数据集合
     * @param clazz    导出的实体类型 Class（不可为空）
     * @param <T>      实体数据泛型
     * @return Spring 响应实体
     */
    public static <T> ResponseEntity<StreamingResponseBody> toResponseEntity(String fileName, List<T> data, Class<T> clazz) {
        return toResponseEntity(fileName, DEFAULT_SHEET_NAME, data, clazz, null, null, null);
    }

    /**
     * 将实体数据集流式导出为 Spring 原生 {@link ResponseEntity}（类型在前重载）
     *
     * @param fileName 导出的文件名（自动补充 .xlsx 后缀）
     * @param clazz    导出的实体类型 Class（不可为空）
     * @param data     待导出的数据集合
     * @param <T>      实体数据泛型
     * @return Spring 响应实体
     */
    public static <T> ResponseEntity<StreamingResponseBody> toResponseEntity(String fileName, Class<T> clazz, List<T> data) {
        return toResponseEntity(fileName, DEFAULT_SHEET_NAME, data, clazz, null, null, null);
    }

    /**
     * 将实体数据集流式导出为 Spring 原生 {@link ResponseEntity}，并指定包含列
     *
     * @param fileName       导出的文件名（自动补充 .xlsx 后缀）
     * @param data           待导出的数据集合
     * @param clazz          导出的实体类型 Class（不可为空）
     * @param includeColumns 包含的属性字段名称集合（为空或 null 则不限制）
     * @param <T>            实体数据泛型
     * @return Spring 响应实体
     */
    public static <T> ResponseEntity<StreamingResponseBody> toResponseEntity(String fileName, List<T> data, Class<T> clazz,
                                                                             Collection<String> includeColumns) {
        return toResponseEntity(fileName, DEFAULT_SHEET_NAME, data, clazz, includeColumns, null, null);
    }

    /**
     * 将实体数据集流式导出为 Spring 原生 {@link ResponseEntity}，并指定包含与排除列
     *
     * @param fileName       导出的文件名（自动补充 .xlsx 后缀）
     * @param data           待导出的数据集合
     * @param clazz          导出的实体类型 Class（不可为空）
     * @param includeColumns 包含的属性字段名称集合（为空或 null 则不限制）
     * @param excludeColumns 排除的属性字段名称集合（为空或 null 则不限制）
     * @param <T>            实体数据泛型
     * @return Spring 响应实体
     */
    public static <T> ResponseEntity<StreamingResponseBody> toResponseEntity(String fileName, List<T> data, Class<T> clazz,
                                                                             Collection<String> includeColumns, Collection<String> excludeColumns) {
        return toResponseEntity(fileName, DEFAULT_SHEET_NAME, data, clazz, includeColumns, excludeColumns, null);
    }

    /**
     * 将实体数据集流式导出为 Spring 原生 {@link ResponseEntity}，并指定工作表名称
     *
     * @param fileName  导出的文件名（自动补充 .xlsx 后缀）
     * @param sheetName 工作表名称（为空时默认 Sheet1）
     * @param data      待导出的数据集合
     * @param clazz     导出的实体类型 Class（不可为空）
     * @param <T>       实体数据泛型
     * @return Spring 响应实体
     */
    public static <T> ResponseEntity<StreamingResponseBody> toResponseEntity(String fileName, String sheetName, List<T> data, Class<T> clazz) {
        return toResponseEntity(fileName, sheetName, data, clazz, null, null, null);
    }

    /**
     * 将实体数据集流式导出为 Spring 原生 {@link ResponseEntity}，并指定工作表名称与包含列
     *
     * @param fileName       导出的文件名（自动补充 .xlsx 后缀）
     * @param sheetName      工作表名称（为空时默认 Sheet1）
     * @param data           待导出的数据集合
     * @param clazz          导出的实体类型 Class（不可为空）
     * @param includeColumns 包含的属性字段名称集合（为空或 null 则不限制）
     * @param <T>            实体数据泛型
     * @return Spring 响应实体
     */
    public static <T> ResponseEntity<StreamingResponseBody> toResponseEntity(String fileName, String sheetName, List<T> data, Class<T> clazz,
                                                                             Collection<String> includeColumns) {
        return toResponseEntity(fileName, sheetName, data, clazz, includeColumns, null, null);
    }

    /**
     * 将实体数据集流式导出为 Spring 原生 {@link ResponseEntity}，并指定工作表名称、包含与排除列
     *
     * @param fileName       导出的文件名（自动补充 .xlsx 后缀）
     * @param sheetName      工作表名称（为空时默认 Sheet1）
     * @param data           待导出的数据集合
     * @param clazz          导出的实体类型 Class（不可为空）
     * @param includeColumns 包含的属性字段名称集合（为空或 null 则不限制）
     * @param excludeColumns 排除的属性字段名称集合（为空或 null 则不限制）
     * @param <T>            实体数据泛型
     * @return Spring 响应实体
     */
    public static <T> ResponseEntity<StreamingResponseBody> toResponseEntity(String fileName, String sheetName, List<T> data, Class<T> clazz,
                                                                             Collection<String> includeColumns, Collection<String> excludeColumns) {
        return toResponseEntity(fileName, sheetName, data, clazz, includeColumns, excludeColumns, null);
    }

    /**
     * 将加密实体数据集流式导出为 Spring 原生 {@link ResponseEntity}
     *
     * @param fileName 导出的文件名（自动补充 .xlsx 后缀）
     * @param data     待导出的数据集合
     * @param clazz    导出的实体类型 Class（不可为空）
     * @param password 工作簿加密密码（为空时不加密）
     * @param <T>      实体数据泛型
     * @return Spring 响应实体
     */
    public static <T> ResponseEntity<StreamingResponseBody> toResponseEntity(String fileName, List<T> data, Class<T> clazz, String password) {
        return toResponseEntity(fileName, DEFAULT_SHEET_NAME, data, clazz, null, null, password);
    }

    /**
     * 将加密实体数据集流式导出为 Spring 原生 {@link ResponseEntity}，并指定工作表名称
     *
     * @param fileName  导出的文件名（自动补充 .xlsx 后缀）
     * @param sheetName 工作表名称（为空时默认 Sheet1）
     * @param data      待导出的数据集合
     * @param clazz     导出的实体类型 Class（不可为空）
     * @param password  工作簿加密密码（为空时不加密）
     * @param <T>       实体数据泛型
     * @return Spring 响应实体
     */
    public static <T> ResponseEntity<StreamingResponseBody> toResponseEntity(String fileName, String sheetName, List<T> data, Class<T> clazz, String password) {
        return toResponseEntity(fileName, sheetName, data, clazz, null, null, password);
    }

    /**
     * 将加密实体数据集流式导出为 Spring 原生 {@link ResponseEntity}，支持动态列投影与 AES-256 加密
     *
     * @param fileName       导出的文件名（自动补充 .xlsx 后缀）
     * @param sheetName      工作表名称（为空时默认 Sheet1）
     * @param data           待导出的数据集合
     * @param clazz          导出的实体类型 Class（不可为空）
     * @param includeColumns 包含的属性字段名称集合（为空或 null 则不限制）
     * @param excludeColumns 排除的属性字段名称集合（为空或 null 则不限制）
     * @param password       工作簿加密密码（为空时不加密）
     * @param <T>            实体数据泛型
     * @return Spring 响应实体
     */
    public static <T> ResponseEntity<StreamingResponseBody> toResponseEntity(String fileName, String sheetName, List<T> data, Class<T> clazz,
                                                                             Collection<String> includeColumns, Collection<String> excludeColumns, String password) {
        Assert.notNull(clazz, "导出的实体 Class 类型不可为空");
        return toResponseEntity(fileName, outputStream -> {
            ExcelUtil.export(outputStream, sheetName, data, clazz, includeColumns, excludeColumns, password);
        });
    }

    /**
     * 将无实体动态数据流式导出为 Spring 原生 {@link ResponseEntity}
     *
     * @param fileName 导出的文件名
     * @param headList 动态表头列表
     * @param dataList 动态数据列表
     * @return Spring 响应实体
     */
    public static ResponseEntity<StreamingResponseBody> toResponseEntityDynamic(String fileName, List<?> headList, List<?> dataList) {
        return toResponseEntityDynamic(fileName, DEFAULT_SHEET_NAME, headList, dataList);
    }

    /**
     * 将无实体动态数据流式导出为 Spring 原生 {@link ResponseEntity}（指定工作表名称）
     *
     * @param fileName  导出的文件名
     * @param sheetName 工作表名称
     * @param headList  动态表头列表
     * @param dataList  动态数据列表
     * @return Spring 响应实体
     */
    public static ResponseEntity<StreamingResponseBody> toResponseEntityDynamic(String fileName, String sheetName, List<?> headList, List<?> dataList) {
        return toResponseEntity(fileName, outputStream -> {
            ExcelUtil.exportDynamic(outputStream, sheetName, headList, dataList);
        });
    }

    /**
     * 基于 Map 列表与显式列键映射动态导出为 Spring 原生 {@link ResponseEntity}
     *
     * @param fileName  导出的文件名
     * @param sheetName 工作表名称
     * @param headList  动态表头列表
     * @param dataKeys  Map 对应的键列表
     * @param dataList  Map 数据集合
     * @return Spring 响应实体
     */
    public static ResponseEntity<StreamingResponseBody> toResponseEntityDynamic(String fileName, String sheetName, List<String> headList, List<String> dataKeys, List<Map<String, Object>> dataList) {
        return toResponseEntity(fileName, outputStream -> {
            ExcelUtil.exportDynamic(outputStream, sheetName, headList, dataKeys, dataList);
        });
    }

    /**
     * 将实体导入模板导出为 Spring 原生 {@link ResponseEntity}
     *
     * @param fileName 导出的文件名
     * @param clazz    实体模型 Class
     * @param <T>      实体泛型
     * @return Spring 响应实体
     */
    public static <T> ResponseEntity<StreamingResponseBody> toResponseEntityTemplate(String fileName, Class<T> clazz) {
        return toResponseEntityTemplate(fileName, DEFAULT_SHEET_NAME, clazz);
    }

    /**
     * 将实体导入模板导出为 Spring 原生 {@link ResponseEntity}（指定工作表名称）
     *
     * @param fileName  导出的文件名
     * @param sheetName 工作表名称
     * @param clazz     实体模型 Class
     * @param <T>       实体泛型
     * @return Spring 响应实体
     */
    public static <T> ResponseEntity<StreamingResponseBody> toResponseEntityTemplate(String fileName, String sheetName, Class<T> clazz) {
        return toResponseEntity(fileName, outputStream -> {
            ExcelUtil.exportTemplate(outputStream, sheetName, clazz);
        });
    }

    // ==================== HttpServletResponse 流式直接写出 ====================

    /**
     * Web 环境下流式导出 Excel 并直接写入 HttpServletResponse
     *
     * @param response 响应对象（不可为空）
     * @param fileName 导出的文件名（自动补充 .xlsx 后缀）
     * @param data     待导出的数据集合
     * @param clazz    导出的实体类型 Class（不可为空）
     * @param <T>      实体数据泛型
     */
    public static <T> void export(HttpServletResponse response, String fileName, List<T> data, Class<T> clazz) {
        export(response, fileName, DEFAULT_SHEET_NAME, data, clazz, null, null, null);
    }

    /**
     * Web 环境下流式导出 Excel 并直接写入 HttpServletResponse（实体类型在前签名重载）
     *
     * @param response 响应对象（不可为空）
     * @param fileName 导出的文件名（自动补充 .xlsx 后缀）
     * @param clazz    导出的实体类型 Class（不可为空）
     * @param data     待导出的数据集合
     * @param <T>      实体数据泛型
     */
    public static <T> void export(HttpServletResponse response, String fileName, Class<T> clazz, List<T> data) {
        export(response, fileName, DEFAULT_SHEET_NAME, data, clazz, null, null, null);
    }

    /**
     * Web 环境下流式导出 Excel 并直接写入 HttpServletResponse，并指定包含的列
     *
     * @param response       响应对象（不可为空）
     * @param fileName       导出的文件名（自动补充 .xlsx 后缀）
     * @param data           待导出的数据集合
     * @param clazz          导出的实体类型 Class（不可为空）
     * @param includeColumns 包含的属性字段名称集合（为空或 null 则不限制）
     * @param <T>            实体数据泛型
     */
    public static <T> void export(HttpServletResponse response, String fileName, List<T> data, Class<T> clazz,
                                  Collection<String> includeColumns) {
        export(response, fileName, DEFAULT_SHEET_NAME, data, clazz, includeColumns, null, null);
    }

    /**
     * Web 环境下流式导出 Excel 并直接写入 HttpServletResponse，并指定包含与排除的列
     *
     * @param response       响应对象（不可为空）
     * @param fileName       导出的文件名（自动补充 .xlsx 后缀）
     * @param data           待导出的数据集合
     * @param clazz          导出的实体类型 Class（不可为空）
     * @param includeColumns 包含的属性字段名称集合（为空或 null 则不限制）
     * @param excludeColumns 排除的属性字段名称集合（为空或 null 则不限制）
     * @param <T>            实体数据泛型
     */
    public static <T> void export(HttpServletResponse response, String fileName, List<T> data, Class<T> clazz,
                                  Collection<String> includeColumns, Collection<String> excludeColumns) {
        export(response, fileName, DEFAULT_SHEET_NAME, data, clazz, includeColumns, excludeColumns, null);
    }

    /**
     * Web 环境下流式导出 Excel 并直接写入 HttpServletResponse，并指定 Sheet 名称
     *
     * @param response  响应对象（不可为空）
     * @param fileName  导出的文件名（自动补充 .xlsx 后缀）
     * @param sheetName 工作表名称（为空时默认 Sheet1）
     * @param data      待导出的数据集合
     * @param clazz     导出的实体类型 Class（不可为空）
     * @param <T>       实体数据泛型
     */
    public static <T> void export(HttpServletResponse response, String fileName, String sheetName, List<T> data, Class<T> clazz) {
        export(response, fileName, sheetName, data, clazz, null, null, null);
    }

    /**
     * Web 环境下流式导出 Excel 并直接写入 HttpServletResponse，并指定 Sheet 名称与包含的列
     *
     * @param response       响应对象（不可为空）
     * @param fileName       导出的文件名（自动补充 .xlsx 后缀）
     * @param sheetName      工作表名称（为空时默认 Sheet1）
     * @param data           待导出的数据集合
     * @param clazz          导出的实体类型 Class（不可为空）
     * @param includeColumns 包含的属性字段名称集合（为空或 null 则不限制）
     * @param <T>            实体数据泛型
     */
    public static <T> void export(HttpServletResponse response, String fileName, String sheetName, List<T> data, Class<T> clazz,
                                  Collection<String> includeColumns) {
        export(response, fileName, sheetName, data, clazz, includeColumns, null, null);
    }

    /**
     * Web 环境下流式导出 Excel 并直接写入 HttpServletResponse，并指定 Sheet 名称与包含/排除的列
     *
     * @param response       响应对象（不可为空）
     * @param fileName       导出的文件名（自动补充 .xlsx 后缀）
     * @param sheetName      工作表名称（为空时默认 Sheet1）
     * @param data           待导出的数据集合
     * @param clazz          导出的实体类型 Class（不可为空）
     * @param includeColumns 包含的属性字段名称集合（为空或 null 则不限制）
     * @param excludeColumns 排除的属性字段名称集合（为空或 null 则不限制）
     * @param <T>            实体数据泛型
     */
    public static <T> void export(HttpServletResponse response, String fileName, String sheetName, List<T> data, Class<T> clazz,
                                  Collection<String> includeColumns, Collection<String> excludeColumns) {
        export(response, fileName, sheetName, data, clazz, includeColumns, excludeColumns, null);
    }

    /**
     * Web 环境下流式导出加密 Excel 并直接写入 HttpServletResponse
     *
     * @param response 响应对象（不可为空）
     * @param fileName 导出的文件名（自动补充 .xlsx 后缀）
     * @param data     待导出的数据集合
     * @param clazz    导出的实体类型 Class（不可为空）
     * @param password 工作簿加密保护密码（为空时不加密）
     * @param <T>      实体数据泛型
     */
    public static <T> void export(HttpServletResponse response, String fileName, List<T> data, Class<T> clazz, String password) {
        export(response, fileName, DEFAULT_SHEET_NAME, data, clazz, null, null, password);
    }

    /**
     * Web 环境下流式导出加密 Excel 并直接写入 HttpServletResponse，并指定 Sheet 名称
     *
     * @param response  响应对象（不可为空）
     * @param fileName  导出的文件名（自动补充 .xlsx 后缀）
     * @param sheetName 工作表名称（为空时默认 Sheet1）
     * @param data      待导出的数据集合
     * @param clazz     导出的实体类型 Class（不可为空）
     * @param password  工作簿加密保护密码（为空时不加密）
     * @param <T>       实体数据泛型
     */
    public static <T> void export(HttpServletResponse response, String fileName, String sheetName, List<T> data, Class<T> clazz, String password) {
        export(response, fileName, sheetName, data, clazz, null, null, password);
    }

    /**
     * Web 环境下流式导出加密 Excel 并直接写入 HttpServletResponse，并指定 Sheet 名称与包含/排除的列
     *
     * @param response       响应对象（不可为空）
     * @param fileName       导出的文件名（自动补充 .xlsx 后缀）
     * @param sheetName      工作表名称（为空时默认 Sheet1）
     * @param data           待导出的数据集合
     * @param clazz          导出的实体类型 Class（不可为空）
     * @param includeColumns 包含的属性字段名称集合（为空或 null 则不限制）
     * @param excludeColumns 排除的属性字段名称集合（为空或 null 则不限制）
     * @param password       工作簿加密保护密码（为空时不加密）
     * @param <T>            实体数据泛型
     */
    public static <T> void export(HttpServletResponse response, String fileName, String sheetName, List<T> data, Class<T> clazz,
                                  Collection<String> includeColumns, Collection<String> excludeColumns, String password) {
        prepareWebResponse(response, fileName);
        Assert.notNull(clazz, "导出的实体 Class 类型不可为空");

        try {
            ExcelUtil.export(response.getOutputStream(), sheetName, data, clazz, includeColumns, excludeColumns, password);
        } catch (Throwable t) {
            if (isClientAbortException(t)) {
                log.debug("客户端提前中止/重置 Excel 下载网络连接: {}", t.getMessage());
                return;
            }
            if (t instanceof BusinessException be) {
                throw be;
            }
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(), "Excel 导出写入响应流失败: " + t.getMessage(), t);
        }
    }

    /**
     * Web 环境下无实体动态流式导出并直接写入 HttpServletResponse
     *
     * @param response 响应对象
     * @param fileName 导出的文件名
     * @param headList 动态表头列表
     * @param dataList 动态数据列表
     */
    public static void exportDynamic(HttpServletResponse response, String fileName, List<?> headList, List<?> dataList) {
        exportDynamic(response, fileName, DEFAULT_SHEET_NAME, headList, dataList);
    }

    /**
     * Web 环境下无实体动态流式导出并直接写入 HttpServletResponse（指定工作表名称）
     *
     * @param response  响应对象
     * @param fileName  导出的文件名
     * @param sheetName 工作表名称
     * @param headList  动态表头列表
     * @param dataList  动态数据列表
     */
    public static void exportDynamic(HttpServletResponse response, String fileName, String sheetName, List<?> headList, List<?> dataList) {
        prepareWebResponse(response, fileName);
        try {
            ExcelUtil.exportDynamic(response.getOutputStream(), sheetName, headList, dataList);
        } catch (Throwable t) {
            if (isClientAbortException(t)) {
                log.debug("客户端提前中止/重置 Excel 动态导出下载连接: {}", t.getMessage());
                return;
            }
            if (t instanceof BusinessException be) {
                throw be;
            }
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(), "Web 动态导出 Excel 失败: " + t.getMessage(), t);
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
            ExcelUtil.exportDynamic(response.getOutputStream(), sheetName, headList, dataKeys, dataList);
        } catch (Throwable t) {
            if (isClientAbortException(t)) {
                log.debug("客户端提前中止/重置 Excel 动态导出下载连接: {}", t.getMessage());
                return;
            }
            if (t instanceof BusinessException be) {
                throw be;
            }
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(), "Web 动态导出 Excel 失败: " + t.getMessage(), t);
        }
    }

    /**
     * Web 环境下基于原文件回写批注与高亮流式导出错误反馈 Excel 并直接写入 HttpServletResponse
     *
     * @param response            HTTP 响应（不可为空）
     * @param fileName            下载文件名（自动补全 .xlsx）
     * @param originalExcelStream 原始上传的 Excel 输入流（不可为空）
     * @param errorRows           校验失败错误行集合
     */
    public static void exportErrorFeedback(HttpServletResponse response, String fileName, InputStream originalExcelStream, List<ExcelErrorRow> errorRows) {
        prepareWebResponse(response, fileName);
        try {
            ExcelUtil.exportErrorFeedback(originalExcelStream, response.getOutputStream(), errorRows);
        } catch (Throwable t) {
            if (isClientAbortException(t)) {
                log.debug("客户端提前中止/重置 Excel 错误反馈导出连接: {}", t.getMessage());
                return;
            }
            if (t instanceof BusinessException be) {
                throw be;
            }
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(), "Web 导出 Excel 错误反馈文件失败: " + t.getMessage(), t);
        }
    }

    /**
     * 将基于原文件回写批注与高亮的错误反馈文件导出为 Spring 原生 {@link ResponseEntity}
     *
     * @param fileName            下载文件名（自动补全 .xlsx）
     * @param originalExcelStream 原始上传的 Excel 输入流（不可为空）
     * @param errorRows           校验失败错误行集合
     * @return Spring 响应实体
     */
    public static ResponseEntity<StreamingResponseBody> toResponseEntityErrorFeedback(String fileName, InputStream originalExcelStream, List<ExcelErrorRow> errorRows) {
        return toResponseEntity(fileName, outputStream -> {
            ExcelUtil.exportErrorFeedback(originalExcelStream, outputStream, errorRows);
        });
    }

    /**
     * Web 环境下差错数据隔离提取导出并直接写入 HttpServletResponse
     *
     * @param response  响应对象（不可为空）
     * @param fileName  导出的文件名（自动补充 .xlsx 后缀）
     * @param clazz     导出的实体类型 Class（不可为空）
     * @param errorRows 错误行信息列表
     * @param <T>       实体数据泛型
     */
    public static <T> void exportFailedRows(HttpServletResponse response, String fileName, Class<T> clazz, List<ExcelErrorRow> errorRows) {
        prepareWebResponse(response, fileName);
        try {
            ExcelUtil.exportFailedRows(response.getOutputStream(), clazz, errorRows);
        } catch (Throwable t) {
            if (isClientAbortException(t)) {
                log.debug("客户端提前中止/重置 Excel 差错导出连接: {}", t.getMessage());
                return;
            }
            if (t instanceof BusinessException be) {
                throw be;
            }
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(), "Excel 差错导出写入响应流失败: " + t.getMessage(), t);
        }
    }

    /**
     * Web 环境下差错数据隔离提取导出并直接写入 HttpServletResponse（显式指定失败实体列表）
     *
     * @param response   响应对象（不可为空）
     * @param fileName   导出的文件名（自动补充 .xlsx 后缀）
     * @param clazz      导出的实体类型 Class（不可为空）
     * @param failedList 失败行对应的原始实体对象列表
     * @param errorRows  错误行信息列表
     * @param <T>        实体数据泛型
     */
    public static <T> void exportFailedRows(HttpServletResponse response, String fileName, Class<T> clazz, List<T> failedList, List<ExcelErrorRow> errorRows) {
        prepareWebResponse(response, fileName);
        try {
            ExcelUtil.exportFailedRows(response.getOutputStream(), clazz, failedList, errorRows);
        } catch (Throwable t) {
            if (isClientAbortException(t)) {
                log.debug("客户端提前中止/重置 Excel 差错导出连接: {}", t.getMessage());
                return;
            }
            if (t instanceof BusinessException be) {
                throw be;
            }
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(), "Excel 差错导出写入响应流失败: " + t.getMessage(), t);
        }
    }

    /**
     * Web 环境下添加全景文字水印流式导出 Excel 并直接写入 HttpServletResponse
     *
     * @param response      响应对象（不可为空）
     * @param fileName      导出的文件名（自动补充 .xlsx 后缀）
     * @param data          待导出的数据集合
     * @param clazz         导出的实体类型 Class（不可为空）
     * @param watermarkText 水印文字内容（如 "机密文件 严禁外传"）
     * @param <T>           实体数据泛型
     */
    public static <T> void exportWithWatermark(HttpServletResponse response, String fileName, List<T> data, Class<T> clazz, String watermarkText) {
        exportWithWatermark(response, fileName, data, clazz, watermarkText, null);
    }

    /**
     * Web 环境下添加全景文字水印与工作表保护流式导出 Excel 并直接写入 HttpServletResponse
     *
     * @param response           响应对象（不可为空）
     * @param fileName           导出的文件名（自动补充 .xlsx 后缀）
     * @param data               待导出的数据集合
     * @param clazz              导出的实体类型 Class（不可为空）
     * @param watermarkText      水印文字内容
     * @param protectionPassword 工作表保护密码（为空时不启用锁定保护）
     * @param <T>                实体数据泛型
     */
    public static <T> void exportWithWatermark(HttpServletResponse response, String fileName, List<T> data, Class<T> clazz, String watermarkText, String protectionPassword) {
        prepareWebResponse(response, fileName);
        try {
            ExcelUtil.exportWithWatermark(response.getOutputStream(), DEFAULT_SHEET_NAME, data, clazz, watermarkText, protectionPassword);
        } catch (Throwable t) {
            if (isClientAbortException(t)) {
                log.debug("客户端提前中止/重置 Excel 水印导出连接: {}", t.getMessage());
                return;
            }
            if (t instanceof BusinessException be) {
                throw be;
            }
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(), "Excel 水印导出写入响应流失败: " + t.getMessage(), t);
        }
    }

    /**
     * 将带水印的实体数据集流式导出为 Spring 原生 {@link ResponseEntity}
     *
     * @param fileName      导出的文件名（自动补充 .xlsx 后缀）
     * @param data          待导出的数据集合
     * @param clazz         导出的实体类型 Class（不可为空）
     * @param watermarkText 水印文字内容
     * @param <T>           实体数据泛型
     * @return Spring 响应实体
     */
    public static <T> ResponseEntity<StreamingResponseBody> toResponseEntityWithWatermark(String fileName, List<T> data, Class<T> clazz, String watermarkText) {
        return toResponseEntityWithWatermark(fileName, DEFAULT_SHEET_NAME, data, clazz, watermarkText, null);
    }

    /**
     * 将带水印与工作表锁定的实体数据集流式导出为 Spring 原生 {@link ResponseEntity}
     *
     * @param fileName           导出的文件名（自动补充 .xlsx 后缀）
     * @param sheetName          工作表名称
     * @param data               待导出的数据集合
     * @param clazz              导出的实体类型 Class（不可为空）
     * @param watermarkText      水印文字内容
     * @param protectionPassword 工作表保护密码（为空时不锁定）
     * @param <T>                实体数据泛型
     * @return Spring 响应实体
     */
    public static <T> ResponseEntity<StreamingResponseBody> toResponseEntityWithWatermark(String fileName, String sheetName, List<T> data, Class<T> clazz, String watermarkText, String protectionPassword) {
        return toResponseEntity(fileName, outputStream -> {
            ExcelUtil.exportWithWatermark(outputStream, sheetName, data, clazz, watermarkText, protectionPassword);
        });
    }

    /**
     * Web 环境下流式导出空数据导入模板（包含表头、下拉列表与提示批注）并直接写入 HttpServletResponse
     *
     * @param response 响应对象（不可为空）
     * @param fileName 导出的模板文件名（自动补充 .xlsx 后缀）
     * @param clazz    实体模型 Class（不可为空）
     * @param <T>      实体泛型
     */
    public static <T> void exportTemplate(HttpServletResponse response, String fileName, Class<T> clazz) {
        exportTemplate(response, fileName, DEFAULT_SHEET_NAME, clazz);
    }

    /**
     * Web 环境下流式导出空数据导入模板（指定工作表名称）并直接写入 HttpServletResponse
     *
     * @param response  响应对象（不可为空）
     * @param fileName  导出的模板文件名（自动补充 .xlsx 后缀）
     * @param sheetName 工作表名称（为空时默认 Sheet1）
     * @param clazz     实体模型 Class（不可为空）
     * @param <T>       实体泛型
     */
    public static <T> void exportTemplate(HttpServletResponse response, String fileName, String sheetName, Class<T> clazz) {
        prepareWebResponse(response, fileName);
        try {
            ExcelUtil.exportTemplate(response.getOutputStream(), sheetName, clazz);
        } catch (Throwable t) {
            if (isClientAbortException(t)) {
                log.debug("客户端提前中止/重置 Excel 模板导出连接: {}", t.getMessage());
                return;
            }
            if (t instanceof BusinessException be) {
                throw be;
            }
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(), "Excel 模板导出写入响应流失败: " + t.getMessage(), t);
        }
    }

    /**
     * Web 环境下基于 Excel 模板填充数据并直接写入 HttpServletResponse
     *
     * @param response   响应对象
     * @param fileName   导出的文件名
     * @param templateIn 模板输入流
     * @param data       填充对象（JavaBean 或 Map）
     */
    public static void fill(HttpServletResponse response, String fileName, InputStream templateIn, Object data) {
        fill(response, fileName, DEFAULT_SHEET_NAME, templateIn, data, null);
    }

    /**
     * Web 环境下基于 Excel 模板填充数据并直接写入 HttpServletResponse（支持自定义填充配置）
     *
     * @param response   响应对象
     * @param fileName   导出的文件名
     * @param templateIn 模板输入流
     * @param data       填充对象（JavaBean 或 Map）
     * @param fillConfig 自定义填充配置（为 null 则使用默认配置）
     */
    public static void fill(HttpServletResponse response, String fileName, InputStream templateIn, Object data, FillConfig fillConfig) {
        fill(response, fileName, DEFAULT_SHEET_NAME, templateIn, data, fillConfig);
    }

    /**
     * Web 环境下基于 Excel 模板填充数据并直接写入 HttpServletResponse（指定工作表名称）
     *
     * @param response   响应对象
     * @param fileName   导出的文件名
     * @param sheetName  工作表名称
     * @param templateIn 模板输入流
     * @param data       填充对象（JavaBean 或 Map）
     * @param fillConfig 自定义填充配置（为 null 则使用默认配置）
     */
    public static void fill(HttpServletResponse response, String fileName, String sheetName, InputStream templateIn, Object data, FillConfig fillConfig) {
        prepareWebResponse(response, fileName);
        try {
            ExcelUtil.fill(response.getOutputStream(), templateIn, sheetName, data, fillConfig);
        } catch (Throwable t) {
            if (isClientAbortException(t)) {
                log.debug("客户端提前中止/重置 Excel 模板填充连接: {}", t.getMessage());
                return;
            }
            if (t instanceof BusinessException be) {
                throw be;
            }
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(), "Excel 模板填充写出失败: " + t.getMessage(), t);
        }
    }

    /**
     * Web 环境下基于 Excel 模板复合填充单个对象与集合列表并直接写入 HttpServletResponse
     *
     * @param response   响应对象
     * @param fileName   导出的文件名
     * @param sheetName  工作表名称
     * @param templateIn 模板输入流
     * @param objectData 填充单对象
     * @param listData   填充集合数据
     * @param fillConfig 自定义填充配置
     */
    public static void fill(HttpServletResponse response, String fileName, String sheetName, InputStream templateIn, Object objectData, Collection<?> listData, FillConfig fillConfig) {
        prepareWebResponse(response, fileName);
        try {
            ExcelUtil.fill(response.getOutputStream(), templateIn, sheetName, objectData, listData, fillConfig);
        } catch (Throwable t) {
            if (isClientAbortException(t)) {
                log.debug("客户端提前中止/重置 Excel 复合模板填充连接: {}", t.getMessage());
                return;
            }
            if (t instanceof BusinessException be) {
                throw be;
            }
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(), "Excel 模板填充写出失败: " + t.getMessage(), t);
        }
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
            return ExcelUtil.builder(response.getOutputStream());
        } catch (Throwable t) {
            if (isClientAbortException(t)) {
                log.debug("客户端提前中止/重置 Excel 构建器连接: {}", t.getMessage());
                return null;
            }
            if (t instanceof BusinessException be) {
                throw be;
            }
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(), "创建 ExcelBuilder 失败: " + t.getMessage(), t);
        }
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
            return ExcelUtil.streaming(response.getOutputStream(), clazz);
        } catch (Throwable t) {
            if (isClientAbortException(t)) {
                log.debug("客户端提前中止/重置 Excel 流式分页连接: {}", t.getMessage());
                return null;
            }
            if (t instanceof BusinessException be) {
                throw be;
            }
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(), "创建流式分页导出模板失败: " + t.getMessage(), t);
        }
    }

    /**
     * Web 环境下流式分页导出至 HttpServletResponse（基于 PageResult 驱动）
     *
     * @param response   响应对象（不可为空）
     * @param fileName   导出的文件名（自动补充 .xlsx 后缀）
     * @param clazz      导出的实体类型 Class（不可为空）
     * @param pageLoader 分页数据拉取函数（输入当前页码，返回分页结果 PageResult）
     * @param <T>        实体泛型
     * @return 实际导出的总记录行数
     */
    public static <T> long exportStreaming(HttpServletResponse response, String fileName, Class<T> clazz,
                                          Function<Integer, PageResult<T>> pageLoader) {
        return exportStreaming(response, fileName, DEFAULT_SHEET_NAME, clazz, pageLoader);
    }

    /**
     * Web 环境下流式分页导出至 HttpServletResponse，并指定基础 Sheet 名称（基于 PageResult 驱动）
     *
     * @param response   响应对象（不可为空）
     * @param fileName   导出的文件名（自动补充 .xlsx 后缀）
     * @param sheetName  基础工作表名称
     * @param clazz      导出的实体类型 Class（不可为空）
     * @param pageLoader 分页数据拉取函数（输入当前页码，返回分页结果 PageResult）
     * @param <T>        实体泛型
     * @return 实际导出的总记录行数
     */
    public static <T> long exportStreaming(HttpServletResponse response, String fileName, String sheetName, Class<T> clazz,
                                          Function<Integer, PageResult<T>> pageLoader) {
        prepareWebResponse(response, fileName);
        try {
            return ExcelUtil.exportStreaming(response.getOutputStream(), sheetName, clazz, pageLoader);
        } catch (Throwable t) {
            if (isClientAbortException(t)) {
                log.debug("客户端提前中止/重置 Excel 流式分页导出连接: {}", t.getMessage());
                return 0L;
            }
            if (t instanceof BusinessException be) {
                throw be;
            }
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(), "Web 流式分页导出执行失败: " + t.getMessage(), t);
        }
    }

    /**
     * Web 环境下流式游标分页导出至 HttpServletResponse（基于主键/时间戳游标驱动）
     *
     * @param response        响应对象（不可为空）
     * @param fileName        导出的文件名（自动补充 .xlsx 后缀）
     * @param clazz           导出的实体类型 Class（不可为空）
     * @param initialCursor   初始游标
     * @param pageSize        每次拉取步长
     * @param cursorLoader    游标数据拉取函数（输入当前游标与批次大小，返回实体列表）
     * @param cursorExtractor 游标提取器（从实体中提取游标值）
     * @param <T>             实体泛型
     * @param <K>             游标泛型
     * @return 实际导出的总记录行数
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
     * @param response        响应对象（不可为空）
     * @param fileName        导出的文件名（自动补充 .xlsx 后缀）
     * @param sheetName       基础工作表名称
     * @param clazz           导出的实体类型 Class（不可为空）
     * @param initialCursor   初始游标
     * @param pageSize        每次拉取步长
     * @param cursorLoader    游标数据拉取函数（输入当前游标与批次大小，返回实体列表）
     * @param cursorExtractor 游标提取器（从实体中提取游标值）
     * @param <T>             实体泛型
     * @param <K>             游标泛型
     * @return 实际导出的总记录行数
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
            return ExcelUtil.exportStreamingByCursor(response.getOutputStream(), sheetName, clazz, initialCursor, pageSize, cursorLoader, cursorExtractor);
        } catch (Throwable t) {
            if (isClientAbortException(t)) {
                log.debug("客户端提前中止/重置 Excel 游标流式分页导出连接: {}", t.getMessage());
                return 0L;
            }
            if (t instanceof BusinessException be) {
                throw be;
            }
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(), "Web 游标流式分页导出执行失败: " + t.getMessage(), t);
        }
    }
}
