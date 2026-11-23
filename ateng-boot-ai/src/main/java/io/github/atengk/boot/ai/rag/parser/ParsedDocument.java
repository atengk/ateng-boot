package io.github.atengk.boot.ai.rag.parser;

import lombok.Builder;
import lombok.Getter;
import lombok.ToString;
import org.springframework.util.StringUtils;

import java.io.Serializable;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 结构化文档解析结果封装
 *
 * @author Ateng
 * @since 2026-11-05
 */
@Getter
@ToString
public class ParsedDocument implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 元数据键：文档标题
     */
    public static final String METADATA_TITLE = "title";

    /**
     * 元数据键：文档格式
     */
    public static final String METADATA_FORMAT = "format";

    /**
     * 元数据键：字符总数
     */
    public static final String METADATA_CHARACTER_COUNT = "character_count";

    /**
     * 元数据键：总页数或章节数
     */
    public static final String METADATA_PAGE_COUNT = "page_count";

    /**
     * 元数据键：源文件名
     */
    public static final String METADATA_FILENAME = "filename";

    /**
     * 元数据键：首要关联图表 URL
     */
    public static final String METADATA_IMAGE_URL = "image_url";

    /**
     * 元数据键：文档关联的所有图表 URL 列表
     */
    public static final String METADATA_IMAGE_URLS = "image_urls";

    /**
     * 元数据键：首要关联图表对象存储 Key
     */
    public static final String METADATA_IMAGE_STORAGE_KEY = "image_storage_key";

    /**
     * 元数据键：文档关联的所有图表对象存储 Key 列表
     */
    public static final String METADATA_IMAGE_STORAGE_KEYS = "image_storage_keys";

    /**
     * 元数据键：文档关联的所有图表视觉摘要描述列表
     */
    public static final String METADATA_IMAGE_CAPTIONS = "image_captions";

    /**
     * 提取后的纯净文本内容 (保证非 null)
     */
    private final String text;

    /**
     * 文档标题 (若提取不到则可为空)
     */
    private final String title;

    /**
     * 文本字符总数
     */
    private final int characterCount;

    /**
     * 文档页数或段落章节数 (纯文本默认为 1)
     */
    private final int pageCount;

    /**
     * 文档格式
     */
    private final DocumentFormat format;

    /**
     * 文档元数据属性字典
     */
    private final Map<String, Object> metadata;

    @Builder
    public ParsedDocument(String text,
                          String title,
                          Integer characterCount,
                          Integer pageCount,
                          DocumentFormat format,
                          Map<String, Object> metadata) {
        this.text = text != null ? text : "";
        this.title = title;
        this.characterCount = characterCount != null ? characterCount : this.text.length();
        this.pageCount = pageCount != null && pageCount > 0 ? pageCount : 1;
        this.format = format != null ? format : DocumentFormat.UNKNOWN;

        Map<String, Object> meta = new LinkedHashMap<>();
        if (metadata != null) {
            meta.putAll(metadata);
        }
        if (StringUtils.hasText(this.title) && !meta.containsKey(METADATA_TITLE)) {
            meta.put(METADATA_TITLE, this.title);
        }
        meta.putIfAbsent(METADATA_FORMAT, this.format.name());
        meta.putIfAbsent(METADATA_CHARACTER_COUNT, this.characterCount);
        meta.putIfAbsent(METADATA_PAGE_COUNT, this.pageCount);
        this.metadata = Collections.unmodifiableMap(meta);
    }

    /**
     * 判断提取文本是否为空或全为空白字符
     *
     * @return true 表示无有效文本内容
     */
    public boolean isEmpty() {
        return text.isBlank();
    }

    /**
     * 是否包含有效文档标题
     *
     * @return true 表示提取到了标题
     */
    public boolean hasTitle() {
        return StringUtils.hasText(title);
    }

    /**
     * 创建空文档解析结果
     *
     * @param format 文档格式
     * @return 空 ParsedDocument 实例
     */
    public static ParsedDocument empty(DocumentFormat format) {
        return builder()
                .text("")
                .format(format)
                .characterCount(0)
                .pageCount(0)
                .build();
    }
}
