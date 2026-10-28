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
            result = applyRegexReplacement(result, customPattern,
                    m -> parseContent(m.group(0), null, m.group(1)), decryptorTokenFunction);
        }

        // 2. 处理标准 ENC 模式
        return applyRegexReplacement(result, defaultPattern,
                m -> parseContent(m.group(0), m.group(1), m.group(2)), decryptorTokenFunction);
    }

    private String applyRegexReplacement(String text, Pattern pattern,
                                         Function<Matcher, ParsedToken> tokenExtractor,
                                         Function<ParsedToken, String> decryptor) {
        Matcher matcher = pattern.matcher(text);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            ParsedToken token = tokenExtractor.apply(matcher);
            String replacement = decryptor.apply(token);
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
                String ciphertext = trimmed.substring(atIndex + 1);
                return new ParsedToken(rawToken, keyId, algo, MultilineSecretFormatter.cleanCiphertext(ciphertext));
            }
        }
        return new ParsedToken(rawToken, keyId, null, MultilineSecretFormatter.cleanCiphertext(trimmed));
    }

    /**
     * 格式化生成标准的 ENC[keyId](algo@ciphertext) 或 ENC(ciphertext) 密文标记
     *
     * @param keyId 密钥别名标识（为 null、空串或 "default" 时省略 [keyId] 标签）
     * @param algorithm 加密算法（为 null 或默认 AES_GCM 时省略 algo@ 标签）
     * @param ciphertext 密文内容
     * @return 格式化后的密文标记，如 ENC[db](SM4-CBC@...) 或 ENC(...)
     */
    public static String formatToken(String keyId, EncryptAlgorithm algorithm, String ciphertext) {
        return formatToken(keyId, algorithm, EncryptAlgorithm.AES_GCM, ciphertext);
    }

    /**
     * 格式化生成密文标记，并指定默认算法基准
     *
     * @param keyId 密钥别名标识（为 null、空串或 "default" 时省略 [keyId] 标签）
     * @param algorithm 实际加密算法
     * @param defaultAlgorithm 当前环境的默认加密算法
     * @param ciphertext 密文内容
     * @return 格式化后的密文标记
     */
    public static String formatToken(String keyId, EncryptAlgorithm algorithm, EncryptAlgorithm defaultAlgorithm, String ciphertext) {
        if (ciphertext == null) {
            throw new IllegalArgumentException("ciphertext 不能为空");
        }
        StringBuilder sb = new StringBuilder("ENC");
        if (keyId != null && !keyId.trim().isEmpty() && !"default".equalsIgnoreCase(keyId.trim())) {
            sb.append("[").append(keyId.trim()).append("]");
        }
        sb.append("(");
        EncryptAlgorithm baseAlgo = defaultAlgorithm != null ? defaultAlgorithm : EncryptAlgorithm.AES_GCM;
        if (algorithm != null && algorithm != baseAlgo) {
            sb.append(algorithm.getCode()).append("@");
        }
        sb.append(ciphertext).append(")");
        return sb.toString();
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
