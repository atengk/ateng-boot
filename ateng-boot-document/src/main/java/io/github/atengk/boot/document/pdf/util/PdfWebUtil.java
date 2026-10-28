package io.github.atengk.boot.document.pdf.util;

import io.github.atengk.boot.document.exception.DocumentResultCode;
import io.github.atengk.boot.document.exception.PdfOperationException;
import io.github.atengk.boot.document.util.DocumentWebHelper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * PDF 文档浏览器流式安全导出与内联预览静态辅助工具
 * <p>
 * 封装基于 {@link HttpServletResponse} 的流式导出能力，
 * 严格遵循 RFC 6266 与 RFC 5987 标准自动组装 UTF-8 防乱码响应标头与标准 application/pdf MIME 类型，
 * 支持 attachment 附件下载与 inline 浏览器内嵌预览两种模式。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
public final class PdfWebUtil {

    /**
     * PDF 标准 MIME 类型
     */
    public static final String PDF_CONTENT_TYPE = "application/pdf";

    /**
     * PDF 默认文件扩展名
     */
    public static final String PDF_EXTENSION = ".pdf";

    private PdfWebUtil() {
    }

    /**
     * PDF 响应流式写入函数式动作接口
     */
    @FunctionalInterface
    public interface PdfWebWriter {

        /**
         * 向 HTTP 响应输出流写入 PDF 二进制流
         *
         * @param outputStream HTTP 响应输出流
         * @throws Exception 处理或写出异常
         */
        void write(OutputStream outputStream) throws Exception;
    }

    /**
     * 为 HttpServletResponse 准备标准 PDF 响应标头（RFC 6266 / RFC 5987）
     *
     * @param response HTTP 响应对象
     * @param filename 导出文件名（若缺少 .pdf 后缀则自动补齐）
     */
    public static void prepareResponse(HttpServletResponse response, String filename) {
        prepareResponse(response, filename, false);
    }

    /**
     * 为 HttpServletResponse 准备标准 PDF 响应标头（RFC 6266 / RFC 5987）
     *
     * @param response HTTP 响应对象
     * @param filename 导出文件名（若缺少 .pdf 后缀则自动补齐）
     * @param inline   是否为浏览器内联预览模式（true 为 inline 预览，false 为 attachment 下载）
     */
    public static void prepareResponse(HttpServletResponse response, String filename, boolean inline) {
        Objects.requireNonNull(response, "HttpServletResponse response 不能为空");
        if (filename == null || filename.trim().isEmpty()) {
            throw new IllegalArgumentException("导出文件名 filename 不能为空");
        }

        response.setContentType(PDF_CONTENT_TYPE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        response.setDateHeader(HttpHeaders.EXPIRES, 0);

        String safeFileName = DocumentWebHelper.sanitizeAndFormatFileName(filename, PDF_EXTENSION);
        ContentDisposition.Builder builder = inline ? ContentDisposition.inline() : ContentDisposition.attachment();
        ContentDisposition disposition = builder
                .filename(safeFileName, StandardCharsets.UTF_8)
                .build();
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, disposition.toString());
    }

