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
 * 零臃肿超文本 (HTML) 文档解析器
 * <p>
 * 遵循架构零臃肿原则，完全依托 JDK 17 原生能力安全清洗剥离 HTML 标签、{@code <script>}、{@code <style>}
 * 脚本样式块与注释，规范化反转义 HTML 命名与数值实体，并自适应提取 {@code <title>} 文档标题。
 * </p>
 *
 * @author Ateng
 * @since 2026-11-05
 */
public class HtmlDocumentParser implements DocumentParser {

    private static final Pattern HEAD_PATTERN = Pattern.compile("(?is)<head[^>]*>[\\s\\S]*?</head>");
    private static final Pattern SCRIPT_PATTERN = Pattern.compile("(?is)<script[^>]*>[\\s\\S]*?</script>");
    private static final Pattern STYLE_PATTERN = Pattern.compile("(?is)<style[^>]*>[\\s\\S]*?</style>");
    private static final Pattern COMMENT_PATTERN = Pattern.compile("(?s)<!--[\\s\\S]*?-->");
    private static final Pattern TITLE_PATTERN = Pattern.compile("(?is)<title[^>]*>([\\s\\S]*?)</title>");
    private static final Pattern IMG_SRC_PATTERN = Pattern.compile("(?i)<img[^>]+src=[\"']([^\"']+)[\"'][^>]*>");
    private static final Pattern BLOCK_BREAK_PATTERN = Pattern.compile("(?i)<(?:br\\s*/?|hr\\s*/?|/p|/div|/h[1-6]|/tr|/li|/section|/article|/header|/footer|/blockquote|/table|/thead|/tbody|/tfoot|/body)>");
    private static final Pattern ALL_TAGS_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern MULTI_NEWLINE_PATTERN = Pattern.compile("\\n{3,}");
    private static final Pattern HORIZONTAL_WHITESPACE_PATTERN = Pattern.compile("[ \\t\\x0B\\f]+");
    private static final Pattern NUMERIC_ENTITY_PATTERN = Pattern.compile("&#(\\d+);");
    private static final Pattern HEX_ENTITY_PATTERN = Pattern.compile("&#x([0-9a-fA-F]+);");

