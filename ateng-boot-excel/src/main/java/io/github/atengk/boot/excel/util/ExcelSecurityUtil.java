package io.github.atengk.boot.excel.util;

import io.github.atengk.boot.core.code.CommonResultCode;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.core.util.FileUtil;
import io.github.atengk.boot.excel.exception.ExcelValidationException;
import org.apache.poi.poifs.crypt.Decryptor;
import org.apache.poi.poifs.crypt.EncryptionInfo;
import org.apache.poi.poifs.crypt.EncryptionMode;
import org.apache.poi.poifs.crypt.Encryptor;
import org.apache.poi.poifs.filesystem.FileMagic;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.Assert;

import java.io.BufferedInputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;
import java.util.function.Consumer;

/**
 * Excel 安全、公式注入防御与整工作簿 AES-256 加解密工具类
 * <p>
 * 1. 依据 OWASP 与 CWE-1236 规范，识别与对称转译/反转译潜在的 CSV/Excel 公式注入前缀；
 * 2. 依托 Apache POI 原生 Agile Encryption 实现整工作簿 AES-256 强加密与透明流式解密；
 * 3. 加解密全链路依托磁盘受管临时文件缓冲，并在 finally 块确定性执行物理销毁，保障零堆内存 OOM 与零文件泄漏。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-20
 */
public final class ExcelSecurityUtil {

    private static final Logger log = LoggerFactory.getLogger(ExcelSecurityUtil.class);

    private static final char SINGLE_QUOTE = '\'';

    private ExcelSecurityUtil() {
        // 工具类禁止实例化
    }

    /**
     * 受管明文临时文件缓冲会话（用于长生命周期写入器在完成写出后再统一执行 AES-256 整工作簿加密，并在结束时确定性销毁临时文件）
     */
    public static final class TempPlainBuffer implements Closeable {

        private final OutputStream finalTargetOut;
        private final String password;
        private final Path tempPlainFile;
        private final OutputStream plainOut;
        private boolean streamClosed = false;

        private TempPlainBuffer(OutputStream finalTargetOut, String password, Path tempPlainFile, OutputStream plainOut) {
            this.finalTargetOut = finalTargetOut;
            this.password = password;
            this.tempPlainFile = tempPlainFile;
            this.plainOut = plainOut;
        }

        /**
         * 获取底层写手应实际写入的目标输出流（明文临时文件流）
         */
        public OutputStream targetOutputStream() {
            return plainOut;
        }

        /**
         * 闭合明文流并将缓冲文件使用 AES-256 算法加密写出至最终目标输出流
         */
        public void encryptToTarget() {
            closePlainStream();
            if (tempPlainFile != null && password != null && !password.isBlank()) {
                encryptWorkbook(tempPlainFile, finalTargetOut, password);
            }
        }

        private void closePlainStream() {
            if (!streamClosed && plainOut != null) {
                streamClosed = true;
                try {
                    plainOut.close();
                } catch (IOException ex) {
                    throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                            "关闭 Excel 明文加密缓冲流失败: " + ex.getMessage(), ex);
                }
            }
        }

