package io.github.atengk.boot.core.util;

/**
 * 文件名与文件属性安全通用工具
 * 提供针对路径遍历攻击（Path Traversal）的纯净文件名清洗、扩展名提取与人类可读文件容量格式化
 *
 * @author Ateng
 * @since 2026-10-17
 */
public final class FileUtil {

    private static final String[] SIZE_UNITS = {"B", "KB", "MB", "GB", "TB", "PB"};

    private FileUtil() {
    }

    /**
     * 安全清洗文件名（防御路径穿越攻击 Path Traversal）
     * 1. 过滤空字符 \0；
     * 2. 剥离所有前置目录路径（/ 或 \）；
     * 3. 递归彻底清除相对路径符号（如 ../ 或 ..\）；
     *
     * @param filename 原始文件名（可能包含恶意相对路径）
     * @return 经过净化后的纯安全文件名；若清洗后为空则返回空字符串
     */
    public static String cleanFilename(String filename) {
        if (filename == null || filename.isBlank()) {
            return "";
        }
        // 1. 移除空字符
        String clean = filename.replace("\0", "").trim();

        // 2. 提取最后一个路径分隔符之后的文件名
        int lastSlash = Math.max(clean.lastIndexOf('/'), clean.lastIndexOf('\\'));
        if (lastSlash >= 0) {
            clean = clean.substring(lastSlash + 1).trim();
        }

        // 3. 消除可能残留的相对路径标记
        while (clean.contains("..") || clean.startsWith(".") || clean.contains("/") || clean.contains("\\")) {
            clean = clean.replace("..", "").replace("/", "").replace("\\", "").trim();
            if (clean.startsWith(".")) {
                clean = clean.substring(1).trim();
            }
        }

        return clean;
    }

    /**
     * 安全规范化并清洗文件路径（防御路径穿越攻击 Path Traversal）
     * <p>
     * 1. 过滤空字符 \0 与首尾空白；<br>
     * 2. 统一将 Windows 反斜杠 \ 转换为标准正斜杠 /；<br>
     * 3. 规范化并消除冗余相对路径（. 与 ..）；<br>
     * 4. 严防超出根路径或恶意穿越提权（若检测到相对路径逃逸、包含残留 ../ 或以 /.. 结尾则抛出 IllegalArgumentException）。
     * </p>
     *
     * @param path 原始路径
     * @return 经过净化后的标准路径
     * @throws IllegalArgumentException 若路径为 null、空白或检测到恶意路径穿越
     */
    public static String cleanPath(String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("Path must not be null or blank");
        }
        String clean = path.replace("\0", "").replace('\\', '/').trim();
        boolean startsWithSlash = clean.startsWith("/");
        String[] rawSegments = clean.split("/+");
        java.util.List<String> segments = new java.util.ArrayList<>();
        for (String segment : rawSegments) {
            if (segment.isEmpty() || ".".equals(segment)) {
                continue;
            }
            if ("..".equals(segment)) {
                if (!segments.isEmpty() && !"..".equals(segments.get(segments.size() - 1))) {
                    segments.remove(segments.size() - 1);
                } else {
                    if (startsWithSlash) {
                        throw new IllegalArgumentException("检测到高危路径穿越 (Path Traversal) 尝试: " + path);
                    }
                    segments.add("..");
                }
            } else {
                segments.add(segment);
            }
        }
        if (segments.contains("..")) {
            throw new IllegalArgumentException("检测到高危路径穿越 (Path Traversal) 尝试: " + path);
        }
        StringBuilder result = new StringBuilder();
        if (startsWithSlash) {
            result.append("/");
        }
        result.append(String.join("/", segments));
        if (clean.endsWith("/") && result.length() > (startsWithSlash ? 1 : 0)) {
            result.append("/");
        }
        return result.length() == 0 ? (startsWithSlash ? "/" : "") : result.toString();
    }

    /**
     * 提取文件扩展名（不带点，全小写）
     * 例如："report.PDF" -> "pdf", "archive.tar.gz" -> "gz", "README" -> ""
     *
     * @param filename 文件名或文件路径
     * @return 扩展名字符串（统一转为小写）；无扩展名时返回空字符串 ""
     */
    public static String getExtName(String filename) {
        if (filename == null || filename.isBlank()) {
            return "";
        }
        String pureName = cleanFilename(filename);
        int lastDotIndex = pureName.lastIndexOf('.');
        if (lastDotIndex <= 0 || lastDotIndex == pureName.length() - 1) {
            return "";
        }
        return pureName.substring(lastDotIndex + 1).toLowerCase();
    }

    /**
     * 提取不包含扩展名的基础主文件名
     * 例如："user_avatar.png" -> "user_avatar", "/path/to/archive.tar.gz" -> "archive.tar"
     *
     * @param filename 文件名或文件路径
     * @return 基础主文件名
     */
    public static String getBaseName(String filename) {
        if (filename == null || filename.isBlank()) {
            return "";
        }
        String pureName = cleanFilename(filename);
        int lastDotIndex = pureName.lastIndexOf('.');
        if (lastDotIndex <= 0) {
            return pureName;
        }
        return pureName.substring(0, lastDotIndex);
    }

    /**
     * 将字节容量数值格式化为人类易读的单位格式（B, KB, MB, GB, TB, PB）
     * 保留两位小数
     *
     * @param bytes 字节数
     * @return 格式化后的容量字符串（如 "1.50 KB", "10.00 MB"）
     */
    public static String formatSize(long bytes) {
        if (bytes <= 0) {
            return "0 B";
        }
        if (bytes < 1024) {
            return bytes + " B";
        }
        int unitIndex = (int) (Math.log10(bytes) / Math.log10(1024));
        if (unitIndex >= SIZE_UNITS.length) {
            unitIndex = SIZE_UNITS.length - 1;
        }
        double sizeInUnit = bytes / Math.pow(1024, unitIndex);
        return String.format(java.util.Locale.ROOT, "%,.2f %s", sizeInUnit, SIZE_UNITS[unitIndex]);
    }
}
