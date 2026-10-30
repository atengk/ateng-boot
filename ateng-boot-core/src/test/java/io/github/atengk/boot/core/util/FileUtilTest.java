package io.github.atengk.boot.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * FileUtil 单元测试
 *
 * @author Ateng
 * @since 2026-10-17
 */
@DisplayName("FileUtil 文件安全清洗与工具测试")
class FileUtilTest {

    @Test
    @DisplayName("cleanFilename: 彻底净化路径穿越攻击")
    void shouldCleanPathTraversalAttacks() {
        assertEquals("passwd", FileUtil.cleanFilename("../../etc/passwd"));
        assertEquals("cmd.exe", FileUtil.cleanFilename("..\\..\\windows\\system32\\cmd.exe"));
        assertEquals("avatar.png", FileUtil.cleanFilename("user/photos/avatar.png"));
        assertEquals("safe.doc", FileUtil.cleanFilename("safe\0.doc"));
        assertEquals("test.txt", FileUtil.cleanFilename(".../test.txt"));
        assertEquals("", FileUtil.cleanFilename(null));
        assertEquals("", FileUtil.cleanFilename("   "));
    }

    @Test
    @DisplayName("cleanPath: 安全规范化清洗路径并拦截恶意穿越提权")
    void shouldCleanPathAndBlockTraversal() {
        assertEquals("/var/log/app.log", FileUtil.cleanPath("/var/log/app.log"));
        assertEquals("/var/log/app.log", FileUtil.cleanPath("/var/log/./app.log"));
        assertEquals("/var/log/app.log", FileUtil.cleanPath("/var/tmp/../log/app.log"));
        assertEquals("/var/log/", FileUtil.cleanPath("/var/log/"));
        assertEquals("/", FileUtil.cleanPath("/"));
        assertEquals("/", FileUtil.cleanPath("///"));
        assertEquals("a/b/c", FileUtil.cleanPath("a\\b\\c"));
        assertEquals("/etc/passwd.png", FileUtil.cleanPath("/etc/passwd\0.png"));

        // 恶意越界穿越测试
        assertThrows(IllegalArgumentException.class, () -> FileUtil.cleanPath(null));
        assertThrows(IllegalArgumentException.class, () -> FileUtil.cleanPath("   "));
        assertThrows(IllegalArgumentException.class, () -> FileUtil.cleanPath(".."));
        assertThrows(IllegalArgumentException.class, () -> FileUtil.cleanPath("/.."));
        assertThrows(IllegalArgumentException.class, () -> FileUtil.cleanPath("/sub/../../etc/passwd"));
        assertThrows(IllegalArgumentException.class, () -> FileUtil.cleanPath("../secret.key"));
    }

    @Test
    @DisplayName("getExtName: 提取扩展名并统一转小写")
    void shouldExtractExtensionNames() {
        assertEquals("png", FileUtil.getExtName("avatar.PNG"));
        assertEquals("gz", FileUtil.getExtName("/path/to/archive.tar.gz"));
        assertEquals("txt", FileUtil.getExtName("notes.txt"));
        assertEquals("", FileUtil.getExtName("README"));
        assertEquals("", FileUtil.getExtName(".gitignore"));
        assertEquals("", FileUtil.getExtName(null));
    }

    @Test
    @DisplayName("getBaseName: 提取基础主文件名")
    void shouldExtractBaseName() {
        assertEquals("avatar", FileUtil.getBaseName("avatar.png"));
        assertEquals("archive.tar", FileUtil.getBaseName("/data/archive.tar.gz"));
        assertEquals("README", FileUtil.getBaseName("README"));
        assertEquals("", FileUtil.getBaseName(null));
    }

    @Test
    @DisplayName("formatSize: 格式化容量为人类可读字符串")
    void shouldFormatByteSizes() {
        assertEquals("0 B", FileUtil.formatSize(0));
        assertEquals("0 B", FileUtil.formatSize(-100));
        assertEquals("500 B", FileUtil.formatSize(500));
        assertEquals("1.00 KB", FileUtil.formatSize(1024));
        assertEquals("1.50 KB", FileUtil.formatSize(1536));
        assertEquals("10.00 MB", FileUtil.formatSize(10L * 1024 * 1024));
        assertEquals("2.50 GB", FileUtil.formatSize((long) (2.5 * 1024 * 1024 * 1024)));
        assertEquals("1.00 TB", FileUtil.formatSize(1024L * 1024 * 1024 * 1024));
    }

    @Test
    @DisplayName("formatSize: 多线程并发调用无竞态格式错乱")
    void shouldFormatByteSizesConcurrently() {
        java.util.stream.IntStream.range(0, 50).parallel().forEach(i -> {
            assertEquals("1.50 KB", FileUtil.formatSize(1536));
            assertEquals("10.00 MB", FileUtil.formatSize(10L * 1024 * 1024));
        });
    }