    @Override
    public boolean supports(DocumentFormat format, String filename) {
        if (format == DocumentFormat.HTML) {
            return true;
        }
        if (format == DocumentFormat.UNKNOWN && StringUtils.hasText(filename)) {
            DocumentFormat detected = DocumentFormat.fromFilename(filename);
            return detected == DocumentFormat.HTML;
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

        String rawHtml;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, charset))) {
            StringBuilder sb = new StringBuilder();
            char[] buffer = new char[4096];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                sb.append(buffer, 0, read);
            }
            rawHtml = sb.toString();
        } catch (IOException e) {
            throw new AiException(AiResultCode.AI_PARSE_ERROR, "读取 HTML 文档流失败: " + e.getMessage(), e);
        }

        // 1. 优先提取文档标题 <title>...</title>
        String title = null;
        if (effectiveOptions.isExtractTitle()) {
            Matcher titleMatcher = TITLE_PATTERN.matcher(rawHtml);
            if (titleMatcher.find()) {
                String rawTitle = titleMatcher.group(1);
                title = cleanAndUnescape(rawTitle).trim();
            }
            if (!StringUtils.hasText(title)) {
                title = effectiveOptions.extractTitleFromFilename();
            }
        }

        // 2. 清洗 HTML 标签与无用代码块
        String cleanText = extractCleanText(rawHtml);

        // 3. 截断最大字符数
        Integer maxChars = effectiveOptions.getMaxCharacters();
        if (maxChars != null && maxChars > 0 && cleanText.length() > maxChars) {
            cleanText = cleanText.substring(0, maxChars);
        }

        // 4. 组装元数据
        Map<String, Object> metadata = new LinkedHashMap<>();
        if (effectiveOptions.isExtractMetadata()) {
            metadata.put("charset", charset.name());
            if (StringUtils.hasText(effectiveOptions.getFilename())) {
                metadata.put(ParsedDocument.METADATA_FILENAME, effectiveOptions.getFilename());
            }
            if (StringUtils.hasText(rawHtml)) {
                Matcher imgMatcher = IMG_SRC_PATTERN.matcher(rawHtml);
                List<String> imageUrls = new ArrayList<>();
                while (imgMatcher.find()) {
                    String src = imgMatcher.group(1).trim();
                    if (StringUtils.hasText(src) && !imageUrls.contains(src)) {
                        imageUrls.add(src);
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
                .text(cleanText)
                .title(title)
                .characterCount(cleanText.length())
                .pageCount(1)
                .format(DocumentFormat.HTML)
                .metadata(metadata)
                .build();
    }

    /**
     * 清洗 HTML 标签并保留纯净段落文本
     */
    private String extractCleanText(String html) {
        if (!StringUtils.hasText(html)) {
            return "";
        }
        // 1. 移除 head 头部、script、style、html 注释与独立 title 标签
        String step1 = HEAD_PATTERN.matcher(html).replaceAll("");
        String step2 = SCRIPT_PATTERN.matcher(step1).replaceAll("");
        String step3 = STYLE_PATTERN.matcher(step2).replaceAll("");
        String step4 = COMMENT_PATTERN.matcher(step3).replaceAll("");
        String step4b = TITLE_PATTERN.matcher(step4).replaceAll("");

        // 2. 将段落、换行标签转译为真实换行符
        String step5 = BLOCK_BREAK_PATTERN.matcher(step4b).replaceAll("\n");

        // 3. 剥离所有 HTML 标签
        String step6 = ALL_TAGS_PATTERN.matcher(step5).replaceAll("");

        // 4. 反转义 HTML 实体字符
        String step7 = unescapeHtmlEntities(step6);

        // 5. 规范化空白行与连续空格
        StringBuilder result = new StringBuilder();
        String[] lines = step7.split("\\r?\\n");
        for (String line : lines) {
            String trimmed = HORIZONTAL_WHITESPACE_PATTERN.matcher(line).replaceAll(" ").trim();
            if (!trimmed.isEmpty()) {
                result.append(trimmed).append("\n");
            }
        }

        String finalText = MULTI_NEWLINE_PATTERN.matcher(result.toString()).replaceAll("\n\n").trim();
        return finalText;
    }

    /**
     * 单行标题简单反转义与清洗
     */
    private String cleanAndUnescape(String raw) {
        if (!StringUtils.hasText(raw)) {
            return "";
        }
        String stripped = ALL_TAGS_PATTERN.matcher(raw).replaceAll("");
        return unescapeHtmlEntities(stripped);
    }

    /**
     * 反转义常用 HTML 实体字符 (无需引入臃肿第三方库)
     */
    private String unescapeHtmlEntities(String text) {
        if (!StringUtils.hasText(text)) {
            return "";
        }
        String s = text
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&#39;", "'")
                .replace("&copy;", "©")
                .replace("&reg;", "®")
                .replace("&trade;", "™")
                .replace("&mdash;", "—")
                .replace("&ndash;", "–")
                .replace("&hellip;", "…");

        // 处理十进制数字实体 &#123;
        if (s.contains("&#")) {
            Matcher decMatcher = NUMERIC_ENTITY_PATTERN.matcher(s);
            StringBuilder sb = new StringBuilder();
            while (decMatcher.find()) {
                try {
                    int code = Integer.parseInt(decMatcher.group(1));
                    decMatcher.appendReplacement(sb, Matcher.quoteReplacement(Character.toString((char) code)));
                } catch (Exception e) {
                    decMatcher.appendReplacement(sb, decMatcher.group(0));
                }
            }
            decMatcher.appendTail(sb);
            s = sb.toString();

            // 处理十六进制数字实体 &#x7b;
            Matcher hexMatcher = HEX_ENTITY_PATTERN.matcher(s);
            sb = new StringBuilder();
            while (hexMatcher.find()) {
                try {
                    int code = Integer.parseInt(hexMatcher.group(1), 16);
                    hexMatcher.appendReplacement(sb, Matcher.quoteReplacement(Character.toString((char) code)));
                } catch (Exception e) {
                    hexMatcher.appendReplacement(sb, hexMatcher.group(0));
                }
            }
            hexMatcher.appendTail(sb);
            s = sb.toString();
        }

        return s;
    }
}
