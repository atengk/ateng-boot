package io.github.atengk.boot.storage.util;

import io.github.atengk.boot.core.util.FileUtil;
import io.github.atengk.boot.core.util.HmacUtil;
import io.github.atengk.boot.storage.exception.StorageException;
import io.github.atengk.boot.storage.exception.StorageResultCode;
import org.springframework.util.unit.DataSize;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLConnection;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;

/**
 * 存储安全防卫、路径清洗与签名通用工具
 * 提供严格的路径遍历防卫、全路径归一化净化、MIME 自动推导、高危文件拦截以及轻量 HMAC-SHA256 签名与校验
 *
 * @author Ateng
 * @since 2026-10-17
 */
public final class StorageSecurityUtil {

    /**
     * 默认单个分片允许的最大容量上限 (100MB)
     */
    public static final long DEFAULT_MAX_CHUNK_SIZE_BYTES = DataSize.ofMegabytes(100).toBytes();

    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private static final Map<String, String> MIME_MAP = new HashMap<>();

    private static final Set<String> DANGEROUS_EXTENSIONS = Set.of(
            "sh", "bat", "cmd", "exe", "dll", "vbs", "ps1", "scr", "com", "msi"
    );

    static {
        MIME_MAP.put("jpg", "image/jpeg");
        MIME_MAP.put("jpeg", "image/jpeg");
        MIME_MAP.put("png", "image/png");
        MIME_MAP.put("gif", "image/gif");
        MIME_MAP.put("webp", "image/webp");
        MIME_MAP.put("svg", "image/svg+xml");
        MIME_MAP.put("bmp", "image/bmp");
        MIME_MAP.put("ico", "image/x-icon");
        MIME_MAP.put("pdf", "application/pdf");
        MIME_MAP.put("json", "application/json");
        MIME_MAP.put("xml", "application/xml");
        MIME_MAP.put("txt", "text/plain");
        MIME_MAP.put("log", "text/plain");
        MIME_MAP.put("html", "text/html");
        MIME_MAP.put("htm", "text/html");
        MIME_MAP.put("css", "text/css");
        MIME_MAP.put("js", "application/javascript");
        MIME_MAP.put("zip", "application/zip");
        MIME_MAP.put("tar", "application/x-tar");
        MIME_MAP.put("gz", "application/gzip");
        MIME_MAP.put("mp3", "audio/mpeg");
        MIME_MAP.put("mp4", "video/mp4");
        MIME_MAP.put("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        MIME_MAP.put("xls", "application/vnd.ms-excel");
        MIME_MAP.put("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        MIME_MAP.put("doc", "application/msword");
        MIME_MAP.put("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation");
        MIME_MAP.put("ppt", "application/vnd.ms-powerpoint");
        MIME_MAP.put("csv", "text/csv");
    }

    private StorageSecurityUtil() {
    }

    /**
     * 规范化并清洗存储对象路径（防御路径穿越攻击 Path Traversal）
     * 1. 拦截 null、空字符 \0 与 Windows 盘符冒号；
     * 2. 统一将所有反斜杠 \ 转换为斜杠 /；
     * 3. 逐级解析路径片段，一旦发现越级相对路径 .. 立即快速失败阻断；
     * 4. 剥除前置与后置多余斜杠，返回纯净规范的相对路径。
     *
     * @param rawPath 原始输入路径
     * @return 经过深度清洗与归一化的安全相对路径（例如："uploads/2026/avatar.png"）
     * @throws StorageException 若路径为空、包含空字符、包含盘符或存在路径穿越逃逸风险
     */
    public static String normalizePath(String rawPath) {
        if (rawPath == null || rawPath.isBlank()) {
            throw new StorageException(StorageResultCode.INVALID_PATH, "存储路径不能为空");
        }
        if (rawPath.contains("\0")) {
            throw new StorageException(StorageResultCode.INVALID_PATH, "存储路径包含非法空字符");
        }

        // 统一斜杠并消除两端空白
        String path = rawPath.trim().replace('\\', '/');

        // 防御 Windows 盘符（如 C:/file）
        if (path.contains(":")) {
            throw new StorageException(StorageResultCode.INVALID_PATH, "存储路径不能包含驱动器盘符标识: " + rawPath);
        }

        String[] segments = path.split("/");
        Deque<String> stack = new ArrayDeque<>();

        for (String segment : segments) {
            String trimmed = segment.trim();
            if (trimmed.isEmpty() || ".".equals(trimmed)) {
                continue;
            }
            if ("..".equals(trimmed)) {
                // 严禁向上穿越逃逸
                throw new StorageException(StorageResultCode.INVALID_PATH, "存储路径包含路径遍历逃逸风险: " + rawPath);
            }
            stack.addLast(trimmed);
        }

        if (stack.isEmpty()) {
            throw new StorageException(StorageResultCode.INVALID_PATH, "规范化后的存储路径为空");
        }

        return String.join("/", stack);
    }

    /**
     * 格式化并拼接包含自定义域名或相对路径前缀的完整访问 URL
     *
     * @param domain 自定义域名或前缀（例如 "https://cdn.example.com" 或 "http://localhost:8080"）
     * @param cleanPath 已经过规范化的相对存储路径
     * @return 拼接后的规范 URL（例如 "https://cdn.example.com/images/avatar.png" 或 "/images/avatar.png"）
     */
    public static String formatDomainUrl(String domain, String cleanPath) {
        if (cleanPath == null || cleanPath.isBlank()) {
            throw new StorageException(StorageResultCode.INVALID_PATH, "存储路径不能为空");
        }
        String relativePath = cleanPath.startsWith("/") ? cleanPath.substring(1) : cleanPath;
        if (domain != null && !domain.isBlank()) {
            String cleanDomain = domain.endsWith("/") ? domain.substring(0, domain.length() - 1) : domain;
            return cleanDomain + "/" + relativePath;
        }
        return "/" + relativePath;
    }

    /**
     * 构建预签名只读下载 HMAC 签名摘要数据源载荷
     * 将存储相对路径、有效截止时间戳以及可选的响应头重写参数（下载文件名、Content-Type、Content-Disposition）
     * 规范化拼接入防篡改校验载荷中，全面防御外部参数篡改攻击
     *
     * @param cleanPath   规范化相对存储路径
     * @param expires     Unix 秒级过期时间戳
     * @param filename    自定义友好下载文件名（可选）
     * @param contentType 响应 Content-Type（可选）
     * @param disposition 响应 Content-Disposition（可选）
     * @return 规约签名源载荷字符串
     */
    public static String buildPresignedDownloadSignaturePayload(String cleanPath, long expires,
                                                               String filename, String contentType, String disposition) {
        return buildPresignedDownloadSignaturePayload(cleanPath, expires, filename, contentType, disposition, null);
    }

    /**
     * 构建预签名下载载荷字符串（支持绑定命名存储源 source 防止跨源重放篡改）
     *
     * @param cleanPath   标准化相对路径
     * @param expires     Unix 秒级过期时间戳
     * @param filename    自定义下载文件名（可选）
     * @param contentType 自定义响应 Content-Type（可选）
     * @param disposition 自定义响应 Content-Disposition（可选）
     * @param source      命名存储源标识（可选，空值时与单源历史签名格式完全兼容）
     * @return 待签名的载荷字符串
     */
    public static String buildPresignedDownloadSignaturePayload(String cleanPath, long expires,
                                                               String filename, String contentType,
                                                               String disposition, String source) {
        StringBuilder sb = new StringBuilder();
        sb.append(cleanPath).append(":").append(expires);
        if (filename != null && !filename.isBlank()) {
            sb.append(":fn=").append(filename.trim());
        }
        if (contentType != null && !contentType.isBlank()) {
            sb.append(":ct=").append(contentType.trim());
        }
        if (disposition != null && !disposition.isBlank()) {
            sb.append(":cd=").append(disposition.trim());
        }
        if (source != null && !source.isBlank()) {
            sb.append(":src=").append(source.trim());
        }
        return sb.toString();
    }

    /**
     * 根据文件名自动推导标准 MIME 内容类型 (Content-Type)
     *
     * @param filename 文件名或路径
     * @return 推导出的 MIME 类型；若无法识别则保底返回 application/octet-stream
     */
    public static String determineContentType(String filename) {
        if (filename == null || filename.isBlank()) {
            return DEFAULT_CONTENT_TYPE;
        }
        String ext = FileUtil.getExtName(filename);
        if (!ext.isEmpty()) {
            String mime = MIME_MAP.get(ext);
            if (mime != null) {
                return mime;
            }
        }
        String guessed = URLConnection.guessContentTypeFromName(filename);
        return (guessed != null && !guessed.isBlank()) ? guessed : DEFAULT_CONTENT_TYPE;
    }

    /**
     * 检查文件是否属于危险可执行脚本类型
     *
     * @param filename 文件名或路径
     * @return true 表示命中高危黑名单后缀，false 表示安全
     */
    public static boolean isDangerousExtension(String filename) {
        if (filename == null || filename.isBlank()) {
            return false;
        }
        String ext = FileUtil.getExtName(filename);
        return DANGEROUS_EXTENSIONS.contains(ext);
    }

    /**
     * 基于 HMAC-SHA256 算法计算待签名字符串的消息认证码（小写十六进制）
     *
     * @param data 待签名字符串
     * @param key  密钥字符串
     * @return 64 位小写十六进制签名摘要
     * @throws StorageException 若密钥或数据为空，或算法异常
     */
    public static String hmacSha256(String data, String key) {
        if (data == null || key == null || key.isBlank()) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "HMAC 签名数据或密钥不能为空");
        }
        try {
            return HmacUtil.hmacSha256Hex(data, key);
        } catch (Exception e) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "计算 HMAC-SHA256 签名异常", e);
        }
    }

    /**
     * 校验 HMAC-SHA256 签名是否合法（采用常量时间比对，防御时序侧信道攻击 Timing Attack）
     *
     * @param data      原始待签名字符串
     * @param key       密钥字符串
     * @param signature 待验证的签名十六进制字符串
     * @return true 签名一致且未被篡改，false 签名不匹配或入参非法
     */
    public static boolean verifyHmacSha256(String data, String key, String signature) {
        if (data == null || key == null || key.isBlank() || signature == null || signature.isBlank()) {
            return false;
        }
        try {
            String expected = hmacSha256(data, key);
            return HmacUtil.verifyHex(expected, signature);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 生成高强度 256 位安全随机密钥（64 位小写十六进制字符串）
     *
     * @return 随机安全密钥
     */
    public static String generateRandomSecretKey() {
        byte[] randomBytes = new byte[32];
        SECURE_RANDOM.nextBytes(randomBytes);
        return HexFormat.of().formatHex(randomBytes);
    }

    /**
     * 校验并清洗用于批量删除的前缀（防清库与误删熔断保护）
     * 1. 严格拦截 null、空白字符串；
     * 2. 严格拦截包含通配符 *、? 或空字符；
     * 3. 拦截根路径 /、仅包含点号 .、.. 等危险逃逸路径，防止清空整盘或清空整个 Bucket；
     * 4. 返回清洗归一化后的干净前缀。
     *
     * @param rawPrefix 原始输入前缀
     * @return 经过严格安全审查的干净前缀
     * @throws StorageException 若前缀不合规或触发熔断保护
     */
    public static String validatePrefixForDeletion(String rawPrefix) {
        if (rawPrefix == null || rawPrefix.isBlank()) {
            throw new StorageException(StorageResultCode.INVALID_PATH, "批量删除前缀不能为空");
        }
        if (rawPrefix.contains("\0")) {
            throw new StorageException(StorageResultCode.INVALID_PATH, "批量删除前缀包含非法空字符");
        }
        if (rawPrefix.contains("*") || rawPrefix.contains("?")) {
            throw new StorageException(StorageResultCode.INVALID_PATH, "批量删除前缀不能包含通配符: " + rawPrefix);
        }

        String path = rawPrefix.trim().replace('\\', '/');
        if (path.contains(":")) {
            throw new StorageException(StorageResultCode.INVALID_PATH, "批量删除前缀不能包含驱动器盘符标识: " + rawPrefix);
        }

        boolean endsWithSlash = path.endsWith("/");

        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        while (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        path = path.trim();

        if (path.isEmpty() || ".".equals(path) || "..".equals(path)) {
            throw new StorageException(StorageResultCode.INVALID_PATH, "禁止使用根路径作为批量删除前缀，触发防清库熔断保护: " + rawPrefix);
        }

        String[] segments = path.split("/");
        Deque<String> stack = new ArrayDeque<>();
        for (String segment : segments) {
            String trimmed = segment.trim();
            if (trimmed.isEmpty() || ".".equals(trimmed)) {
                continue;
            }
            if ("..".equals(trimmed)) {
                throw new StorageException(StorageResultCode.INVALID_PATH, "批量删除前缀包含路径遍历逃逸风险: " + rawPrefix);
            }
            stack.addLast(trimmed);
        }

        if (stack.isEmpty()) {
            throw new StorageException(StorageResultCode.INVALID_PATH, "规范化后的批量删除前缀为空，触发防清库熔断保护");
        }

        String normalized = String.join("/", stack);
        return endsWithSlash ? normalized + "/" : normalized;
    }

    /**
     * 构建符合 RFC 5987 与 RFC 6266 标准的 Content-Disposition 响应头值
     * 自动处理 ASCII 回退文件名与 UTF-8 扩展文件名（支持中文与特殊字符），防御 CRLF 响应拆分注入
     *
     * @param filename 下载文件名
     * @return 格式化的 Content-Disposition 字符串，例如：attachment; filename="report.pdf"; filename*=UTF-8''%E6%8A%A5%E8%A1%A8.pdf
     */
    public static String formatContentDisposition(String filename) {
        if (filename == null || filename.isBlank()) {
            return "attachment";
        }
        // 清洗 CRLF 防御 HTTP 响应拆分注入攻击
        String clean = filename.replace("\r", "").replace("\n", "").trim();
        String basename = FileUtil.cleanFilename(clean).replace("\"", "");
        if (basename.isBlank()) {
            return "attachment";
        }
        String encoded = java.net.URLEncoder.encode(basename, StandardCharsets.UTF_8).replace("+", "%20");

        // 构建 ASCII 回退文件名，非 ASCII 字符替换为下划线
        StringBuilder asciiFallback = new StringBuilder();
        for (char c : basename.toCharArray()) {
            if (c > 31 && c < 127 && c != '"' && c != '\\') {
                asciiFallback.append(c);
            } else {
                asciiFallback.append('_');
            }
        }
        String fallback = !asciiFallback.isEmpty() ? asciiFallback.toString() : "download";
        return "attachment; filename=\"" + fallback + "\"; filename*=UTF-8''" + encoded;
    }

    /**
     * 受上限约束的流式字节拷贝（防御未知长度分片流导致磁盘爆盘或 OOM）
     *
     * @param in       输入流
     * @param out      输出流
     * @param maxBytes 允许的最大字节上限（<= 0 时不限制）
     * @throws IOException      流读写异常
     * @throws StorageException 当实际读取字节数超出 maxBytes 时抛出 STORAGE_SIZE_EXCEEDED
     */
    public static void copyWithMaxBytes(InputStream in, OutputStream out, long maxBytes) throws IOException {
        if (in == null || out == null) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "流拷贝入参不能为空");
        }
        byte[] buffer = new byte[16384];
        long totalRead = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            totalRead += read;
            if (maxBytes > 0 && totalRead > maxBytes) {
                throw new StorageException(StorageResultCode.STORAGE_SIZE_EXCEEDED,
                        "分片数据流体积超出允许的最大上限: maxBytes=" + maxBytes);
            }
            out.write(buffer, 0, read);
        }
    }

    /**
     * 解析单个分片允许的最大字节上限（未配置或非正值时回退至默认 100MB）
     *
     * @param maxChunkSize 配置的分片上限
     * @return 生效的字节上限
     */
    public static long resolveMaxChunkSizeBytes(DataSize maxChunkSize) {
        return (maxChunkSize != null && maxChunkSize.toBytes() > 0)
                ? maxChunkSize.toBytes()
                : DEFAULT_MAX_CHUNK_SIZE_BYTES;
    }

    /**
     * 前置校验已知分片大小是否超出允许上限
     *
     * @param size              声明的分片字节大小（未知长度流传 <= 0 时跳过前置校验）
     * @param maxChunkSizeBytes 最大允许字节上限
     * @throws StorageException 超出上限时抛出 STORAGE_SIZE_EXCEEDED
     */
    public static void validateChunkSize(long size, long maxChunkSizeBytes) {
        if (size > 0 && size > maxChunkSizeBytes) {
            throw new StorageException(StorageResultCode.STORAGE_SIZE_EXCEEDED,
                    "分片声明大小超出允许的最大上限: size=" + size + ", maxBytes=" + maxChunkSizeBytes);
        }
    }
}
