package io.github.atengk.boot.ai.rag.parser;

import io.github.atengk.boot.ai.exception.AiException;
import io.github.atengk.boot.ai.exception.AiResultCode;
import org.springframework.util.StringUtils;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 纯文本与 Markdown 文档解析器
 * <p>
 * 支持 TXT、Markdown 格式流的轻量高效读取，保留标题与章节层级结构，
 * 并支持自动提取 Markdown 一级标题或 Frontmatter 标题元数据。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-05
 */
public class TextDocumentParser implements DocumentParser {

    private static final Pattern FRONTMATTER_PATTERN = Pattern.compile("^---\\r?\\n([\\s\\S]*?)\\r?\\n---");
    private static final Pattern FRONTMATTER_TITLE_PATTERN = Pattern.compile("(?m)^title:\\s*[\"']?(.*?)[\"']?\\s*$");
    private static final Pattern MARKDOWN_H1_PATTERN = Pattern.compile("(?m)^#\\s+(.+?)\\s*$");
    private static final Pattern MARKDOWN_IMAGE_PATTERN = Pattern.compile("!\\[([^\\]]*)\\]\\(([^)]+)\\)");

    @Override
    public boolean supports(DocumentFormat format, String filename) {
        if (format == DocumentFormat.TXT || format == DocumentFormat.MARKDOWN) {
            return true;
        }
        if (format == DocumentFormat.UNKNOWN && StringUtils.hasText(filename)) {
            DocumentFormat detected = DocumentFormat.fromFilename(filename);
            return detected == DocumentFormat.TXT || detected == DocumentFormat.MARKDOWN;
        }
        return false;
    }

    @Override
    public ParsedDocument parse(InputStream in, ParseOptions options) {
        if (in == null) {
            throw new IllegalArgumentException("文档输入流 InputStream 不能为 null");
        }
        ParseOptions effectiveOptions = options != null ? options : ParseOptions.defaults();
        Charset charset = effectiveOptions.getCharset() != null ? effectiveOptions.getCharset() : StandardCharsets.UTF_8;

        String content;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, charset))) {
            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[4096];
            int read;
            int totalChars = 0;
            Integer maxChars = effectiveOptions.getMaxCharacters();

            while ((read = reader.read(buffer)) != -1) {
                if (maxChars != null && maxChars > 0) {
                    if (totalChars + read > maxChars) {
                        int remaining = maxChars - totalChars;
                        if (remaining > 0) {
                            sb.append(buffer, 0, remaining);
                            totalChars += remaining;
                        }
                        break;
                    }
                }
                sb.append(buffer, 0, read);
                totalChars += read;
            }
            content = sb.toString();
        } catch (IOException e) {
            throw new AiException(AiResultCode.AI_PARSE_ERROR, "读取文本/Markdown文档流失败: " + e.getMessage(), e);
        }

        DocumentFormat format = resolveFormat(effectiveOptions);
        String title = null;
        if (effectiveOptions.isExtractTitle()) {
            title = extractTitle(content, format, effectiveOptions);
        }

        Map<String, Object> metadata = new LinkedHashMap<>();
        if (effectiveOptions.isExtractMetadata()) {
            metadata.put("charset", charset.name());
            if (StringUtils.hasText(effectiveOptions.getFilename())) {
                metadata.put(ParsedDocument.METADATA_FILENAME, effectiveOptions.getFilename());
            }
            if (format == DocumentFormat.MARKDOWN && StringUtils.hasText(content)) {
                Matcher imgMatcher = MARKDOWN_IMAGE_PATTERN.matcher(content);
                List<String> imageUrls = new ArrayList<>();
                while (imgMatcher.find()) {
                    String url = imgMatcher.group(2).trim();
                    if (StringUtils.hasText(url) && !imageUrls.contains(url)) {
                        imageUrls.add(url);
                    }
                }
                if (!imageUrls.isEmpty()) {
                    metadata.put(ParsedDocument.METADATA_IMAGE_URL, imageUrls.get(0));
                    metadata.put(ParsedDocument.METADATA_IMAGE_URLS, imageUrls);
                }
            }
            if (effectiveOptions.getCustomOptions() != null && !effectiveOptions.getCustomOptions().isEmpty()) {
                metadata.putAll(effectiveOptions.getCustomOptions());
            }
        }

        return ParsedDocument.builder()
                .text(content)
                .title(title)
                .characterCount(content.length())
                .pageCount(1)
                .format(format)
                .metadata(metadata)
                .build();
    }

    /**
     * 推断最终生效的文档格式
     */
    private DocumentFormat resolveFormat(ParseOptions options) {
        if (options.getFormat() != null && options.getFormat() != DocumentFormat.UNKNOWN) {
            return options.getFormat();
        }
        if (StringUtils.hasText(options.getFilename())) {
            DocumentFormat detected = DocumentFormat.fromFilename(options.getFilename());
            if (detected == DocumentFormat.TXT || detected == DocumentFormat.MARKDOWN) {
                return detected;
            }
        }
        return DocumentFormat.TXT;
    }

    /**
     * 提取文档标题
     */
    private String extractTitle(String content, DocumentFormat format, ParseOptions options) {
        if (format == DocumentFormat.MARKDOWN && StringUtils.hasText(content)) {
            // 1. 尝试匹配 YAML Frontmatter title
            Matcher fmMatcher = FRONTMATTER_PATTERN.matcher(content);
            if (fmMatcher.find()) {
                String frontmatter = fmMatcher.group(1);
                Matcher titleMatcher = FRONTMATTER_TITLE_PATTERN.matcher(frontmatter);
                if (titleMatcher.find()) {
                    String title = titleMatcher.group(1).trim();
                    if (!title.isEmpty()) {
                        return title;
                    }
                }
            }

            // 2. 尝试匹配第一个 # 一级标题
            Matcher h1Matcher = MARKDOWN_H1_PATTERN.matcher(content);
            if (h1Matcher.find()) {
                String h1 = h1Matcher.group(1).trim();
                if (!h1.isEmpty()) {
                    return h1;
                }
            }
        }

        // 3. 回退基于文件名提取 (去除扩展名)
        if (options != null) {
            String baseName = options.extractTitleFromFilename();
            if (StringUtils.hasText(baseName)) {
                return baseName.trim();
            }
        }

        // 4. 回退提取首行非空文本作为标题候选 (截取前 80 字符)
        if (StringUtils.hasText(content)) {
            for (String line : content.split("\\r?\\n")) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    return trimmed.length() > 80 ? trimmed.substring(0, 80) : trimmed;
                }
            }
        }

        return null;
    }
}
