package io.github.atengk.boot.document.word.util;

import com.deepoove.poi.config.Configure;
import io.github.atengk.boot.document.exception.DocumentResultCode;
import io.github.atengk.boot.document.exception.WordRenderException;
import io.github.atengk.boot.document.util.DocumentWebHelper;
import io.github.atengk.boot.document.word.WordTemplate;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Word 文档浏览器流式安全导出静态辅助工具
 * <p>
 * 封装基于 {@link HttpServletResponse} 的流式下载能力，
 * 严格遵循 RFC 6266 与 RFC 5987 标准自动组装 UTF-8 防乱码响应标头与标准 DOCX MIME 类型，
 * 采用流式直出避免将文档全量加载至堆内存字节数组，降低高并发下的内存开销。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-23
 */
public final class WordWebUtil {

    /**
     * DOCX 标准 MIME 类型
     */
    public static final String DOCX_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    /**
     * DOCX 默认文件扩展名
     */
    public static final String DOCX_EXTENSION = ".docx";

    private WordWebUtil() {
    }

    /**
     * 自定义流式直写回调函数式接口
     */
    @FunctionalInterface
    public interface WordWebWriter {
        /**
         * 写入 Word 数据流至目标响应流
         *
         * @param outputStream HTTP 响应输出流
         * @throws Exception 业务写入或流操作异常
         */
        void write(OutputStream outputStream) throws Exception;
    }

    /**
     * 为 HttpServletResponse 准备标准 DOCX 下载响应标头（RFC 6266 / RFC 5987）
     *
     * @param response HTTP 响应对象
     * @param filename 导出文件名（若缺少 .docx 后缀则自动补齐）
     */
    public static void prepareResponse(HttpServletResponse response, String filename) {
        Objects.requireNonNull(response, "HttpServletResponse response 不能为空");
        if (filename == null || filename.trim().isEmpty()) {
            throw new IllegalArgumentException("导出文件名 filename 不能为空");
        }

        response.setContentType(DOCX_CONTENT_TYPE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, must-revalidate");
        response.setHeader(HttpHeaders.PRAGMA, "no-cache");
        response.setDateHeader(HttpHeaders.EXPIRES, 0);

        String safeFileName = DocumentWebHelper.sanitizeAndFormatFileName(filename, DOCX_EXTENSION);
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(safeFileName, StandardCharsets.UTF_8)
                .build();
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, disposition.toString());
    }

    /**
     * 执行自定义写入回调动作并直接流式写入 HttpServletResponse
     *
     * @param response HTTP 响应对象
     * @param filename 导出文件名（自动补齐 .docx）
     * @param writer   自定义流式写入回调动作
     */
    public static void writeToResponse(HttpServletResponse response, String filename, WordWebWriter writer) {
        Objects.requireNonNull(writer, "WordWebWriter 不能为空");
        prepareResponse(response, filename);

        try {
            writer.write(response.getOutputStream());
            response.getOutputStream().flush();
        } catch (WordRenderException e) {
            throw e;
        } catch (Exception e) {
            throw new WordRenderException(DocumentResultCode.WORD_RENDER_FAILED, "流式写入 Word 响应失败: " + e.getMessage(), e);
        }
    }

    /**
     * 渲染 Word 模板并直接流式输出至 HttpServletResponse
     *
     * @param response       HTTP 响应对象
     * @param filename       导出文件名（自动补齐 .docx）
     * @param templateSource 模板输入源（支持 Resource, InputStream, File, Path, byte[], String）
     * @param data           模板填充数据模型对象
     */
    public static void writeToResponse(HttpServletResponse response, String filename, Object templateSource, Object data) {
        writeToResponse(response, filename, templateSource, data, null, null);
    }

    /**
     * 渲染 Word 模板并直接流式输出至 HttpServletResponse（支持 poi-tl 自定义配置）
     *
     * @param response       HTTP 响应对象
     * @param filename       导出文件名（自动补齐 .docx）
     * @param templateSource 模板输入源
     * @param data           模板填充数据模型对象
     * @param configure      poi-tl 自定义扩展配置
     */
    public static void writeToResponse(HttpServletResponse response, String filename, Object templateSource, Object data, Configure configure) {
        writeToResponse(response, filename, templateSource, data, configure, null);
    }

