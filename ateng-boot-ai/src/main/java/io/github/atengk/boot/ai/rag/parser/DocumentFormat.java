package io.github.atengk.boot.ai.rag.parser;

import lombok.Getter;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * RAG 知识库多源文档格式枚举
 *
 * @author Ateng
 * @since 2026-11-05
 */
@Getter
public enum DocumentFormat {

    /**
     * 纯文本格式
     */
    TXT("text/plain", List.of("txt", "text", "log")),

    /**
     * Markdown 文档格式
     */
    MARKDOWN("text/markdown", List.of("md", "markdown")),

    /**
     * HTML 超文本格式
     */
    HTML("text/html", List.of("html", "htm", "xhtml")),

    /**
     * PDF 文档格式
     */
    PDF("application/pdf", List.of("pdf")),

    /**
     * Word 文档格式 (.docx / .doc)
     */
    WORD("application/vnd.openxmlformats-officedocument.wordprocessingml.document", List.of("docx", "doc")),

    /**
     * Excel 电子表格格式 (.xlsx / .xls)
     */
    EXCEL("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", List.of("xlsx", "xls")),

    /**
     * CSV 逗号分隔值表格格式
     */
    CSV("text/csv", List.of("csv")),

    /**
     * JSON 结构化数据格式
     */
    JSON("application/json", List.of("json")),

    /**
     * 未知或通用二进制格式
     */
    UNKNOWN("application/octet-stream", Collections.emptyList());

    private final String mimeType;
    private final List<String> extensions;

    DocumentFormat(String mimeType, List<String> extensions) {
        this.mimeType = mimeType;
        this.extensions = extensions;
    }

    /**
     * 获取首选文件扩展名 (不含前导点)
     *
     * @return 扩展名字符串，若无扩展名则返回空字符串
     */
    public String getPrimaryExtension() {
        return extensions.isEmpty() ? "" : extensions.get(0);
    }

    /**
     * 判断是否为纯文本类格式 (TXT, MARKDOWN, HTML, CSV, JSON)
     *
     * @return true 表示为文本流格式
     */
    public boolean isTextBased() {
        return this == TXT || this == MARKDOWN || this == HTML || this == CSV || this == JSON;
    }

    /**
     * 根据文件名智能探测文档格式
     *
     * @param filename 文件名或路径
     * @return 对应的 DocumentFormat 枚举，无法识别时返回 UNKNOWN
     */
    public static DocumentFormat fromFilename(String filename) {
        if (!StringUtils.hasText(filename)) {
            return UNKNOWN;
        }
        int lastDotIndex = filename.lastIndexOf('.');
        if (lastDotIndex < 0 || lastDotIndex == filename.length() - 1) {
            return UNKNOWN;
        }
        String ext = filename.substring(lastDotIndex + 1).trim().toLowerCase(Locale.ROOT);
        return fromExtension(ext);
    }

    /**
     * 根据扩展名匹配文档格式
     *
     * @param extension 文件扩展名 (可带或不带前导点)
     * @return 对应的 DocumentFormat 枚举，无法识别时返回 UNKNOWN
     */
    public static DocumentFormat fromExtension(String extension) {
        if (!StringUtils.hasText(extension)) {
            return UNKNOWN;
        }
        String cleanExt = extension.startsWith(".") ? extension.substring(1) : extension;
        cleanExt = cleanExt.trim().toLowerCase(Locale.ROOT);

        for (DocumentFormat format : values()) {
            if (format.extensions.contains(cleanExt)) {
                return format;
            }
        }
        return UNKNOWN;
    }

    /**
     * 根据 MIME / Content-Type 探测文档格式
     *
     * @param mimeType MIME 类型字符串 (如 text/html; charset=UTF-8)
     * @return 对应的 DocumentFormat 枚举，无法识别时返回 UNKNOWN
     */
    public static DocumentFormat fromMimeType(String mimeType) {
        if (!StringUtils.hasText(mimeType)) {
            return UNKNOWN;
        }
        String cleanMime = mimeType.split(";")[0].trim().toLowerCase(Locale.ROOT);
        for (DocumentFormat format : values()) {
            if (format.mimeType.equalsIgnoreCase(cleanMime)) {
                return format;
            }
        }
        if (cleanMime.contains("markdown")) {
            return MARKDOWN;
        }
        if (cleanMime.contains("html")) {
            return HTML;
        }
        if (cleanMime.contains("pdf")) {
            return PDF;
        }
        if (cleanMime.contains("word") || cleanMime.contains("msword")) {
            return WORD;
        }
        if (cleanMime.contains("spreadsheet") || cleanMime.contains("excel")) {
            return EXCEL;
        }
        if (cleanMime.contains("csv")) {
            return CSV;
        }
        if (cleanMime.contains("json")) {
            return JSON;
        }
        if (cleanMime.startsWith("text/")) {
            return TXT;
        }
        return UNKNOWN;
    }
}
