package io.github.atengk.boot.storage.guard;

import io.github.atengk.boot.core.util.FileUtil;
import io.github.atengk.boot.storage.exception.StorageException;
import io.github.atengk.boot.storage.exception.StorageResultCode;
import io.github.atengk.boot.storage.properties.StorageGuardProperties;
import io.github.atengk.boot.storage.util.StorageSecurityUtil;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;

/**
 * 存储安全防卫与文件魔数嗅探器
 * 提供真实二进制特征嗅探、扩展名伪装检测、上传扩展名白名单拦截以及零流截断损耗保障
 *
 * @author Ateng
 * @since 2026-10-18
 */
public class StorageGuard {

    private static final int MAX_MAGIC_BUFFER_SIZE = 512;

    private static final Set<String> STRICT_CHECK_EXTENSIONS = Set.of(
            "png", "jpg", "jpeg", "gif", "webp", "bmp", "pdf",
            "zip", "jar", "docx", "xlsx", "pptx", "gz", "gzip", "7z", "tar",
            "mp4", "m4v", "mp3", "wav", "avi", "doc", "xls", "ppt"
    );

    // 常用标准与高危魔数特征签名
    private static final byte[] MAGIC_PE = {(byte) 0x4D, (byte) 0x5A};
    private static final byte[] MAGIC_ELF = {(byte) 0x7F, (byte) 0x45, (byte) 0x4C, (byte) 0x46};
    private static final byte[] MAGIC_SHEBANG = {(byte) 0x23, (byte) 0x21};
    private static final byte[] MAGIC_JAVA_CLASS = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE};
    private static final byte[] MAGIC_PNG = {(byte) 0x89, (byte) 0x50, (byte) 0x4E, (byte) 0x47, (byte) 0x0D, (byte) 0x0A, (byte) 0x1A, (byte) 0x0A};
    private static final byte[] MAGIC_JPEG = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] MAGIC_GIF = {(byte) 0x47, (byte) 0x49, (byte) 0x46, (byte) 0x38};
    private static final byte[] MAGIC_BMP = {(byte) 0x42, (byte) 0x4D};
    private static final byte[] MAGIC_PDF = {(byte) 0x25, (byte) 0x50, (byte) 0x44, (byte) 0x46};
    private static final byte[] MAGIC_OLE2 = {(byte) 0xD0, (byte) 0xCF, (byte) 0x11, (byte) 0xE0, (byte) 0xA1, (byte) 0xB1, (byte) 0x1A, (byte) 0xE1};
    private static final byte[] MAGIC_GZIP = {(byte) 0x1F, (byte) 0x8B};
    private static final byte[] MAGIC_SEVEN_Z = {(byte) 0x37, (byte) 0x7A, (byte) 0xBC, (byte) 0xAF, (byte) 0x27, (byte) 0x1C};
    private static final byte[] MAGIC_MP3_ID3 = {(byte) 0x49, (byte) 0x44, (byte) 0x33};

    private final StorageGuardProperties properties;

    public StorageGuard() {
        this(new StorageGuardProperties());
    }

    public StorageGuard(StorageGuardProperties properties) {
        this.properties = properties != null ? properties : new StorageGuardProperties();
    }

    /**
     * 当前安全防卫是否已激活
     */
    public boolean isEnabled() {
        return properties.isEnabled();
    }

    /**
     * 获取安全防卫配置属性
     */
    public StorageGuardProperties getProperties() {
        return properties;
    }

    /**
     * 基于输入字节数组进行真实文件特征嗅探
     *
     * @param header 头部字节数组
     * @return 识别出的 StorageFileType，若未匹配已知特征则返回 null
     */
    public static StorageFileType sniff(byte[] header) {
        if (header == null || header.length == 0) {
            return null;
        }

        int len = header.length;

        // 1. 高危可执行与脚本特征（优先嗅探拦截）
        if (startsWith(header, MAGIC_PE)) {
            return StorageFileType.PE_EXE;
        }
        if (startsWith(header, MAGIC_ELF)) {
            return StorageFileType.ELF;
        }
        if (startsWith(header, MAGIC_SHEBANG)) {
            return StorageFileType.SHELL;
        }
        if (startsWith(header, MAGIC_JAVA_CLASS)) {
            return StorageFileType.JAVA_CLASS;
        }

        // 2. 图片特征
        if (startsWith(header, MAGIC_PNG)) {
            return StorageFileType.PNG;
        }
        if (startsWith(header, MAGIC_JPEG)) {
            return StorageFileType.JPEG;
        }
        if (startsWith(header, MAGIC_GIF)) {
            return StorageFileType.GIF;
        }
        if (startsWith(header, MAGIC_BMP)) {
            return StorageFileType.BMP;
        }

        // 3. RIFF 容器族（WEBP, WAV, AVI）
        // RIFF header: 52 49 46 46
        if (len >= 12 && header[0] == 0x52 && header[1] == 0x49 && header[2] == 0x46 && header[3] == 0x46) {
            // bytes 8..11
            if (header[8] == 0x57 && header[9] == 0x45 && header[10] == 0x42 && header[11] == 0x50) {
                return StorageFileType.WEBP; // WEBP
            }
            if (header[8] == 0x57 && header[9] == 0x41 && header[10] == 0x56 && header[11] == 0x45) {
                return StorageFileType.WAV; // WAVE
            }
            if (header[8] == 0x41 && header[9] == 0x56 && header[10] == 0x49 && header[11] == 0x20) {
                return StorageFileType.AVI; // AVI<space>
            }
        }

        // 4. 文档与格式
        if (startsWith(header, MAGIC_PDF)) {
            return StorageFileType.PDF;
        }
        if (startsWith(header, MAGIC_OLE2)) {
            return StorageFileType.OLE2;
        }

        // 5. 压缩与打包格式
        // ZIP: PK.. (50 4B 03 04, 50 4B 05 06, 50 4B 07 08)
        if (len >= 4 && header[0] == 0x50 && header[1] == 0x4B
                && (header[2] == 0x03 || header[2] == 0x05 || header[2] == 0x07)
                && (header[3] == 0x04 || header[3] == 0x06 || header[3] == 0x08)) {
            return StorageFileType.ZIP;
        }
        if (startsWith(header, MAGIC_GZIP)) {
            return StorageFileType.GZIP;
        }
        if (startsWith(header, MAGIC_SEVEN_Z)) {
            return StorageFileType.SEVEN_Z;
        }
        // TAR: bytes 257..261 ustar
        if (len >= 262 && header[257] == 0x75 && header[258] == 0x73 && header[259] == 0x74 && header[260] == 0x61 && header[261] == 0x72) {
            return StorageFileType.TAR;
        }

        // 6. 音视频多媒体
        // MP4: bytes 4..7 ftyp (66 74 79 70)
        if (len >= 8 && header[4] == 0x66 && header[5] == 0x74 && header[6] == 0x79 && header[7] == 0x70) {
            return StorageFileType.MP4;
        }
        if (startsWith(header, MAGIC_MP3_ID3)) {
            return StorageFileType.MP3;
        }
        if (len >= 2 && (header[0] & 0xFF) == 0xFF && ((header[1] & 0xFE) == 0xFA || (header[1] & 0xFE) == 0xF2)) {
            return StorageFileType.MP3;
        }

        // 7. XML / SVG 文本嗅探
        String textSnippet = new String(header, 0, Math.min(len, 256), StandardCharsets.UTF_8).trim().toLowerCase(Locale.ROOT);
        if (textSnippet.startsWith("<svg") || textSnippet.contains("<svg")) {
            return StorageFileType.SVG;
        }
        if (textSnippet.startsWith("<?xml")) {
            return StorageFileType.XML;
        }

        return null;
    }

    private static boolean startsWith(byte[] data, byte[] magic) {
        if (data == null || magic == null || data.length < magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if (data[i] != magic[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * 基于输入流嗅探文件类型（保障零流截断损耗，嗅探完成后将流复位）
     *
     * @param in 输入流
     * @return 识别出的 StorageFileType，若未识别则返回 null
     * @throws IOException 流读取异常
     */
    public static StorageFileType sniff(InputStream in) throws IOException {
        if (in == null) {
            return null;
        }
        if (!in.markSupported()) {
            throw new IllegalArgumentException("InputStream must support mark/reset for zero-truncation sniffing");
        }
        in.mark(MAX_MAGIC_BUFFER_SIZE);
        try {
            byte[] header = in.readNBytes(MAX_MAGIC_BUFFER_SIZE);
            return sniff(header);
        } finally {
            in.reset();
        }
    }

    /**
     * 对上传文件实施安全防卫与魔数校验（零损耗流包装）
     *
     * @param path                上传目标路径
     * @param in                  上传数据输入流
     * @param declaredContentType 声明的 Content-Type（可选）
     * @return 校验后的结果，包含包装好的流及嗅探类型
     */
    public GuardResult inspect(String path, InputStream in, String declaredContentType) {
        if (!properties.isEnabled()) {
            return new GuardResult(in, null, declaredContentType);
        }

        if (path == null || path.isBlank()) {
            throw new StorageException(StorageResultCode.INVALID_PATH, "存储路径不能为空");
        }

        String ext = FileUtil.getExtName(path).toLowerCase(Locale.ROOT);

        // 1. 白名单拦截校验（若配置了 allowedExtensions）
        Set<String> allowed = properties.getAllowedExtensions();
        if (allowed != null && !allowed.isEmpty()) {
            boolean matched = allowed.stream()
                    .map(e -> e.toLowerCase(Locale.ROOT).trim())
                    .anyMatch(e -> e.equals(ext));
            if (!matched) {
                throw new StorageException(StorageResultCode.STORAGE_FILE_NOT_ALLOWED,
                        "文件类型 [" + ext + "] 不被允许上传，允许的类型为: " + allowed);
            }
        }

        // 2. 危险脚本与可执行扩展名拦截
        if (StorageSecurityUtil.isDangerousExtension(path)) {
            throw new StorageException(StorageResultCode.STORAGE_FILE_NOT_ALLOWED,
                    "禁止上传高危可执行文件或脚本: " + ext);
        }

        // 3. 零损耗流读取特征魔数
        InputStream effectiveStream = in;
        if (effectiveStream != null && !effectiveStream.markSupported()) {
            effectiveStream = new BufferedInputStream(effectiveStream, 8192);
        }

        byte[] header = new byte[0];
        if (effectiveStream != null) {
            effectiveStream.mark(MAX_MAGIC_BUFFER_SIZE);
            try {
                header = effectiveStream.readNBytes(MAX_MAGIC_BUFFER_SIZE);
                effectiveStream.reset();
            } catch (IOException e) {
                throw new StorageException(StorageResultCode.UPLOAD_FAILED, "读取文件头部特征魔数失败: " + path, e);
            }
        }

        StorageFileType detected = sniff(header);

        // 4. 伪装攻击拦截（高危二进制伪装为普通图片/文档）
        if (detected != null && detected.isDangerous()) {
            if (!detected.getExtensions().contains(ext)) {
                throw new StorageException(StorageResultCode.STORAGE_FILE_TYPE_MISMATCH,
                        "检测到文件伪装攻击：文件声明扩展名为 [" + ext + "]，但实际内容特征为高危文件 [" + detected.getName() + "]");
            }
            throw new StorageException(StorageResultCode.STORAGE_FILE_NOT_ALLOWED,
                    "禁止上传高危二进制内容: " + detected.getName());
        }

        // 5. 严格魔数强校验
        if (properties.isStrictMagicCheck()) {
            if (detected != null) {
                // 若检测到了已知类型，但声明的扩展名属于已知二进制扩展名且不匹配
                if (STRICT_CHECK_EXTENSIONS.contains(ext) && !detected.getExtensions().contains(ext)) {
                    throw new StorageException(StorageResultCode.STORAGE_FILE_TYPE_MISMATCH,
                            "文件真实类型与扩展名不匹配：声明扩展名为 [" + ext + "]，但实际内容特征为 [" + detected.getName() + "]");
                }
            } else {
                // 若未识别出魔数，但声明的扩展名属于必须具备魔数的强校验扩展名
                if (STRICT_CHECK_EXTENSIONS.contains(ext)) {
                    throw new StorageException(StorageResultCode.STORAGE_FILE_TYPE_MISMATCH,
                            "文件魔数强校验失败：未检测到扩展名 [" + ext + "] 所需的标准头部特征魔数");
                }
            }
        }

        String effectiveMime = detected != null ? detected.getMimeType() : declaredContentType;
        return new GuardResult(effectiveStream, detected, effectiveMime);
    }

    /**
     * 检验结果承载对象
     */
    @Getter
    @RequiredArgsConstructor
    public static class GuardResult {
        private final InputStream guardedStream;
        private final StorageFileType detectedType;
        private final String detectedMimeType;
    }
}
