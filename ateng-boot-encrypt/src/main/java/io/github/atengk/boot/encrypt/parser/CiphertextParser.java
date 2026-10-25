package io.github.atengk.boot.encrypt.parser;

import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.ToString;

import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 密文语法解析与正则提取器
 * 支持整字段与内嵌字符串匹配、多 Key 别名 [keyId] 与显式算法前缀声明 (algo@...)
 *
 * @author Ateng
 * @since 2026-10-25
 */
public class CiphertextParser {

    private final String prefix;
    private final String suffix;
    private final Pattern defaultPattern;
    private final Pattern customPattern;

    public CiphertextParser(String prefix, String suffix) {
        this.prefix = (prefix != null && !prefix.trim().isEmpty()) ? prefix.trim() : "ENC(";
        this.suffix = (suffix != null && !suffix.trim().isEmpty()) ? suffix.trim() : ")";
        // 通用多模式：支持 ENC[keyId](content) 以及 ENC(content)
        this.defaultPattern = Pattern.compile("ENC(?:\\[([^\\]]+)\\])?\\((.*?)\\)", Pattern.DOTALL);
        if ("ENC(".equals(this.prefix) && ")".equals(this.suffix)) {
            this.customPattern = null;
        } else {
            this.customPattern = Pattern.compile(Pattern.quote(this.prefix) + "(.*?)" + Pattern.quote(this.suffix), Pattern.DOTALL);
        }
    }

    public CiphertextParser() {
        this("ENC(", ")");
    }

    /**
     * 判断文本中是否包含需要解密的密文标记
     */
    public boolean hasEncrypted(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        if (defaultPattern.matcher(value).find()) {
            return true;
        }
        return customPattern != null && customPattern.matcher(value).find();
    }

    /**
     * 替换文本中的所有密文标记为解密后的明文
     *
     * @param value 原始文本（可能为整字段密文或内嵌密文的复合连接串）
     * @param decryptorTokenFunction 接收 ParsedToken 并返回解密明文的函数
     * @return 替换后的完整明文字符串
     */
    public String replaceTokens(String value, Function<ParsedToken, String> decryptorTokenFunction) {
        if (value == null || value.isEmpty() || !hasEncrypted(value)) {
            return value;
        }

        String result = value;
        // 1. 处理自定义前后缀模式（若配置了非默认前后缀）
        if (customPattern != null) {
            Matcher matcher = customPattern.matcher(result);
            StringBuilder sb = new StringBuilder();
            while (matcher.find()) {
                String rawToken = matcher.group(0);
                String content = matcher.group(1);
                ParsedToken token = parseContent(rawToken, null, content);
                String replacement = decryptorTokenFunction.apply(token);
                matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(sb);
            result = sb.toString();
        }

        // 2. 处理标准 ENC 模式
        Matcher matcher = defaultPattern.matcher(result);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String rawToken = matcher.group(0);
            String keyId = matcher.group(1);
            String content = matcher.group(2);
            ParsedToken token = parseContent(rawToken, keyId, content);
            String replacement = decryptorTokenFunction.apply(token);
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }

    /**
     * 解析单个内部载荷，识别可选算法声明（如 SM4@密文 或 RSA@密文）
     */
    private ParsedToken parseContent(String rawToken, String keyId, String content) {
        if (content == null) {
            return new ParsedToken(rawToken, keyId, null, "");
        }
        String trimmed = content.trim();
        int atIndex = trimmed.indexOf('@');
        if (atIndex > 0) {
            String algoPrefix = trimmed.substring(0, atIndex).trim();
            EncryptAlgorithm algo = EncryptAlgorithm.fromCode(algoPrefix);
            if (algo != null) {
                String ciphertext = trimmed.substring(atIndex + 1).trim();
                return new ParsedToken(rawToken, keyId, algo, ciphertext);
            }
        }
        return new ParsedToken(rawToken, keyId, null, trimmed);
    }

    @Getter
    @ToString
    @RequiredArgsConstructor
    public static class ParsedToken {
        private final String rawToken;
        private final String keyId;
        private final EncryptAlgorithm algorithm;
        private final String ciphertext;
    }
}
