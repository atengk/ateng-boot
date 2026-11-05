package io.github.atengk.boot.ai.rag.parser;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 文档解析配置选项
 *
 * @author Ateng
 * @since 2026-11-05
 */
@Getter
@ToString
public class ParseOptions {

    /**
     * 文本字符编码 (默认 UTF-8)
     */
    private final Charset charset;

    /**
     * 源文件名 (可选，用于辅助标题提取与格式推断)
     */
    private final String filename;

    /**
     * 显式指定的文档格式 (可选，若不指定则自动基于文件名或内容探测)
     */
    private final DocumentFormat format;

    /**
     * 最大解析字符数上限 (防止超大文件内存溢出，null 或 <= 0 表示不限制)
     */
    private final Integer maxCharacters;

    /**
     * 是否尝试自动从文档中提取标题 (默认 true)
     */
    private final boolean extractTitle;

    /**
     * 是否提取文档结构元数据 (默认 true)
     */
    private final boolean extractMetadata;

    /**
     * 自定义扩展参数字典
     */
    private final Map<String, Object> customOptions;

    /**
     * 默认配置选项单例
     */
    private static final ParseOptions DEFAULT_INSTANCE = builder().build();

    @Builder
    public ParseOptions(Charset charset,
                        String filename,
                        DocumentFormat format,
                        Integer maxCharacters,
                        Boolean extractTitle,
                        Boolean extractMetadata,
                        Map<String, Object> customOptions) {
        this.charset = charset != null ? charset : StandardCharsets.UTF_8;
        this.filename = filename;
        this.format = format;
        this.maxCharacters = maxCharacters;
        this.extractTitle = extractTitle == null || extractTitle;
        this.extractMetadata = extractMetadata == null || extractMetadata;
        this.customOptions = customOptions != null
                ? Collections.unmodifiableMap(new LinkedHashMap<>(customOptions))
                : Collections.emptyMap();
    }

    /**
     * 获取标准默认解析选项
     *
     * @return 默认选项实例
     */
    public static ParseOptions defaults() {
        return DEFAULT_INSTANCE;
    }

    /**
     * 基于源文件名快速构造默认解析选项
     *
     * @param filename 源文件名
     * @return 带有文件名的解析选项
     */
    public static ParseOptions ofFilename(String filename) {
        return builder().filename(filename).build();
    }

    /**
     * 基于指定字符集与源文件名快速构造解析选项
     *
     * @param charset  字符编码
     * @param filename 源文件名
     * @return 解析选项
     */
    public static ParseOptions of(Charset charset, String filename) {
        return builder().charset(charset).filename(filename).build();
    }

    /**
     * 从源文件名中安全提取去除路径与后缀的基础文档标题 (环境宿主中立)
     *
     * @return 基础文件名标题；若未配置有效文件名则返回 null
     */
    public String extractTitleFromFilename() {
        if (filename == null || filename.isBlank()) {
            return null;
        }
        String cleanName = filename.trim();
        int slashIdx = Math.max(cleanName.lastIndexOf('/'), cleanName.lastIndexOf('\\'));
        if (slashIdx >= 0 && slashIdx < cleanName.length() - 1) {
            cleanName = cleanName.substring(slashIdx + 1);
        }
        int dotIdx = cleanName.lastIndexOf('.');
        return dotIdx > 0 ? cleanName.substring(0, dotIdx) : cleanName;
    }
}
