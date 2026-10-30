package io.github.atengk.boot.core.util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 文件名与文件属性安全通用工具
 * 提供针对路径遍历攻击（Path Traversal）的纯净文件名清洗、扩展名提取与人类可读文件容量格式化
 *
 * @author Ateng
 * @since 2026-10-17
 */
public final class FileUtil {

    private static final Logger log = LoggerFactory.getLogger(FileUtil.class);

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

    /**
     * 安全读取输入流指定上限字节数（防 OOM 内存耗尽安全有界读取）
     * 自动探测流大小，若流实际字节数超出 maxBytes，立即抛出 IllegalArgumentException 快速阻断
     *
     * @param inputStream 输入流，为 null 时安全返回空字节数组
     * @param maxBytes    允许读取的最大字节数（必须大于或等于 0）
     * @return 读取得到的字节数组
     * @throws java.io.IOException      IO 异常
     * @throws IllegalArgumentException 当 maxBytes 小于 0 或输入流实际大小超出 maxBytes 限制时抛出
     */
    public static byte[] readNBytesSafe(java.io.InputStream inputStream, int maxBytes) throws java.io.IOException {
        if (inputStream == null) {
            return new byte[0];
        }
        if (maxBytes < 0) {
            throw new IllegalArgumentException("maxBytes 不能为负数: " + maxBytes);
        }
        // 当 maxBytes 达到 Integer.MAX_VALUE 时，避免 maxBytes + 1 发生整型溢出
        if (maxBytes == Integer.MAX_VALUE) {
            byte[] bytes = inputStream.readNBytes(maxBytes);
            if (inputStream.read() != -1) {
                throw new IllegalArgumentException("输入流实际字节数超出允许的最大安全限制: " + maxBytes + " bytes (" + formatSize(maxBytes) + ")");
            }
            return bytes;
        }
        // 读取 maxBytes + 1 字节探测是否溢出
        byte[] bytes = inputStream.readNBytes(maxBytes + 1);
        if (bytes.length > maxBytes) {
            throw new IllegalArgumentException("输入流实际字节数超出允许的最大安全限制: " + maxBytes + " bytes (" + formatSize(maxBytes) + ")");
        }
        return bytes;
    }

    /**
     * 在默认临时目录创建并在 JVM 退出时自动删除的安全受管临时文件
     * 自动对前缀进行路径穿越清洗以确保安全
     *
     * @param prefix 临时文件名前缀，为空或 null 时默认使用 "ateng-temp-"
     * @param suffix 临时文件名后缀（扩展名，如 ".tmp"），为 null 时默认使用 ".tmp"
     * @return 创建成功的受管临时文件实例
     * @throws java.io.IOException 创建临时文件失败时抛出
     */
    public static java.io.File createTempFile(String prefix, String suffix) throws java.io.IOException {
        return createTempFile(null, prefix, suffix);
    }

    /**
     * 在指定目录创建并在 JVM 退出时自动删除的安全受管临时文件
     * 自动对前缀进行路径穿越清洗以确保安全
     *
     * @param directory 目标存储目录，为 null 时使用系统默认临时目录
     * @param prefix    临时文件名前缀，为空或 null 时默认使用 "ateng-temp-"
     * @param suffix    临时文件名后缀（扩展名，如 ".tmp"），为 null 时默认使用 ".tmp"
     * @return 创建成功的受管临时文件实例
     * @throws java.io.IOException 创建临时文件失败时抛出
     */
    public static java.io.File createTempFile(java.io.File directory, String prefix, String suffix) throws java.io.IOException {
        String safePrefix = (prefix != null && !prefix.isBlank()) ? cleanFilename(prefix) : "ateng-temp-";
        if (safePrefix.length() < 3) {
            safePrefix = safePrefix + "tmp";
        }
        String safeSuffix = suffix != null ? suffix : ".tmp";
        java.io.File tempFile = java.io.File.createTempFile(safePrefix, safeSuffix, directory);
        tempFile.deleteOnExit();
        return tempFile;
    }

    /**
     * 获取当前应用专属的受管临时根目录
     * 路径规范：${java.io.tmpdir}/ateng-boot/${appName}/
     * 若目录不存在则自动创建，并对应用名称执行路径遍历安全清洗
     *
     * @param appName 应用名称（为空时回退至 "application"）
     * @return 应用隔离的受管临时工作目录 Path
     */
    public static java.nio.file.Path getAppTempDirectory(String appName) {
        String safeAppName = (appName != null && !appName.isBlank()) ? cleanFilename(appName) : "application";
        if (safeAppName.isBlank()) {
            safeAppName = "application";
        }
        String systemTmpDir = System.getProperty("java.io.tmpdir");
        java.nio.file.Path appTempDir = java.nio.file.Paths.get(systemTmpDir, "ateng-boot", safeAppName);
        try {
            java.nio.file.Files.createDirectories(appTempDir);
        } catch (java.io.IOException ex) {
            log.error("创建应用受管临时目录失败: {}", appTempDir, ex);
            throw new IllegalStateException("创建应用受管临时目录失败: " + appTempDir, ex);
        }
        return appTempDir;
    }

    /**
     * 获取默认应用专属的受管临时根目录（应用名默认回退至 "application"）
     *
     * @return 应用隔离的受管临时工作目录 Path
     */
    public static java.nio.file.Path getAppTempDirectory() {
        return getAppTempDirectory("application");
    }

    /**
     * 安全扫描并清理指定目录下的所有临时文件与子目录（防御异常中断，尽力清理）
     *
     * @param directory 目标待清理目录
     * @return 成功清理的文件与子目录总数
     */
    public static int cleanDirectorySafely(java.nio.file.Path directory) {
        if (directory == null || !java.nio.file.Files.exists(directory)) {
            return 0;
        }
        // 防御性前置卫语句：严禁清理根系统临时目录或文件系统根目录
        java.nio.file.Path normalizedTarget = directory.toAbsolutePath().normalize();
        String systemTmp = System.getProperty("java.io.tmpdir");
        if (systemTmp != null && !systemTmp.isBlank()) {
            java.nio.file.Path normalizedSystemTmp = java.nio.file.Paths.get(systemTmp).toAbsolutePath().normalize();
            if (normalizedTarget.equals(normalizedSystemTmp) || normalizedTarget.getParent() == null) {
                log.warn("拒绝清理系统根临时目录或文件系统根路径: {}", normalizedTarget);
                return 0;
            }
        }
        int cleanedCount = 0;
        try (java.util.stream.Stream<java.nio.file.Path> stream = java.nio.file.Files.walk(directory)) {
            java.util.List<java.nio.file.Path> paths = stream
                    .filter(path -> !path.equals(directory))
                    .sorted(java.util.Comparator.reverseOrder())
                    .toList();
            for (java.nio.file.Path path : paths) {
                try {
                    java.nio.file.Files.deleteIfExists(path);
                    cleanedCount++;
                } catch (Throwable ex) {
                    log.debug("跳过清理被占用或无权限文件: {}", path, ex);
                }
            }
        } catch (Throwable ex) {
            log.warn("遍历清理目录失败: {}", directory, ex);
        }
        return cleanedCount;
    }
}
