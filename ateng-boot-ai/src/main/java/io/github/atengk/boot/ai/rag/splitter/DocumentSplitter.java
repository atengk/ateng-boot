package io.github.atengk.boot.ai.rag.splitter;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import org.springframework.core.io.Resource;
import org.springframework.util.StreamUtils;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 原生轻量长文本智能切片门面顶层接口
 * <p>
 * 扩展 LangChain4j 标准 {@link dev.langchain4j.data.document.DocumentSplitter}，
 * 支持纯字符串、带元数据字典、Spring {@link Resource} 及 {@link InputStream} 输入流的智能分块切片。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-05
 */
public interface DocumentSplitter extends dev.langchain4j.data.document.DocumentSplitter {

    /**
     * 将纯文本内容切分为 TextSegment 片段列表
     *
     * @param text 待切片的原始文本
     * @return 文本片段列表，绝不返回 null
     */
    default List<TextSegment> split(String text) {
        return split(text, new Metadata());
    }

    /**
     * 将纯文本内容切分为带初始元数据的 TextSegment 片段列表 (Map 形式)
     *
     * @param text     待切片的原始文本
     * @param metadata 初始元数据键值对
     * @return 文本片段列表，绝不返回 null
     */
    default List<TextSegment> split(String text, Map<String, ?> metadata) {
        return split(text, metadata != null ? Metadata.from(metadata) : new Metadata());
    }

    /**
     * 将纯文本内容切分为带初始元数据的 TextSegment 片段列表
     *
     * @param text     待切片的原始文本
     * @param metadata 初始元数据对象
     * @return 文本片段列表，绝不返回 null
     */
    List<TextSegment> split(String text, Metadata metadata);

    /**
     * 适配 LangChain4j Document 标准接口
     *
     * @param document 待切片标准 Document 对象
     * @return 文本片段列表，绝不返回 null
     */
    @Override
    default List<TextSegment> split(Document document) {
        if (document == null || document.text() == null || document.text().isBlank()) {
            return Collections.emptyList();
        }
        return split(document.text(), document.metadata());
    }

    /**
     * 从 Spring Resource 资源中读取文本并执行切片 (默认 UTF-8 编码)
     *
     * @param resource 待读取资源
     * @return 文本片段列表，绝不返回 null
     */
    default List<TextSegment> split(Resource resource) {
        return split(resource, StandardCharsets.UTF_8);
    }

    /**
     * 从 Spring Resource 资源中读取文本并执行切片 (指定字符集)
     *
     * @param resource 待读取资源
     * @param charset  字符编码
     * @return 文本片段列表，绝不返回 null
     */
    default List<TextSegment> split(Resource resource, Charset charset) {
        if (resource == null) {
            throw new IllegalArgumentException("待切片 Resource 资源不能为空");
        }
        try (InputStream in = resource.getInputStream()) {
            Metadata metadata = new Metadata();
            String filename = resource.getFilename();
            if (filename == null && resource.getDescription() != null) {
                String desc = resource.getDescription();
                int start = desc.indexOf('[');
                int end = desc.lastIndexOf(']');
                if (start != -1 && end > start) {
                    filename = desc.substring(start + 1, end);
                } else {
                    filename = desc;
                }
            }
            if (filename != null) {
                metadata.put("file_name", filename);
            }
            return split(in, charset, metadata);
        } catch (IOException e) {
            throw new IllegalArgumentException("读取 Resource 资源失败: " + resource.getDescription(), e);
        }
    }

    /**
     * 从 InputStream 输入流中读取文本并执行切片 (默认 UTF-8 编码)
     *
     * @param inputStream 待读取输入流
     * @return 文本片段列表，绝不返回 null
     */
    default List<TextSegment> split(InputStream inputStream) {
        return split(inputStream, StandardCharsets.UTF_8, new Metadata());
    }

    /**
     * 从 InputStream 输入流中读取文本并执行切片 (指定字符集)
     *
     * @param inputStream 待读取输入流
     * @param charset     字符编码
     * @return 文本片段列表，绝不返回 null
     */
    default List<TextSegment> split(InputStream inputStream, Charset charset) {
        return split(inputStream, charset, new Metadata());
    }

    /**
     * 从 InputStream 输入流中读取文本并执行切片 (指定字符集与元数据)
     *
     * @param inputStream 待读取输入流
     * @param charset     字符编码
     * @param metadata    初始元数据
     * @return 文本片段列表，绝不返回 null
     */
    default List<TextSegment> split(InputStream inputStream, Charset charset, Metadata metadata) {
        if (inputStream == null) {
            throw new IllegalArgumentException("待切片 InputStream 输入流不能为空");
        }
        try {
            String text = StreamUtils.copyToString(inputStream, charset != null ? charset : StandardCharsets.UTF_8);
            return split(text, metadata);
        } catch (IOException e) {
            throw new IllegalArgumentException("读取 InputStream 输入流失败", e);
        }
    }
}