    /**
     * 使用受管的 WordTemplate 实例渲染并流式输出至 HttpServletResponse
     *
     * @param response       HTTP 响应对象
     * @param filename       导出文件名（自动补齐 .docx）
     * @param wordTemplate   WordTemplate 门面实例
     * @param templateSource 模板输入源
     * @param data           模板填充数据模型对象
     */
    public static void writeToResponse(HttpServletResponse response, String filename, WordTemplate wordTemplate, Object templateSource, Object data) {
        writeToResponse(response, filename, templateSource, data, null, wordTemplate);
    }

    /**
     * 使用受管的 WordTemplate 实例渲染并流式输出至 HttpServletResponse（带 poi-tl 自定义配置）
     *
     * @param response       HTTP 响应对象
     * @param filename       导出文件名（自动补齐 .docx）
     * @param templateSource 模板输入源
     * @param data           模板填充数据模型对象
     * @param configure      poi-tl 自定义扩展配置
     * @param wordTemplate   WordTemplate 门面实例（若为 null 则自动创建轻量实例）
     */
    public static void writeToResponse(HttpServletResponse response, String filename, Object templateSource, Object data,
                                       Configure configure, WordTemplate wordTemplate) {
        prepareResponse(response, filename);

        WordTemplate template = wordTemplate != null ? wordTemplate : new WordTemplate();
        try {
            template.render(templateSource, data, response.getOutputStream(), configure);
            response.getOutputStream().flush();
        } catch (WordRenderException e) {
            throw e;
        } catch (Exception e) {
            throw new WordRenderException(DocumentResultCode.WORD_RENDER_FAILED, "流式写入 Word 响应失败: " + e.getMessage(), e);
        }
    }

    /**
     * 将现有的 Word 文档输入源直接流式拷贝至 HttpServletResponse 输出
     *
     * @param response   HTTP 响应对象
     * @param filename   导出文件名（自动补齐 .docx）
     * @param docxSource Word 文档输入源（支持 Resource, InputStream, File, Path, byte[], String）
     */
    public static void writeToResponse(HttpServletResponse response, String filename, Object docxSource) {
        Objects.requireNonNull(docxSource, "Word 文档输入源 docxSource 不能为空");
        prepareResponse(response, filename);

        try (InputStream is = DocumentWebHelper.resolveInputStream(docxSource)) {
            is.transferTo(response.getOutputStream());
            response.getOutputStream().flush();
        } catch (Exception e) {
            throw new WordRenderException(DocumentResultCode.DOCUMENT_OPERATION_FAILED, "流式输出 Word 文档失败: " + e.getMessage(), e);
        }
    }

    // --- 便捷别名重载（对称 API） ---

    /**
     * 流式输出 Word 响应别名（对齐 write 契约）
     */
    public static void write(HttpServletResponse response, String filename, Object templateSource, Object data) {
        writeToResponse(response, filename, templateSource, data);
    }

    /**
     * 流式输出 Word 响应别名（对齐 write 契约）
     */
    public static void write(HttpServletResponse response, String filename, Object docxSource) {
        writeToResponse(response, filename, docxSource);
    }

    /**
     * 流式输出 Word 响应别名（对齐 write 契约）
     */
    public static void write(HttpServletResponse response, String filename, WordWebWriter writer) {
        writeToResponse(response, filename, writer);
    }

    /**
     * 作为附件下载 Word 文档（对齐 download 契约）
     */
    public static void download(HttpServletResponse response, String filename, Object docxSource) {
        writeToResponse(response, filename, docxSource);
    }

    /**
     * 作为附件渲染并下载 Word 文档（对齐 download 契约）
     */
    public static void download(HttpServletResponse response, String filename, Object templateSource, Object data) {
        writeToResponse(response, filename, templateSource, data);
    }
}
