package io.github.atengk.boot.document.pdf.util;

import io.github.atengk.boot.document.exception.DocumentResultCode;
import io.github.atengk.boot.document.exception.PdfOperationException;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * PDF 文档浏览器流式安全输出与预览响应静态辅助工具
 * <p>
 * 支持直接向 {@link HttpServletResponse} 流式输出带水印、签名或填报后的 PDF 二进制数据，
 * 严格遵循 RFC 6266 与 RFC 5987 标准自动组装 UTF-8 防乱码响应标头与标准 application/pdf MIME 类型，
 * 并支持浏览器内联预览（{@code inline}）与附件下载（{@code attachment}）双模平滑切换。
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

    private static final String CLASSPATH_PREFIX = "classpath:";

    private PdfWebUtil() {
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

        String safeFileName = sanitizeAndFormatFileName(filename, PDF_EXTENSION);
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

        try (InputStream is = resolveInputStream(pdfSource)) {
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
        Objects.requireNonNull(writer, "PDF 流式写入回调 writer 不能为空");
        prepareResponse(response, filename, inline);

        try {
            writer.write(response.getOutputStream());
            response.getOutputStream().flush();
        } catch (PdfOperationException e) {
            throw e;
        } catch (Exception e) {
            throw new PdfOperationException(DocumentResultCode.PDF_OPERATION_FAILED, "流式生成 PDF 响应失败: " + e.getMessage(), e);
        }
    }

    /**
     * 执行自定义写入回调动作并作为附件直接下载输出至 HttpServletResponse
     *
     * @param response HTTP 响应对象
     * @param filename 导出文件名（自动补齐 .pdf）
     * @param writer   自定义流式写入回调动作
     */
    public static void writeToResponse(HttpServletResponse response, String filename, PdfWebWriter writer) {
        writeToResponse(response, filename, writer, false);
    }

    /**
     * 便捷方法：以浏览器内联预览模式（inline）流式输出 PDF
     *
     * @param response  HTTP 响应对象
     * @param filename  导出文件名（自动补齐 .pdf）
     * @param pdfSource PDF 输入源
     */
    public static void preview(HttpServletResponse response, String filename, Object pdfSource) {
        writeToResponse(response, filename, pdfSource, true);
    }

    /**
     * 便捷方法：以浏览器内联预览模式（inline）流式执行写入动作并输出
     *
     * @param response HTTP 响应对象
     * @param filename 导出文件名（自动补齐 .pdf）
     * @param writer   自定义流式写入回调动作
     */
    public static void preview(HttpServletResponse response, String filename, PdfWebWriter writer) {
        writeToResponse(response, filename, writer, true);
    }

    /**
     * 便捷方法：以附件下载模式（attachment）流式输出 PDF
     *
     * @param response  HTTP 响应对象
     * @param filename  导出文件名（自动补齐 .pdf）
     * @param pdfSource PDF 输入源
     */
    public static void download(HttpServletResponse response, String filename, Object pdfSource) {
        writeToResponse(response, filename, pdfSource, false);
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

    private static String sanitizeAndFormatFileName(String fileName, String defaultExtension) {
        String clean = fileName.replace("\r", "").replace("\n", "").trim();
        if (!clean.toLowerCase().endsWith(defaultExtension.toLowerCase())) {
            clean += defaultExtension;
        }
        return clean;
    }

    private static InputStream resolveInputStream(Object source) throws IOException {
        if (source instanceof InputStream is) {
            return is;
        }
        if (source instanceof byte[] b) {
            return new ByteArrayInputStream(b);
        }
        if (source instanceof File f) {
            return new FileInputStream(f);
        }
        if (source instanceof Path p) {
            return Files.newInputStream(p);
        }
        if (source instanceof Resource r) {
            return r.getInputStream();
        }
        if (source instanceof String s) {
            String path = s.trim();
            if (path.startsWith(CLASSPATH_PREFIX)) {
                String sub = path.substring(CLASSPATH_PREFIX.length());
                if (sub.startsWith("/")) {
                    sub = sub.substring(1);
                }
                InputStream cis = PdfWebUtil.class.getClassLoader().getResourceAsStream(sub);
                if (cis != null) {
                    return cis;
                }
            }
            return new FileInputStream(path);
        }
        throw new IllegalArgumentException("不支持的 PDF 文档输入源: " + source.getClass().getName());
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
}