        @Override
        public void close() {
            if (!streamClosed && plainOut != null) {
                streamClosed = true;
                try {
                    plainOut.close();
                } catch (Exception ex) {
                    // 清理分支仅记录调试日志，避免覆盖主流程业务异常
                    log.debug("清理关闭 Excel 明文加密缓冲流时发生非致命异常: {}", ex.getMessage());
                }
            }
            if (tempPlainFile != null) {
                try {
                    Files.deleteIfExists(tempPlainFile);
                } catch (Exception ex) {
                    log.debug("清理删除 Excel 加密临时文件失败 [{}]: {}", tempPlainFile, ex.getMessage());
                }
            }
        }
    }

    /**
     * 创建受管明文临时文件缓冲会话（仅在需要 AES-256 加密时创建临时文件）
     *
     * @param finalTargetOut 加密后的最终输出流（不可为空）
     * @param password       访问密码（不可为空白）
     * @param tempPrefix     临时文件名前缀
     * @return 受管明文临时文件缓冲会话（必须配合 try-finally 调用 {@link TempPlainBuffer#close()}）
     */
    public static TempPlainBuffer openTempPlainBuffer(OutputStream finalTargetOut, String password, String tempPrefix) {
        Assert.notNull(finalTargetOut, "finalTargetOut 目标输出流不可为空");
        Assert.hasText(password, "工作簿加密密码不可为空");
        String prefix = (tempPrefix != null && !tempPrefix.isBlank()) ? tempPrefix : "ateng-excel-plain-";
        Path tempFile = null;
        try {
            tempFile = ExcelUtil.createTempFile(prefix, ".tmp");
            OutputStream fos = Files.newOutputStream(tempFile);
            return new TempPlainBuffer(finalTargetOut, password, tempFile, fos);
        } catch (IOException ex) {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (Exception cleanupEx) {
                    log.debug("初始化失败后清理临时文件出错 [{}]: {}", tempFile, cleanupEx.getMessage());
                }
            }
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                    "创建 Excel 加密缓冲临时文件失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 统一执行“无密码直写或受管临时文件缓冲后 AES-256 加密”的一站式包装逻辑
     *
     * @param targetOut  最终输出流（不可为空）
     * @param password   可选加密密码（为空或 null 时直接明文写至 targetOut）
     * @param tempPrefix 临时文件前缀
     * @param writer     实际写手逻辑（接收应写入的目标输出流）
     */
    public static void writeWithOptionalEncryption(OutputStream targetOut,
                                                   String password,
                                                   String tempPrefix,
                                                   Consumer<OutputStream> writer) {
        Assert.notNull(targetOut, "targetOut 目标输出流不可为空");
        Assert.notNull(writer, "writer 写出回调不可为空");
        if (password == null || password.isBlank()) {
            writer.accept(targetOut);
            return;
        }
        try (TempPlainBuffer buffer = openTempPlainBuffer(targetOut, password, tempPrefix)) {
            writer.accept(buffer.targetOutputStream());
            buffer.encryptToTarget();
        }
    }

    /**
     * 检查字符串是否以潜在的公式注入特殊字符开头
     * <p>
     * 覆盖范围：{@code =}, {@code +}, {@code -}, {@code @}, {@code \t} (0x09), {@code \r} (0x0D)。
     * </p>
     *
     * @param value 待检查字符串
     * @return true 表示首字符为公式或 DDE 触发字符
     */
    public static boolean isFormulaLeading(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        char first = value.charAt(0);
        return first == '=' || first == '+' || first == '-' || first == '@' || first == '\t' || first == '\r';
    }

    /**
     * 检查字符串是否已具备单引号保护前缀
     *
     * @param value 待检查字符串
     * @return true 表示以单引号开头且后接潜在公式字符
     */
    public static boolean isAlreadyEscaped(String value) {
        if (value == null || value.length() < 2) {
            return false;
        }
        return value.charAt(0) == SINGLE_QUOTE && isFormulaLeading(value.substring(1));
    }

    /**
     * 对潜在公式注入字符串执行安全转译（前置单引号）
     *
     * @param value 原始字符串
     * @return 转译后的安全文本（非公式字符或已转译文本原样返回）
     */
    public static String escapeFormulaLeading(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        if (isFormulaLeading(value) && !isAlreadyEscaped(value)) {
            return SINGLE_QUOTE + value;
        }
        return value;
    }

    /**
     * 对转译保护的字符串执行反向还原（脱去保护单引号）
     *
     * @param value 单元格读取文本
     * @return 还原后的真实明文字符串
     */
    public static String unescapeFormulaLeading(String value) {
        if (isAlreadyEscaped(value)) {
            return value.substring(1);
        }
        return value;
    }

    // ==================== 整工作簿 AES-256 原生加解密 ====================

    /**
     * 判断输入流是否为加密的 Excel 工作簿（OLE2 Compound Document 格式）
     * <p>
     * 注意：入参流必须支持 {@code mark()/reset()}（例如 {@link BufferedInputStream} 或 {@link java.io.ByteArrayInputStream}），
     * 以防止探测文件魔数时消耗流数据导致后续读取损坏。若传入不支持 mark 的流，将抛出 {@link IllegalArgumentException}。
     * </p>
     *
     * @param in 支持 mark/reset 的输入流（为 null 时直接返回 false）
     * @return true 表示为已加密的工作簿
     */
    public static boolean isEncryptedWorkbook(InputStream in) {
        if (in == null) {
            return false;
        }
        Assert.isTrue(in.markSupported(), "InputStream 必须支持 mark/reset (可通过 new BufferedInputStream(in) 包装)，以防止探测魔数消耗流数据损坏原始流");
        try {
            return FileMagic.valueOf(in) == FileMagic.OLE2;
        } catch (IOException ex) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                    "嗅探 Excel 文件格式失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 使用 POI 原生 Agile Encryption (AES-256 + SHA-512) 对明文 Excel 流进行整工作簿强加密
     * <p>
     * 采用受管磁盘临时文件缓冲明文数据，在 finally 块中绝对执行物理销毁，杜绝堆内存 OOM 与临时文件泄露。
     * </p>
     *
     * @param in       明文 Excel 输入流（不可为空）
     * @param out      加密后的目标输出流（不可为空）
     * @param password 访问密码（不可为空）
     */
    public static void encryptWorkbook(InputStream in, OutputStream out, String password) {
        Assert.notNull(in, "InputStream 明文输入流不可为空");
        Assert.notNull(out, "OutputStream 目标输出流不可为空");
        Assert.hasText(password, "工作簿加密密码不可为空");

        Path tempPlainFile = null;
        try {
            tempPlainFile = ExcelUtil.createTempFile("ateng-excel-enc-plain-", ".tmp");
            try (OutputStream fos = Files.newOutputStream(tempPlainFile)) {
                in.transferTo(fos);
            }
            encryptWorkbook(tempPlainFile, out, password);
        } catch (BusinessException be) {
            throw be;
        } catch (Exception ex) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                    "Excel 工作簿 AES-256 加密失败: " + ex.getMessage(), ex);
        } finally {
            if (tempPlainFile != null) {
                try {
                    Files.deleteIfExists(tempPlainFile);
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * 使用 POI 原生 Agile Encryption (AES-256 + SHA-512) 对指定明文文件进行整工作簿强加密
     *
     * @param plainFile 明文 Excel 文件路径（不可为空且必须存在）
     * @param out       加密后的目标输出流（不可为空）
     * @param password  访问密码（不可为空）
     */
    public static void encryptWorkbook(Path plainFile, OutputStream out, String password) {
        Assert.notNull(plainFile, "plainFile 明文文件路径不可为空");
        Assert.isTrue(Files.isRegularFile(plainFile), "plainFile 必须为合法文件");
        Assert.notNull(out, "OutputStream 目标输出流不可为空");
        Assert.hasText(password, "工作簿加密密码不可为空");

        try (POIFSFileSystem fs = new POIFSFileSystem()) {
            EncryptionInfo info = new EncryptionInfo(EncryptionMode.agile);
            Encryptor encryptor = info.getEncryptor();
            encryptor.confirmPassword(password);
            try (InputStream fis = Files.newInputStream(plainFile);
                 OutputStream encOut = encryptor.getDataStream(fs)) {
                fis.transferTo(encOut);
            }
            fs.writeFilesystem(out);
        } catch (Exception ex) {
            throw new BusinessException(CommonResultCode.INTERNAL_SERVER_ERROR.getCode(),
                    "Excel 工作簿 AES-256 加密失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 解密受密码保护的 Excel 工作簿并输出明文流
     *
     * @param in       加密 Excel 输入流（不可为空）
     * @param out      明文输出流（不可为空）
     * @param password 工作簿解密密码（不可为空）
     */
    public static void decryptWorkbook(InputStream in, OutputStream out, String password) {
        Assert.notNull(in, "InputStream 输入流不可为空");
        Assert.notNull(out, "OutputStream 输出流不可为空");
        if (password == null || password.isBlank()) {
            throw new ExcelValidationException("Excel 工作簿已加密，必须提供访问密码以完成解密");
        }

        try (POIFSFileSystem fs = new POIFSFileSystem(in)) {
            if (!fs.getRoot().hasEntry(EncryptionInfo.ENCRYPTION_INFO_ENTRY)) {
                throw new ExcelValidationException("非加密工作簿格式，无法执行解密");
            }
            EncryptionInfo info = new EncryptionInfo(fs);
            Decryptor decryptor = Decryptor.getInstance(info);
            if (!decryptor.verifyPassword(password)) {
                throw new ExcelValidationException("Excel 工作簿访问密码错误，解密失败",
                        new SecurityException("Workbook password verification failed"));
            }
            try (InputStream decryptedStream = decryptor.getDataStream(fs)) {
                decryptedStream.transferTo(out);
            }
        } catch (ExcelValidationException eve) {
            throw eve;
        } catch (GeneralSecurityException gse) {
            throw new ExcelValidationException("Excel 工作簿访问密码错误，解密失败: " + gse.getMessage(), gse);
        } catch (Exception ex) {
            throw new ExcelValidationException("Excel 工作簿解密失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 将加密的 Excel 输入流解密并写入受管临时文件
     *
     * @param in       加密的 Excel 输入流
     * @param password 工作簿解密密码
     * @return 存储解密明文的临时文件路径
     */
    public static Path decryptWorkbookToTempFile(InputStream in, String password) {
        Path tempFile = null;
        try {
            tempFile = ExcelUtil.createTempFile("ateng-excel-dec-", ".tmp");
            try (OutputStream fos = Files.newOutputStream(tempFile)) {
                decryptWorkbook(in, fos, password);
                return tempFile;
            } catch (Exception ex) {
                if (tempFile != null) {
                    Files.deleteIfExists(tempFile);
                }
                throw ex;
            }
        } catch (ExcelValidationException eve) {
            throw eve;
        } catch (Exception ex) {
            throw new ExcelValidationException("创建解密临时文件失败: " + ex.getMessage(), ex);
        }
    }

    /**
     * 透明嗅探并解密 Excel 输入流（若未加密则原样返回）
     * <p>
     * 当检测到输入流为加密工作簿时，自动使用提供的密码解密至受管临时文件，并将临时文件的输入流与物理删除钩子
     * 注册至清理队列，保障流关闭时自动彻底销毁磁盘临时文件。
     * </p>
     *
     * @param in               原始输入流
     * @param password         解密密码（可为 null）
     * @param cleanupRegistrar 受管清理资源注册回调
     * @return 可供后续直接解析的明文输入流
     */
    public static InputStream decryptIfEncrypted(InputStream in, String password, Consumer<AutoCloseable> cleanupRegistrar) {
        Assert.notNull(in, "InputStream 输入流不可为空");
        Assert.notNull(cleanupRegistrar, "cleanupRegistrar 受管清理资源注册回调不可为空");
        InputStream markable = in.markSupported() ? in : new BufferedInputStream(in);
        if (!isEncryptedWorkbook(markable)) {
            return markable;
        }
        if (password == null || password.isBlank()) {
            throw new ExcelValidationException("Excel 工作簿已加密，必须提供访问密码以完成解密");
        }
        Path tempFile = decryptWorkbookToTempFile(markable, password);
        try {
            InputStream decIs = Files.newInputStream(tempFile);
            cleanupRegistrar.accept(() -> {
                try {
                    decIs.close();
                } finally {
                    Files.deleteIfExists(tempFile);
                }
            });
            return decIs;
        } catch (IOException ex) {
            try {
                Files.deleteIfExists(tempFile);
            } catch (Exception ignored) {
            }
            throw new ExcelValidationException("打开解密临时文件失败: " + ex.getMessage(), ex);
        }
    }

    // ==================== 受管路径防穿越统一校验 ====================

    /**
     * 判断原始路径字符串中是否显式包含父级目录穿越片段（{@code ..}）
     *
     * @param rawPath 原始路径字符串
     * @return true 表示存在 {@code ..} 路径片段
     */
    public static boolean hasTraversalSegment(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return false;
        }
        String normalizedSep = rawPath.replace('\\', '/');
        for (String segment : normalizedSep.split("/+")) {
            if ("..".equals(segment.trim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 将输入路径清洗归一化并校验其严格限定在受管基准目录之内（防御 Path Traversal）
     * <p>
     * 统一拦截空字符 {@code \0}、任何 {@code ..} 路径段、POSIX 前导 {@code /} 越界路径以及跨盘符/目录外绝对路径，
     * 校验失败时立即抛出 {@link ExcelValidationException}。
     * </p>
     *
     * @param baseDir 基准根目录
     * @param rawPath 待解析的相对或绝对路径
     * @return 归一化且确认落在 {@code baseDir} 之内的安全路径
     * @throws ExcelValidationException 路径为空、包含穿越片段或超出基准目录边界时抛出
     */
    public static Path resolveContainedPath(Path baseDir, String rawPath) {
        if (baseDir == null) {
            throw new ExcelValidationException("基准存储目录不能为空");
        }
        if (rawPath == null || rawPath.isBlank()) {
            throw new ExcelValidationException("文件路径不能为空");
        }
        if (rawPath.contains("\0") || hasTraversalSegment(rawPath)) {
            throw new ExcelValidationException("检测到高危路径穿越参数: " + rawPath);
        }

        Path normalizedBase = baseDir.toAbsolutePath().normalize();
        String cleaned;
        try {
            cleaned = FileUtil.cleanPath(rawPath);
        } catch (IllegalArgumentException ex) {
            throw new ExcelValidationException("非法路径参数: " + rawPath, ex);
        }
        if (cleaned.isEmpty()) {
            throw new ExcelValidationException("文件路径不能为空");
        }

        // 统一拦截 POSIX 前导 '/' 与原生绝对路径越界，避免 Windows 下将 '/etc/passwd' 降级为相对子路径
        if (cleaned.startsWith("/") || Paths.get(cleaned).isAbsolute()) {
            Path resolved = Paths.get(cleaned).toAbsolutePath().normalize();
            if (!resolved.startsWith(normalizedBase)) {
                throw new ExcelValidationException("检测到路径越界访问，超出允许根目录: " + rawPath);
            }
            return resolved;
        }

        try {
            Path resolved = FileUtil.resolveSafePath(normalizedBase, cleaned);
            if (!resolved.normalize().startsWith(normalizedBase)) {
                throw new ExcelValidationException("检测到路径逃逸风险: " + rawPath);
            }
            return resolved;
        } catch (IllegalArgumentException ex) {
            throw new ExcelValidationException("检测到路径逃逸风险: " + rawPath, ex);
        }
    }
}

