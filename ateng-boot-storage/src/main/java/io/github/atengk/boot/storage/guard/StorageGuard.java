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
        // PE: MZ (4D 5A)
        if (len >= 2 && header[0] == 0x4D && header[1] == 0x5A) {
            return StorageFileType.PE_EXE;
        }
        // ELF: 7F 45 4C 46
        if (len >= 4 && header[0] == 0x7F && header[1] == 0x45 && header[2] == 0x4C && header[3] == 0x46) {
            return StorageFileType.ELF;
        }
        // Shebang: #! (23 21)
        if (len >= 2 && header[0] == 0x23 && header[1] == 0x21) {
            return StorageFileType.SHELL;
        }
        // Java Class: CA FE BA BE
        if (len >= 4 && (header[0] & 0xFF) == 0xCA && (header[1] & 0xFF) == 0xFE && (header[2] & 0xFF) == 0xBA && (header[3] & 0xFF) == 0xBE) {
            return StorageFileType.JAVA_CLASS;
        }

        // 2. 图片特征
        // PNG: 89 50 4E 47 0D 0A 1A 0A
        if (len >= 8 && (header[0] & 0xFF) == 0x89 && header[1] == 0x50 && header[2] == 0x4E && header[3] == 0x47
                && header[4] == 0x0D && header[5] == 0x0A && header[6] == 0x1A && header[7] == 0x0A) {
            return StorageFileType.PNG;
        }
        // JPEG: FF D8 FF
        if (len >= 3 && (header[0] & 0xFF) == 0xFF && (header[1] & 0xFF) == 0xD8 && (header[2] & 0xFF) == 0xFF) {
            return StorageFileType.JPEG;
        }
        // GIF: GIF8 (47 49 46 38)
        if (len >= 4 && header[0] == 0x47 && header[1] == 0x49 && header[2] == 0x46 && header[3] == 0x38) {
            return StorageFileType.GIF;
        }
        // BMP: BM (42 4D)
        if (len >= 2 && header[0] == 0x42 && header[1] == 0x4D) {
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
        // PDF: %PDF (25 50 44 46)
        if (len >= 4 && header[0] == 0x25 && header[1] == 0x50 && header[2] == 0x44 && header[3] == 0x46) {
            return StorageFileType.PDF;
        }
        // OLE2 Compound Document: D0 CF 11 E0 A1 B1 1A E1
        if (len >= 8 && (header[0] & 0xFF) == 0xD0 && (header[1] & 0xFF) == 0xCF && (header[2] & 0xFF) == 0x11
                && (header[3] & 0xFF) == 0xE0 && (header[4] & 0xFF) == 0xA1 && (header[5] & 0xFF) == 0xB1
                && (header[6] & 0xFF) == 0x1A && (header[7] & 0xFF) == 0xE1) {
            return StorageFileType.OLE2;
        }

        // 5. 压缩与打包格式
        // ZIP: PK.. (50 4B 03 04, 50 4B 05 06, 50 4B 07 08)
        if (len >= 4 && header[0] == 0x50 && header[1] == 0x4B
                && (header[2] == 0x03 || header[2] == 0x05 || header[2] == 0x07)
                && (header[3] == 0x04 || header[3] == 0x06 || header[3] == 0x08)) {
            return StorageFileType.ZIP;
        }
        // GZIP: 1F 8B
        if (len >= 2 && (header[0] & 0xFF) == 0x1F && (header[1] & 0xFF) == 0x8B) {
            return StorageFileType.GZIP;
        }
        // 7Z: 37 7A BC AF 27 1C
        if (len >= 6 && header[0] == 0x37 && header[1] == 0x7A && (header[2] & 0xFF) == 0xBC
                && (header[3] & 0xFF) == 0xAF && header[4] == 0x27 && header[5] == 0x1C) {
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
        // MP3: ID3 (49 44 33) or MPEG sync frames (FF FB / FF F3 / FF F2)
        if (len >= 3 && header[0] == 0x49 && header[1] == 0x44 && header[2] == 0x33) {
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