    @Test
    @DisplayName("readNBytesSafe: 安全有界流读取与溢出防卫")
    void shouldReadNBytesSafely() throws java.io.IOException {
        byte[] data = "Hello World Safe".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.io.ByteArrayInputStream in = new java.io.ByteArrayInputStream(data);

        // 正常读取允许范围内
        byte[] read = FileUtil.readNBytesSafe(in, 20);
        assertArrayEquals(data, read);

        // Integer.MAX_VALUE 边界防溢出测试
        java.io.ByteArrayInputStream inMax = new java.io.ByteArrayInputStream(data);
        byte[] readMax = FileUtil.readNBytesSafe(inMax, Integer.MAX_VALUE);
        assertArrayEquals(data, readMax);

        // 超出上限抛出异常
        java.io.ByteArrayInputStream inOverflow = new java.io.ByteArrayInputStream(data);
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> FileUtil.readNBytesSafe(inOverflow, 10));
        assertTrue(ex.getMessage().contains("超出允许的最大安全限制"));

        // 空输入流安全返回
        assertArrayEquals(new byte[0], FileUtil.readNBytesSafe(null, 10));

        // 负数限制防御
        assertThrows(IllegalArgumentException.class, () -> FileUtil.readNBytesSafe(new java.io.ByteArrayInputStream(data), -1));
    }

    @Test
    @DisplayName("createTempFile: 创建受管安全临时文件与隔离目录测试")
    void shouldCreateManagedTempFile(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws java.io.IOException {
        // 在受隔离的 TempDir 目录下创建受管临时文件
        java.io.File tempFile = FileUtil.createTempFile(tempDir.toFile(), "test-run-", ".log");
        assertNotNull(tempFile);
        assertTrue(tempFile.exists());
        assertTrue(tempFile.getName().endsWith(".log"));
        assertEquals(tempDir.toFile().getCanonicalPath(), tempFile.getParentFile().getCanonicalPath());
        tempFile.delete();

        // 默认前后缀创建于隔离目录
        java.io.File defaultTemp = FileUtil.createTempFile(tempDir.toFile(), null, null);
        assertNotNull(defaultTemp);
        assertTrue(defaultTemp.exists());
        defaultTemp.delete();

        // 无目录入参系统临时目录重载
        java.io.File sysTemp = FileUtil.createTempFile("sys-temp-", ".tmp");
        assertNotNull(sysTemp);
        assertTrue(sysTemp.exists());
        sysTemp.delete();
    }

    @Test
    @DisplayName("getAppTempDirectory: 应用隔离受管临时目录获取与安全清洗")
    void shouldGetAppTempDirectorySafely() {
        java.nio.file.Path appTempDir = FileUtil.getAppTempDirectory("order-service");
        assertNotNull(appTempDir);
        assertTrue(java.nio.file.Files.exists(appTempDir));
        assertTrue(appTempDir.toString().contains("order-service"));

        // 包含恶意路径时被清洗
        java.nio.file.Path cleanTempDir = FileUtil.getAppTempDirectory("../evil-service");
        assertNotNull(cleanTempDir);
        assertTrue(java.nio.file.Files.exists(cleanTempDir));
        assertFalse(cleanTempDir.toString().contains(".."));

        // 默认重载
        java.nio.file.Path defaultTempDir = FileUtil.getAppTempDirectory();
        assertNotNull(defaultTempDir);
        assertTrue(java.nio.file.Files.exists(defaultTempDir));
    }

    @Test
    @DisplayName("cleanDirectorySafely: 安全清理目录及其子文件测试")
    void shouldCleanDirectorySafely(@org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir) throws java.io.IOException {
        java.nio.file.Path subDir = tempDir.resolve("sub");
        java.nio.file.Files.createDirectories(subDir);
        java.nio.file.Path file1 = tempDir.resolve("file1.txt");
        java.nio.file.Path file2 = subDir.resolve("file2.txt");
        java.nio.file.Files.writeString(file1, "hello");
        java.nio.file.Files.writeString(file2, "world");

        int cleaned = FileUtil.cleanDirectorySafely(tempDir);
        assertEquals(3, cleaned); // file1, file2, sub
        assertTrue(java.nio.file.Files.exists(tempDir));
        assertFalse(java.nio.file.Files.exists(file1));
        assertFalse(java.nio.file.Files.exists(file2));
        assertFalse(java.nio.file.Files.exists(subDir));

        // 清理空目录或不存在目录
        assertEquals(0, FileUtil.cleanDirectorySafely(null));
        assertEquals(0, FileUtil.cleanDirectorySafely(tempDir.resolve("non-existent")));
    }
}
