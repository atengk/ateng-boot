package io.github.atengk.boot.ai.rag.parser;

import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.OrderComparator;
import org.springframework.core.io.Resource;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * RAG 多源异构文档解析统一调度门面 (DocumentParserManager)
 * <p>
 * 统一管理、路由与调度所有注册的 {@link DocumentParser} 插件实现，根据文件后缀名、MIME 类型或显式配置
 * 自动选择最优解析器，并提供面向 {@link Resource}、{@link File}、{@link Path} 与流的对称解析 API。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-05
 */
public class DocumentParserManager {

    private static final Logger log = LoggerFactory.getLogger(DocumentParserManager.class);

    private final List<DocumentParser> parsers;

    public DocumentParserManager(List<DocumentParser> parsers) {
        if (parsers == null || parsers.isEmpty()) {
            this.parsers = Collections.emptyList();
        } else {
            List<DocumentParser> sorted = new ArrayList<>(parsers);
            OrderComparator.sort(sorted);
            this.parsers = Collections.unmodifiableList(sorted);
        }
    }

    /**
     * 便捷构造方法：传入变长解析器数组
     *
     * @param parsers 解析器数组
     */
    public DocumentParserManager(DocumentParser... parsers) {
        this(parsers != null ? List.of(parsers) : Collections.emptyList());
    }

    /**
     * 获取所有已注册的解析器列表 (不可修改视图)
     *
     * @return 解析器列表
     */
    public List<DocumentParser> getParsers() {
        return parsers;
    }

    /**
     * 根据文档格式与文件名查找匹配的解析器
     *
     * @param format   文档格式
     * @param filename 文件名 (可选)
     * @return 匹配的解析器
     * @throws AiException 若未找到可用解析器
     */
    public DocumentParser findParser(DocumentFormat format, String filename) {
        DocumentFormat effectiveFormat = format != null ? format : DocumentFormat.fromFilename(filename);
        for (DocumentParser parser : parsers) {
            if (parser.supports(effectiveFormat, filename)) {
                return parser;
            }
        }
        throw new AiException(AiResultCode.AI_PARSE_ERROR,
                String.format("未找到支持该文档格式的解析器 [format=%s, filename=%s]", effectiveFormat, filename));
    }

    /**
     * 判断当前是否具备能够解析指定格式或文件名的解析器
     *
     * @param format   文档格式
     * @param filename 文件名 (可选)
     * @return true 表示存在可用解析器
     */
    public boolean supports(DocumentFormat format, String filename) {
        DocumentFormat effectiveFormat = format != null ? format : DocumentFormat.fromFilename(filename);
        for (DocumentParser parser : parsers) {
            if (parser.supports(effectiveFormat, filename)) {
                return true;
            }
        }
        return false;
    }

    // ==================== 流解析 API ====================

    /**
     * 解析文档输入流
     *
     * @param in      输入流 (调用后由管理器统一执行确定性安全释放)
     * @param options 解析选项
     * @return 结构化文档结果
     */
    public ParsedDocument parse(InputStream in, ParseOptions options) {
        Assert.notNull(in, "文档输入流 InputStream 绝不能为 null");
        ParseOptions effectiveOptions = options != null ? options : ParseOptions.defaults();
        String filename = effectiveOptions.getFilename();
        DocumentFormat format = effectiveOptions.getFormat() != null
                ? effectiveOptions.getFormat()
                : DocumentFormat.fromFilename(filename);

        DocumentParser parser = findParser(format, filename);
        try (InputStream stream = in) {
            return parser.parse(stream, effectiveOptions);
        } catch (IOException e) {
            throw new AiException(AiResultCode.AI_PARSE_ERROR, "文档流解析与资源释放失败: " + e.getMessage(), e);
        }
    }

    /**
     * 基于输入流与文件名快速解析文档
     *
     * @param in       输入流
     * @param filename 文件名
     * @return 结构化文档结果
     */
    public ParsedDocument parse(InputStream in, String filename) {
        return parse(in, ParseOptions.ofFilename(filename));
    }

    /**
     * 基于输入流、显式文档格式与选项解析
     *
     * @param in      输入流
     * @param format  显式指定的文档格式
     * @param options 解析配置选项 (可选)
     * @return 结构化文档结果
     */
    public ParsedDocument parse(InputStream in, DocumentFormat format, ParseOptions options) {
        ParseOptions effectiveOptions = options != null ? options : ParseOptions.defaults();
        ParseOptions mergedOptions = ParseOptions.builder()
                .charset(effectiveOptions.getCharset())
                .filename(effectiveOptions.getFilename())
                .format(format != null ? format : effectiveOptions.getFormat())
                .maxCharacters(effectiveOptions.getMaxCharacters())
                .extractTitle(effectiveOptions.isExtractTitle())
                .extractMetadata(effectiveOptions.isExtractMetadata())
                .customOptions(effectiveOptions.getCustomOptions())
                .build();
        return parse(in, mergedOptions);
    }

    // ==================== Resource 资源解析 API ====================

