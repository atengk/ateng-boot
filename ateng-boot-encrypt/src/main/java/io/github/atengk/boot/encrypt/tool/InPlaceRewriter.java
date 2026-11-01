package io.github.atengk.boot.encrypt.tool;

import io.github.atengk.boot.encrypt.config.EncryptAlgorithm;
import io.github.atengk.boot.encrypt.decryptor.AesCbcConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.AesGcmConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.ConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.ConfigEncryptor;
import io.github.atengk.boot.encrypt.decryptor.RsaConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.Sm2ConfigDecryptor;
import io.github.atengk.boot.encrypt.decryptor.Sm4ConfigDecryptor;
import io.github.atengk.boot.encrypt.exception.ConfigDecryptionException;
import io.github.atengk.boot.encrypt.key.MultiKeyRegistry;
import io.github.atengk.boot.encrypt.parser.CiphertextParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * YAML/Properties 配置文件整文件就地智能加解密回写器
 * 逐行解析扫描目标文件，智能识别敏感明文字段或 # @encrypt 标记，在 100% 严格保持原有注释、缩进与空白格式不变的前提下，
 * 就地重写为密文/明文，并自动生成 .bak 备份文件
 *
 * @author Ateng
 * @since 2026-10-25
 */
public class InPlaceRewriter {

    private static final Logger log = LoggerFactory.getLogger(InPlaceRewriter.class);

    /**
     * 默认敏感键正则匹配模式
     */
    public static final String DEFAULT_SENSITIVE_KEY_PATTERN = ".*password.*|.*secret.*|.*token.*|.*private-key.*";

    /**
     * 显式标记正则匹配模式：匹配 # @encrypt, ! @encrypt, [#!] @encrypt[keyId], [#!] @encrypt(ALGO), [#!] @encrypt[keyId](ALGO)
     */
    private static final Pattern ENCRYPT_DIRECTIVE_PATTERN = Pattern.compile(
            "[#!]\\s*@encrypt(?:\\[([^\\]]+)\\])?(?:\\(([^\\)]+)\\))?", Pattern.CASE_INSENSITIVE);

    /**
     * 带引号值的 YAML / Properties 键值行正则：提取前导缩进、键名、分隔符、带引号值以及行尾注释
     */
    private static final Pattern QUOTED_LINE_PATTERN = Pattern.compile(
            "^(\\s*)([a-zA-Z0-9_.\\[\\]-]+)(\\s*[:=]\\s*)(\"[^\"]*\"|'[^']*')(\\s*[#!].*)?$");

    /**
     * 标准无引号 YAML / Properties 键值行正则：提取前导缩进、键名、分隔符、无引号值以及行尾注释
     */
    private static final Pattern STANDARD_LINE_PATTERN = Pattern.compile(
            "^(\\s*)([a-zA-Z0-9_.\\[\\]-]+)(\\s*[:=]\\s*)([^#!\\r\\n]+?)?(\\s*[#!].*)?$");

    /**
     * 动态占位符正则：匹配 ${...} 环境变量与 #{...} SpEL 表达式
     */
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile(
            "(\\$\\{[^\\r\\n]*?\\}|#\\{[^\\r\\n]*?\\})");

    private final MultiKeyRegistry keyRegistry;
    private final EncryptAlgorithm defaultAlgorithm;
    private final Pattern sensitivePattern;
    private final boolean autoDetectSensitiveKeys;
    private final CiphertextParser parser;

    public InPlaceRewriter(MultiKeyRegistry keyRegistry, EncryptAlgorithm defaultAlgorithm,
                           String sensitiveKeyPattern, boolean autoDetectSensitiveKeys) {
        this.keyRegistry = keyRegistry != null ? keyRegistry : new MultiKeyRegistry();
        this.defaultAlgorithm = defaultAlgorithm != null ? defaultAlgorithm : EncryptAlgorithm.AES_GCM;
        String pattern = (sensitiveKeyPattern != null && !sensitiveKeyPattern.trim().isEmpty())
                ? sensitiveKeyPattern : DEFAULT_SENSITIVE_KEY_PATTERN;
        this.sensitivePattern = Pattern.compile(pattern, Pattern.CASE_INSENSITIVE);
        this.autoDetectSensitiveKeys = autoDetectSensitiveKeys;
        this.parser = new CiphertextParser();
    }

    public InPlaceRewriter(String defaultPassword, EncryptAlgorithm defaultAlgorithm) {
        this(new MultiKeyRegistry(defaultPassword), defaultAlgorithm, DEFAULT_SENSITIVE_KEY_PATTERN, true);
    }

    public InPlaceRewriter(String defaultPassword) {
        this(new MultiKeyRegistry(defaultPassword), EncryptAlgorithm.AES_GCM, DEFAULT_SENSITIVE_KEY_PATTERN, true);
    }