    /**
     * 将现有的 PDF 输入源直接流式输出至 HttpServletResponse
     *
     * @param response  HTTP 响应对象
     * @param filename  导出文件名（自动补齐 .pdf）
     * @param pdfSource PDF 输入源（支持 Resource, InputStream, File, Path, byte[], String）
     * @param inline    是否为浏览器内联预览模式
     */
    public static void writeToResponse(HttpServletResponse response, String filename, Object pdfSource, boolean inline) {
        Objects.requireNonNull(pdfSource, "PDF 文档输入源 pdfSource 不能为空");
        prepareResponse(response, filename, inline);

        try (InputStream is = DocumentWebHelper.resolveInputStream(pdfSource)) {
            is.transferTo(response.getOutputStream());
            response.getOutputStream().flush();
        } catch (Exception e) {
            throw new PdfOperationException(DocumentResultCode.PDF_OPERATION_FAILED, "流式输出 PDF 文档失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将现有的 PDF 输入源直接作为附件下载输出至 HttpServletResponse
     *
     * @param response  HTTP 响应对象
     * @param filename  导出文件名（自动补齐 .pdf）
     * @param pdfSource PDF 输入源
     */
    public static void writeToResponse(HttpServletResponse response, String filename, Object pdfSource) {
        writeToResponse(response, filename, pdfSource, false);
    }

    /**
     * 执行自定义写入回调动作并直接流式写入 HttpServletResponse
     *
     * @param response HTTP 响应对象
     * @param filename 导出文件名（自动补齐 .pdf）
     * @param writer   自定义流式写入回调动作（如调用 PdfTemplate 的水印/表单/编排处理）
     * @param inline   是否为浏览器内联预览模式
     */
    public static void writeToResponse(HttpServletResponse response, String filename, PdfWebWriter writer, boolean inline) {
        Objects.requireNonNull(writer, "PdfWebWriter 不能为空");
        prepareResponse(response, filename, inline);

        try {
            writer.write(response.getOutputStream());
            response.getOutputStream().flush();
        } catch (PdfOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new PdfOperationException(DocumentResultCode.PDF_OPERATION_FAILED, "流式写入 PDF 响应失败: " + e.getMessage(), e);
        }
    }

    /**
     * 便捷方法：以附件下载模式（attachment）输出已有 PDF 输入源
     *
     * @param response  HTTP 响应对象
     * @param filename  导出文件名（自动补齐 .pdf）
     * @param pdfSource PDF 输入源
     */
    public static void download(HttpServletResponse response, String filename, Object pdfSource) {
        writeToResponse(response, filename, pdfSource, false);
    }

    /**
     * 便捷方法：以浏览器预览模式（inline）输出已有 PDF 输入源
     *
     * @param response  HTTP 响应对象
     * @param filename  导出文件名（自动补齐 .pdf）
     * @param pdfSource PDF 输入源
     */
    public static void preview(HttpServletResponse response, String filename, Object pdfSource) {
        writeToResponse(response, filename, pdfSource, true);
    }

    /**
     * 便捷方法：以附件下载模式（attachment）流式执行写入动作并输出
     *
     * @param response HTTP 响应对象
     * @param filename 导出文件名（自动补齐 .pdf）
     * @param writer   自定义流式写入回调动作
     */
    public static void download(HttpServletResponse response, String filename, PdfWebWriter writer) {
        writeToResponse(response, filename, writer, false);
    }

    /**
     * 便捷方法：以浏览器预览模式（inline）流式执行写入动作并输出
     *
     * @param response HTTP 响应对象
     * @param filename 导出文件名（自动补齐 .pdf）
     * @param writer   自定义流式写入回调动作
     */
    public static void preview(HttpServletResponse response, String filename, PdfWebWriter writer) {
        writeToResponse(response, filename, writer, true);
    }

    // --- 便捷别名重载（对齐 write 契约） ---

    /**
     * 流式输出 PDF 响应别名（对齐 write 契约，默认下载模式）
     */
    public static void write(HttpServletResponse response, String filename, Object pdfSource) {
        writeToResponse(response, filename, pdfSource, false);
    }

    /**
     * 流式输出 PDF 响应别名（对齐 write 契约）
     */
    public static void write(HttpServletResponse response, String filename, Object pdfSource, boolean inline) {
        writeToResponse(response, filename, pdfSource, inline);
    }

    /**
     * 流式输出 PDF 响应别名（对齐 write 契约，默认下载模式）
     */
    public static void write(HttpServletResponse response, String filename, PdfWebWriter writer) {
        writeToResponse(response, filename, writer, false);
    }

    /**
     * 流式输出 PDF 响应别名（对齐 write 契约）
     */
    public static void write(HttpServletResponse response, String filename, PdfWebWriter writer, boolean inline) {
        writeToResponse(response, filename, writer, inline);
    }
}
