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
    @DisplayName("createTempFile: 创建受管安全临时文件")
    void shouldCreateManagedTempFile() throws java.io.IOException {
        java.io.File tempFile = FileUtil.createTempFile("test-run-", ".log");
        assertNotNull(tempFile);
        assertTrue(tempFile.exists());
        assertTrue(tempFile.getName().endsWith(".log"));
        tempFile.delete();

        // 默认前后缀
        java.io.File defaultTemp = FileUtil.createTempFile(null, null);
        assertNotNull(defaultTemp);
        assertTrue(defaultTemp.exists());
        defaultTemp.delete();
    }
}