    private record PreparedRewrite(Path targetFile, Path backupFile, List<String> lines, String eol, boolean endsWithEol) {
    }

    private PreparedRewrite prepareRewrite(Path targetFile, String operation) throws IOException {
        Objects.requireNonNull(targetFile, "targetFile 不能为空");
        if (!Files.exists(targetFile) || Files.isDirectory(targetFile)) {
            throw new IllegalArgumentException("目标配置文件不存在或为目录: " + targetFile);
        }

        // 1. 生成 .bak 备份文件
        Path backupFile = createBackup(targetFile);
        log.info("[ateng-boot-encrypt] 正在执行就地{}，已生成源文件备份: {}", operation, backupFile);

        // 2. 读取原始内容并探测换行符规范
        String rawContent = Files.readString(targetFile, StandardCharsets.UTF_8);
        String eol = rawContent.contains("\r\n") ? "\r\n" : "\n";
        boolean endsWithEol = rawContent.endsWith("\n") || rawContent.endsWith("\r");

        List<String> lines = Files.readAllLines(targetFile, StandardCharsets.UTF_8);
        return new PreparedRewrite(targetFile, backupFile, lines, eol, endsWithEol);
    }

    /**
     * 对指定文件执行就地加密重写，并生成 .bak 备份文件
     *
     * @param targetFile 目标 YAML/Properties 配置文件路径
     * @return 实际重写加密的属性键数量
     * @throws IOException 文件读写或备份异常
     */
    public int rewriteEncrypt(Path targetFile) throws IOException {
        PreparedRewrite prep = prepareRewrite(targetFile, "加密");
        List<String> lines = prep.lines();
        List<String> rewrittenLines = new ArrayList<>(lines.size());
        int encryptedCount = 0;
        Directive previousDirective = null;

        for (int lineIdx = 0; lineIdx < lines.size(); lineIdx++) {
            String line = lines.get(lineIdx);
            String trimmed = line.trim();

            // 1. 处理 YAML 多文档分隔符 "---" 或 "..."
            if (trimmed.equals("---") || trimmed.startsWith("--- ") || trimmed.startsWith("---#")
                    || trimmed.equals("...") || trimmed.startsWith("... ") || trimmed.startsWith("...#")) {
                previousDirective = null;
                rewrittenLines.add(line);
                continue;
            }

            // 2. 处理独立一行的指令注释或全行注释（以 # 或 ! 开头）
            if (trimmed.startsWith("#") || trimmed.startsWith("!")) {
                Matcher directiveMatcher = ENCRYPT_DIRECTIVE_PATTERN.matcher(trimmed);
                if (directiveMatcher.find()) {
                    previousDirective = new Directive(directiveMatcher.group(1), directiveMatcher.group(2));
                }
                rewrittenLines.add(line);
                continue;
            }

            // 3. 空白行直接保留
            if (trimmed.isEmpty()) {
                rewrittenLines.add(line);
                previousDirective = null;
                continue;
            }

            // 4. 解析 YAML / Properties 键值行
            Matcher matcher = QUOTED_LINE_PATTERN.matcher(line);
            boolean isQuoted = matcher.matches();
            if (!isQuoted) {
                matcher = STANDARD_LINE_PATTERN.matcher(line);
            }

            if (isQuoted || matcher.matches()) {
                String indent = matcher.group(1);
                String key = matcher.group(2);
                String separator = matcher.group(3);
                String rawValue = matcher.group(4);
                String comment = matcher.group(5) != null ? matcher.group(5) : "";

                // 若该行无值（例如对象节点分组 "datasource:"），跳过
                if (rawValue == null || rawValue.trim().isEmpty()) {
                    rewrittenLines.add(line);
                    previousDirective = null;
                    continue;
                }

                String cleanValue = rawValue.trim();

                // 幂等防卫：若已是密文标记，坚决跳过，杜绝二次加密
                if (parser.hasEncrypted(cleanValue)) {
                    rewrittenLines.add(line);
                    previousDirective = null;
                    continue;
                }

                // 检查行尾是否存在显式指令（支持 # 或 ! 开头的注释）
                Directive inlineDirective = null;
                if (!comment.isEmpty()) {
                    Matcher inlineMatcher = ENCRYPT_DIRECTIVE_PATTERN.matcher(comment);
                    if (inlineMatcher.find()) {
                        inlineDirective = new Directive(inlineMatcher.group(1), inlineMatcher.group(2));
                    }
                }

                Directive effectiveDirective = (inlineDirective != null) ? inlineDirective : previousDirective;
                boolean hasPlaceholder = isPlaceholder(cleanValue);
                boolean isBlockScalar = isYamlBlockScalarIndicator(cleanValue);

                boolean shouldEncrypt;
                boolean shouldEncryptBlockScalar = false;
                if (effectiveDirective != null) {
                    if (isBlockScalar) {
                        shouldEncrypt = false;
                        shouldEncryptBlockScalar = true;
                    } else {
                        shouldEncrypt = true;
                    }
                } else if (hasPlaceholder || isBlockScalar) {
                    // 占位符绝对免疫与多行块标量保护：检测到 ${...}、#{...} 或 |/> 标量头时，无显式指令严格豁免就地加密
                    shouldEncrypt = false;
                } else {
                    shouldEncrypt = autoDetectSensitiveKeys && sensitivePattern.matcher(key).matches();
                }

                if (shouldEncryptBlockScalar) {
                    // 提取后续隶属于该块标量的所有多行内容（缩进深度大于当前键行的缩进）
                    int baseIndent = countLeadingSpaces(indent);
                    StringBuilder blockContent = new StringBuilder();
                    int nextIdx = lineIdx + 1;
                    while (nextIdx < lines.size()) {
                        String nextLine = lines.get(nextIdx);
                        String nextTrimmed = nextLine.trim();
                        int nextIndent = countLeadingSpaces(nextLine);
                        if (nextTrimmed.isEmpty()) {
                            blockContent.append("\n");
                            nextIdx++;
                        } else if (nextIndent > baseIndent) {
                            if (blockContent.length() > 0) {
                                blockContent.append("\n");
                            }
                            blockContent.append(nextTrimmed);
                            nextIdx++;
                        } else {
                            break;
                        }
                    }

                    String plainValue = blockContent.toString().trim();
                    String keyId = effectiveDirective.keyId();
                    EncryptAlgorithm algorithm = (effectiveDirective.algorithmCode() != null)
                            ? resolveAlgorithm(effectiveDirective.algorithmCode()) : defaultAlgorithm;

                    String password = keyRegistry.getKeyOrDefault(keyId);
                    if (password == null || password.trim().isEmpty()) {
                        throw new ConfigDecryptionException(String.format(
                                "就地加密失败：字段 '%s' 缺失解密密钥 [KeyId: '%s']", key, keyId != null ? keyId : "default"));
                    }

                    String ciphertext = ConfigEncryptor.encrypt(plainValue, password, algorithm);
                    String encToken = buildEncToken(keyId, algorithm, ciphertext);

                    String reconstructed = indent + key + separator + encToken + comment;
                    rewrittenLines.add(reconstructed);
                    encryptedCount++;
                    previousDirective = null;
                    lineIdx = nextIdx - 1;
                    continue;
                }

                if (shouldEncrypt) {
                    String plainValue = unquote(cleanValue);
                    String keyId = (effectiveDirective != null) ? effectiveDirective.keyId() : null;
                    EncryptAlgorithm algorithm = (effectiveDirective != null && effectiveDirective.algorithmCode() != null)
                            ? resolveAlgorithm(effectiveDirective.algorithmCode()) : defaultAlgorithm;

                    String password = keyRegistry.getKeyOrDefault(keyId);
                    if (password == null || password.trim().isEmpty()) {
                        throw new ConfigDecryptionException(String.format(
                                "就地加密失败：字段 '%s' 缺失解密密钥 [KeyId: '%s']", key, keyId != null ? keyId : "default"));
                    }

                    String ciphertext = ConfigEncryptor.encrypt(plainValue, password, algorithm);
                    String encToken = buildEncToken(keyId, algorithm, ciphertext);

                    // 保持前导缩进、键名、原始分隔符、行尾注释完全一致
                    String reconstructed = indent + key + separator + encToken + comment;
                    rewrittenLines.add(reconstructed);
                    encryptedCount++;
                    previousDirective = null;
                    continue;
                }
            }

            rewrittenLines.add(line);
            previousDirective = null;
        }

        // 4. 安全覆写目标文件（保真原始换行符）
        writeLinesPreservingEol(targetFile, rewrittenLines, prep.eol(), prep.endsWithEol());
        log.info("[ateng-boot-encrypt] 就地加密回写完成，共加密 {} 个敏感字段", encryptedCount);
        return encryptedCount;
    }