    /**
     * 解析 Spring Resource 资源 (如 ClassPathResource, FileSystemResource, UrlResource 等)
     *
     * @param resource Spring 资源对象
     * @param options  解析选项 (可选)
     * @return 结构化文档结果
     */
    public ParsedDocument parse(Resource resource, ParseOptions options) {
        Assert.notNull(resource, "Spring Resource 资源绝不能为 null");
        String filename = resource.getFilename();
        ParseOptions baseOptions = options != null ? options : ParseOptions.defaults();
        ParseOptions effectiveOptions = ParseOptions.builder()
                .charset(baseOptions.getCharset())
                .filename(StringUtils.hasText(baseOptions.getFilename()) ? baseOptions.getFilename() : filename)
                .format(baseOptions.getFormat() != null ? baseOptions.getFormat() : DocumentFormat.fromFilename(filename))
                .maxCharacters(baseOptions.getMaxCharacters())
                .extractTitle(baseOptions.isExtractTitle())
                .extractMetadata(baseOptions.isExtractMetadata())
                .customOptions(baseOptions.getCustomOptions())
                .build();

        try (InputStream in = resource.getInputStream()) {
            return parse(in, effectiveOptions);
        } catch (IOException e) {
            throw new AiException(AiResultCode.AI_PARSE_ERROR, "读取 Spring 资源流失败: " + resource.getDescription(), e);
        }
    }

    /**
     * 基于默认选项解析 Spring Resource 资源
     *
     * @param resource Spring 资源对象
     * @return 结构化文档结果
     */
    public ParsedDocument parse(Resource resource) {
        return parse(resource, ParseOptions.defaults());
    }

    // ==================== File / Path 文件解析 API ====================

    /**
     * 解析本地文件 File
     *
     * @param file    本地文件对象
     * @param options 解析选项 (可选)
     * @return 结构化文档结果
     */
    public ParsedDocument parse(File file, ParseOptions options) {
        Assert.notNull(file, "待解析的 File 对象绝不能为 null");
        if (!file.exists() || !file.isFile()) {
            throw new AiException(AiResultCode.AI_PARSE_ERROR, "指定的文件不存在或不是有效普通文件: " + file.getAbsolutePath());
        }
        ParseOptions baseOptions = options != null ? options : ParseOptions.defaults();
        ParseOptions effectiveOptions = ParseOptions.builder()
                .charset(baseOptions.getCharset())
                .filename(StringUtils.hasText(baseOptions.getFilename()) ? baseOptions.getFilename() : file.getName())
                .format(baseOptions.getFormat() != null ? baseOptions.getFormat() : DocumentFormat.fromFilename(file.getName()))
                .maxCharacters(baseOptions.getMaxCharacters())
                .extractTitle(baseOptions.isExtractTitle())
                .extractMetadata(baseOptions.isExtractMetadata())
                .customOptions(baseOptions.getCustomOptions())
                .build();

        try (InputStream in = new FileInputStream(file)) {
            return parse(in, effectiveOptions);
        } catch (IOException e) {
            throw new AiException(AiResultCode.AI_PARSE_ERROR, "打开并读取本地文件失败: " + file.getAbsolutePath(), e);
        }
    }

    /**
     * 基于默认选项解析本地文件 File
     *
     * @param file 本地文件对象
     * @return 结构化文档结果
     */
    public ParsedDocument parse(File file) {
        return parse(file, ParseOptions.defaults());
    }

    /**
     * 解析本地文件路径 Path
     *
     * @param path    本地文件路径对象
     * @param options 解析选项 (可选)
     * @return 结构化文档结果
     */
    public ParsedDocument parse(Path path, ParseOptions options) {
        Assert.notNull(path, "待解析的 Path 路径绝不能为 null");
        if (!Files.exists(path) || !Files.isRegularFile(path)) {
            throw new AiException(AiResultCode.AI_PARSE_ERROR, "指定的 Path 路径不存在或不是普通文件: " + path.toAbsolutePath());
        }
        String filename = path.getFileName() != null ? path.getFileName().toString() : "";
        ParseOptions baseOptions = options != null ? options : ParseOptions.defaults();
        ParseOptions effectiveOptions = ParseOptions.builder()
                .charset(baseOptions.getCharset())
                .filename(StringUtils.hasText(baseOptions.getFilename()) ? baseOptions.getFilename() : filename)
                .format(baseOptions.getFormat() != null ? baseOptions.getFormat() : DocumentFormat.fromFilename(filename))
                .maxCharacters(baseOptions.getMaxCharacters())
                .extractTitle(baseOptions.isExtractTitle())
                .extractMetadata(baseOptions.isExtractMetadata())
                .customOptions(baseOptions.getCustomOptions())
                .build();

        try (InputStream in = Files.newInputStream(path)) {
            return parse(in, effectiveOptions);
        } catch (IOException e) {
            throw new AiException(AiResultCode.AI_PARSE_ERROR, "打开并读取 Path 文件路径失败: " + path.toAbsolutePath(), e);
        }
    }

    /**
     * 基于默认选项解析本地文件路径 Path
     *
     * @param path 本地文件路径对象
     * @return 结构化文档结果
     */
    public ParsedDocument parse(Path path) {
        return parse(path, ParseOptions.defaults());
    }

    // ==================== 内存纯文本解析 API ====================

    /**
     * 解析内存中的文本字符串
     *
     * @param text     文本内容 (支持 Markdown、HTML 或纯文本)
     * @param format   文档格式
     * @param filename 虚拟文件名 (可选)
     * @return 结构化文档结果
     */
    public ParsedDocument parseText(String text, DocumentFormat format, String filename) {
        String safeText = text != null ? text : "";
        ParseOptions options = ParseOptions.builder()
                .format(format)
                .filename(filename)
                .build();
        byte[] bytes = safeText.getBytes(options.getCharset());
        return parse(new ByteArrayInputStream(bytes), options);
    }

    /**
     * 解析内存中的文本字符串
     *
     * @param text   文本内容
     * @param format 文档格式
     * @return 结构化文档结果
     */
    public ParsedDocument parseText(String text, DocumentFormat format) {
        return parseText(text, format, null);
    }
}
