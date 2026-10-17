package io.github.atengk.boot.storage.util;

import io.github.atengk.boot.core.util.FileUtil;
import io.github.atengk.boot.storage.exception.StorageException;
import io.github.atengk.boot.storage.exception.StorageResultCode;

import java.net.URLConnection;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * 存储安全防卫与路径清洗通用工具
 * 提供严格的路径遍历防卫、全路径归一化净化、MIME 自动推导及高危文件拦截
 *
 * @author Ateng
 * @since 2026-10-17
 */
public final class StorageSecurityUtil {

    private static final String DEFAULT_CONTENT_TYPE = "application/octet-stream";

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
}