    /**
     * 对指定文件执行就地逆向解密回写（对称完备性），还原为真实明文，并生成 .bak 备份文件
     *
     * @param targetFile 目标 YAML/Properties 配置文件路径
     * @return 实际解密还原的属性键数量
     * @throws IOException 文件读写或备份异常
     */
    public int rewriteDecrypt(Path targetFile) throws IOException {
        PreparedRewrite prep = prepareRewrite(targetFile, "解密还原");
        List<String> lines = prep.lines();
        List<String> rewrittenLines = new ArrayList<>(lines.size());
        int decryptedCount = 0;

        for (String line : lines) {
            if (!parser.hasEncrypted(line)) {
                rewrittenLines.add(line);
                continue;
            }

            // 替换该行中包含的所有 ENC(...) 密文为明文
            String decryptedLine = parser.replaceTokens(line, token -> {
                String keyId = token.getKeyId();
                String password = keyRegistry.getKeyOrDefault(keyId);
                if (password == null || password.trim().isEmpty()) {
                    throw new ConfigDecryptionException(String.format("缺失解密主密钥 [KeyId: '%s']",
                            keyId != null ? keyId : "default"));
                }
                EncryptAlgorithm algo = token.getAlgorithm() != null ? token.getAlgorithm() : defaultAlgorithm;
                ConfigDecryptor decryptor = resolveDecryptor(algo);
                try {
                    return decryptor.decrypt(token.getCiphertext(), password);
                } catch (Exception e) {
                    throw new ConfigDecryptionException("解密失败: " + e.getMessage(), e);
                }
            });

            rewrittenLines.add(decryptedLine);
            decryptedCount++;
        }

        writeLinesPreservingEol(targetFile, rewrittenLines, prep.eol(), prep.endsWithEol());
        log.info("[ateng-boot-encrypt] 就地解密还原完成，共还原 {} 行密文字段", decryptedCount);
        return decryptedCount;
    }

