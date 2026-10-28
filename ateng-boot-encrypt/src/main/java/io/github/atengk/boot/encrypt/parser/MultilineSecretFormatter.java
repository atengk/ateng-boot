package io.github.atengk.boot.encrypt.parser;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 多行大文本机密与 PEM 格式保真工具
 * 自动清洗 YAML 换行与缩进空白杂质，100% 保真还原 PEM 证书/私钥原始首尾边界与换行符
 *
 * @author Ateng
 * @since 2026-10-25
 */
public final class MultilineSecretFormatter {

    private static final Pattern PEM_PATTERN = Pattern.compile(
            "(-----BEGIN [A-Z0-9 ]+-----)(.*?)(-----END [A-Z0-9 ]+-----)",
            Pattern.DOTALL
    );

    public static final String DEFAULT_LINE_SEPARATOR = "\n";
    public static final int PEM_LINE_LENGTH = 64;

    private MultilineSecretFormatter() {
    }

    /**
     * 清洗 Base64 密文在 YAML 缩进折行中产生的空格、换行符（CRLF/LF）与制表符
     *
     * @param rawCiphertext 原始密文字符串
     * @return 清洗后的紧凑 Base64 密文字符串；入参为 null 时返回 null
     */
    public static String cleanCiphertext(String rawCiphertext) {
        if (rawCiphertext == null) {
            return null;
        }
        if (rawCiphertext.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(rawCiphertext.length());
        for (int i = 0; i < rawCiphertext.length(); i++) {
            char c = rawCiphertext.charAt(i);
            if (!Character.isWhitespace(c)) {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /**
     * 判断字符串是否包含标准 PEM 格式标头与标尾
     *
     * @param text 待检测文本
     * @return 若包含 PEM 标头和标尾则返回 true
     */
    public static boolean isPem(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        return text.contains("-----BEGIN ") && text.contains("-----END ");
    }

    /**
     * 标准化还原 PEM 格式文本
     * 若包含 PEM 结构，则严格提取标头标尾，将 Base64 主体规整为标准 64 字符行折叠，并保留换行保真；
     * 若为普通文本，则直接保真返回原值
     *
     * @param text          原始文本
     * @param lineSeparator 换行符（如 "\n" 或 "\r\n"）
     * @return 格式化后的文本
     */
    public static String formatPem(String text, String lineSeparator) {
        if (text == null || text.isEmpty() || !isPem(text)) {
            return text;
        }
        String sep = (lineSeparator != null) ? lineSeparator : DEFAULT_LINE_SEPARATOR;
        Matcher matcher = PEM_PATTERN.matcher(text);
        if (!matcher.find()) {
            return text;
        }

        StringBuilder result = new StringBuilder();
        int lastIndex = 0;
        matcher.reset();
        while (matcher.find()) {
            // 追加 PEM 前缀普通文本
            result.append(text, lastIndex, matcher.start());

            String header = matcher.group(1).trim();
            String rawBody = matcher.group(2);
            String footer = matcher.group(3).trim();

            String cleanBody = cleanCiphertext(rawBody);

            result.append(header).append(sep);
            for (int i = 0; i < cleanBody.length(); i += PEM_LINE_LENGTH) {
                int end = Math.min(i + PEM_LINE_LENGTH, cleanBody.length());
                result.append(cleanBody, i, end).append(sep);
            }
            result.append(footer).append(sep);

            lastIndex = matcher.end();
        }
        if (lastIndex < text.length()) {
            String trailing = text.substring(lastIndex);
            if (!trailing.trim().isEmpty()) {
                result.append(trailing);
            }
        }

        return result.toString();
    }

    /**
     * 默认按 LF ("\n") 标准化还原 PEM 格式文本
     *
     * @param text 原始文本
     * @return 格式化后的文本
     */
    public static String formatPem(String text) {
        return formatPem(text, DEFAULT_LINE_SEPARATOR);
    }

    /**
     * 将 Base64 字符串格式化为指定类型的标准 PEM 文本
     *
     * @param type   PEM 类型（例如 "PUBLIC KEY"、"PRIVATE KEY"）
     * @param base64 Base64 编码的密钥字节
     * @return 规整的 PEM 格式字符串
     */
    public static String formatAsPem(String type, String base64) {
        if (type == null || base64 == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("-----BEGIN ").append(type).append("-----\n");
        for (int i = 0; i < base64.length(); i += PEM_LINE_LENGTH) {
            sb.append(base64, i, Math.min(i + PEM_LINE_LENGTH, base64.length())).append("\n");
        }
        sb.append("-----END ").append(type).append("-----\n");
        return sb.toString();
    }
}
