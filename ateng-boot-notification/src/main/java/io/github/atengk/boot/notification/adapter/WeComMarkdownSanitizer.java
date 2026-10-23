package io.github.atengk.boot.notification.adapter;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 企业微信 Markdown 语法安全清洗器
 * 针对企业微信群机器人 Markdown 仅支持有限标签的物理特性，
 * 自动将 Markdown 表格转译为引用键值列表、剥离不支持的外链图片与危险 HTML 标签，防御客户端解析崩溃
 *
 * @author Ateng
 * @since 2026-10-23
 */
public final class WeComMarkdownSanitizer {

    private static final Pattern IMAGE_PATTERN = Pattern.compile("!\\[(.*?)\\]\\((.*?)\\)");
    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("</?(?!(?:font\\b|/font\\b))[a-zA-Z][a-zA-Z0-9]*(?:\\s+[^>]*)?/?>", Pattern.CASE_INSENSITIVE);
    private static final Pattern TABLE_DIVIDER_PATTERN = Pattern.compile("^\\s*\\|?\\s*[:\\-]+\\s*(\\|\\s*[:\\-]+\\s*)+\\|?\\s*$");

    private WeComMarkdownSanitizer() {
    }

    /**
     * 对原始 Markdown 文本执行企业微信合规安全清洗
     *
     * @param markdown 原始 Markdown 字符串
     * @return 符合企业微信规范的安全 Markdown 字符串
     */
    public static String sanitize(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return markdown;
        }

        // 1. 将外链图片 ![alt](url) 转换为文本链接 [图片: alt](url)
        String processed = IMAGE_PATTERN.matcher(markdown).replaceAll(mr -> {
            String alt = mr.group(1);
            String url = mr.group(2);
            String label = (alt != null && !alt.isBlank()) ? "图片: " + alt.trim() : "图片链接";
            return "[" + label + "](" + url + ")";
        });

        // 2. 剥离除 <font color="..."> 与 </font> 之外的非法 HTML 标签
        processed = HTML_TAG_PATTERN.matcher(processed).replaceAll("");

        // 3. 将 Markdown 表格自适应转译为键值引用列表 (> 键: 值)
        processed = sanitizeTables(processed);

        return processed;
    }

    /**
     * 将文本中的 Markdown 表格语法逐行解析并转译为企业微信友好的引用列表
     */
    private static String sanitizeTables(String text) {
        String[] lines = text.split("\\r?\\n", -1);
        List<String> outputLines = new ArrayList<>();

        int i = 0;
        while (i < lines.length) {
            String currentLine = lines[i];

            // 探测是否为表格表头（当前行包含 | 且下一行为表格分割行）
            if (isTableHeader(lines, i)) {
                List<String> headers = parseTableRow(currentLine);
                i++; // 跳过表头行
                i++; // 跳过分割行 (如 |---|---|)

                // 连续处理数据行
                while (i < lines.length && isTableDataRow(lines[i])) {
                    List<String> cells = parseTableRow(lines[i]);
                    StringBuilder rowBuilder = new StringBuilder("> ");
                    for (int c = 0; c < headers.size(); c++) {
                        if (c > 0) {
                            rowBuilder.append(" | ");
                        }
                        String cellVal = c < cells.size() ? cells.get(c) : "";
                        rowBuilder.append("**").append(headers.get(c)).append("**: ").append(cellVal);
                    }
                    outputLines.add(rowBuilder.toString());
                    i++;
                }
            } else {
                outputLines.add(currentLine);
                i++;
            }
        }

        return String.join("\n", outputLines);
    }

    private static boolean isTableHeader(String[] lines, int currentIndex) {
        if (currentIndex + 1 >= lines.length) {
            return false;
        }
        String current = lines[currentIndex];
        String next = lines[currentIndex + 1];
        return current.contains("|") && TABLE_DIVIDER_PATTERN.matcher(next).matches();
    }

    private static boolean isTableDataRow(String line) {
        if (line == null || line.isBlank()) {
            return false;
        }
        return line.contains("|") && !TABLE_DIVIDER_PATTERN.matcher(line).matches();
    }

    private static List<String> parseTableRow(String line) {
        String trimmed = line.trim();
        if (trimmed.startsWith("|")) {
            trimmed = trimmed.substring(1);
        }
        if (trimmed.endsWith("|")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return Arrays.stream(trimmed.split("\\|", -1))
                .map(String::trim)
                .toList();
    }
}