    private void writeLinesPreservingEol(Path targetFile, List<String> lines, String eol, boolean endsWithEol) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            sb.append(lines.get(i));
            if (i < lines.size() - 1 || endsWithEol) {
                sb.append(eol);
            }
        }
        Files.writeString(targetFile, sb.toString(), StandardCharsets.UTF_8);
    }

    /**
     * 便捷重写加密入口
     */
    public static int encryptFile(File file, String password) throws IOException {
        return new InPlaceRewriter(password).rewriteEncrypt(file.toPath());
    }

    /**
     * 便捷重写解密入口
     */
    public static int decryptFile(File file, String password) throws IOException {
        return new InPlaceRewriter(password).rewriteDecrypt(file.toPath());
    }

    private Path createBackup(Path targetFile) throws IOException {
        String fileName = targetFile.getFileName().toString();
        Path backupFile = targetFile.resolveSibling(fileName + ".bak");
        Files.copy(targetFile, backupFile, StandardCopyOption.REPLACE_EXISTING);
        return backupFile;
    }

    private String buildEncToken(String keyId, EncryptAlgorithm algorithm, String ciphertext) {
        return CiphertextParser.formatToken(keyId, algorithm, defaultAlgorithm, ciphertext);
    }

    private static String unquote(String str) {
        if (str == null || str.length() < 2) {
            return str;
        }
        if ((str.startsWith("\"") && str.endsWith("\"")) || (str.startsWith("'") && str.endsWith("'"))) {
            return str.substring(1, str.length() - 1);
        }
        return str;
    }

    /**
     * 判断值是否包含 ${...} 环境变量占位符或 #{...} SpEL 表达式
     *
     * @param value 原始文本值
     * @return 若包含占位符返回 true
     */
    public static boolean isPlaceholder(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        String unquoted = unquote(value.trim());
        return PLACEHOLDER_PATTERN.matcher(unquoted).find();
    }

    /**
     * 判断值是否为 YAML 多行块标量符号（如 |、>、|-、|+ 等）
     *
     * @param value 原始文本值
     * @return 若为多行块标量指示符返回 true
     */
    public static boolean isYamlBlockScalarIndicator(String value) {
        if (value == null) {
            return false;
        }
        String trimmed = value.trim();
        return trimmed.equals("|") || trimmed.equals(">")
                || trimmed.equals("|-") || trimmed.equals("|+")
                || trimmed.equals(">-") || trimmed.equals(">+");
    }

    private EncryptAlgorithm resolveAlgorithm(String algoCode) {
        EncryptAlgorithm algo = EncryptAlgorithm.fromCode(algoCode);
        return algo != null ? algo : defaultAlgorithm;
    }

    private ConfigDecryptor resolveDecryptor(EncryptAlgorithm algorithm) {
        return switch (algorithm) {
            case AES_GCM -> new AesGcmConfigDecryptor();
            case AES_CBC -> new AesCbcConfigDecryptor();
            case SM4_CBC, SM4_ECB -> new Sm4ConfigDecryptor();
            case RSA -> new RsaConfigDecryptor();
            case SM2 -> new Sm2ConfigDecryptor();
            default -> throw new UnsupportedOperationException("不支持解密的算法: " + algorithm);
        };
    }

    private static int countLeadingSpaces(String str) {
        if (str == null) {
            return 0;
        }
        int count = 0;
        while (count < str.length() && (str.charAt(count) == ' ' || str.charAt(count) == '\t')) {
            count++;
        }
        return count;
    }

    private record Directive(String keyId, String algorithmCode) {
    }
}
