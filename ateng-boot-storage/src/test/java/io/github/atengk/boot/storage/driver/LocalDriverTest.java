package io.github.atengk.boot.storage.driver;

import io.github.atengk.boot.storage.exception.StorageException;
import io.github.atengk.boot.storage.exception.StorageResultCode;
import io.github.atengk.boot.storage.model.PresignedUrl;
import io.github.atengk.boot.storage.model.StorageObject;
import io.github.atengk.boot.storage.util.StorageSecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * LocalDriver 本地磁盘驱动单元测试
 * 基于 JUnit 5 @TempDir 临时目录，实现纯内存快速自验
 *
 * @author Ateng
 * @since 2026-10-17
 */
@DisplayName("LocalDriver 本地磁盘存储驱动测试")
class LocalDriverTest {

    @TempDir
    Path tempDir;

    private LocalDriver driver;

    @BeforeEach
    void setUp() {
        driver = new LocalDriver(tempDir.toString(), "http://localhost:8080", "test-secret-key");
    }

    @Test
    @DisplayName("上传对象并流式读取内容成功")
    void testUploadAndDownload() throws Exception {
        byte[] content = "Hello Ateng Boot Storage!".getBytes(StandardCharsets.UTF_8);
        String path = "documents/2026/hello.txt";

        StorageObject object = driver.upload(path, new ByteArrayInputStream(content), "text/plain", content.length);

        assertNotNull(object);
        assertEquals("documents/2026/hello.txt", object.getPath());
        assertEquals("hello.txt", object.getFilename());
        assertEquals(content.length, object.getSize());
        assertEquals("text/plain", object.getContentType());
        assertNotNull(object.getEtag());
        assertNotNull(object.getLastModified());

        assertTrue(driver.exists(path));

        try (InputStream in = driver.download(path)) {
            byte[] downloaded = in.readAllBytes();
            assertArrayEquals(content, downloaded);
        }
    }

    @Test
    @DisplayName("获取存储对象元数据成功")
    void testGetMetadata() {
        byte[] content = "Metadata Test".getBytes(StandardCharsets.UTF_8);
        String path = "meta/test.json";

        driver.upload(path, new ByteArrayInputStream(content), null, -1);

        StorageObject meta = driver.getMetadata(path);
        assertNotNull(meta);
        assertEquals("meta/test.json", meta.getPath());
        assertEquals("test.json", meta.getFilename());
        assertEquals(content.length, meta.getSize());
        assertEquals("application/json", meta.getContentType());
        assertNotNull(meta.getEtag());
        assertNotNull(meta.getLastModified());
    }

    @Test
    @DisplayName("删除操作分布式天然幂等（存在与不存在均静默返回 true）")
    void testDeleteIdempotent() {
        byte[] content = "To be deleted".getBytes(StandardCharsets.UTF_8);
        String path = "temp/delete-me.txt";

        driver.upload(path, new ByteArrayInputStream(content), null, -1);
        assertTrue(driver.exists(path));

        // 第一次删除存在的对象
        assertTrue(driver.delete(path));
        assertFalse(driver.exists(path));

        // 第二次删除已不存在的对象（幂等）
        assertTrue(driver.delete(path));

        // 删除从未存在过的对象（幂等）
        assertTrue(driver.delete("not-exist-dir/none.txt"));
    }

    @Test
    @DisplayName("批量删除操作天然幂等成功")
    void testDeleteBatch() {
        driver.upload("batch/1.txt", new ByteArrayInputStream(new byte[]{1}), null, -1);
        driver.upload("batch/2.txt", new ByteArrayInputStream(new byte[]{2}), null, -1);

        assertTrue(driver.exists("batch/1.txt"));
        assertTrue(driver.exists("batch/2.txt"));

        assertTrue(driver.deleteBatch(List.of("batch/1.txt", "batch/2.txt", "batch/not-exist.txt")));

        assertFalse(driver.exists("batch/1.txt"));
        assertFalse(driver.exists("batch/2.txt"));
    }

    @Test
    @DisplayName("读取或获取不存在对象的元数据时抛出 OBJECT_NOT_FOUND 异常")
    void testNotFoundExceptions() {
        StorageException ex1 = assertThrows(StorageException.class, () -> driver.download("non-existent.txt"));
        assertEquals(StorageResultCode.OBJECT_NOT_FOUND.getCode(), ex1.getCode());

        StorageException ex2 = assertThrows(StorageException.class, () -> driver.getMetadata("non-existent.txt"));
        assertEquals(StorageResultCode.OBJECT_NOT_FOUND.getCode(), ex2.getCode());
    }

    @Test
    @DisplayName("尝试通过相对路径穿越上传时被底层驱动拦截")
    void testPathTraversalDefense() {
        ByteArrayInputStream in = new ByteArrayInputStream("bad".getBytes(StandardCharsets.UTF_8));
        StorageException ex = assertThrows(StorageException.class, () -> driver.upload("../evil.sh", in, null, -1));
        assertEquals(StorageResultCode.INVALID_PATH.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("生成有时效性的 HMAC-SHA256 预签名下载外链")
    void testGetPresignedUrl() {
        String path = "docs/2026/manual.pdf";
        PresignedUrl presigned = driver.getPresignedUrl(path, Duration.ofMinutes(10));

        assertNotNull(presigned);
        assertEquals("GET", presigned.getHttpMethod());
        assertTrue(presigned.getExpiration().isAfter(Instant.now()));

        String url = presigned.getUrl();
        assertTrue(url.startsWith("http://localhost:8080/storage/local/download?path=docs/2026/manual.pdf"));
        assertTrue(url.contains("&expires="));
        assertTrue(url.contains("&signature="));

        // 提取 expires 与 signature 并验证合法性
        String query = url.substring(url.indexOf('?') + 1);
        String expiresStr = null;
        String signature = null;
        for (String param : query.split("&")) {
            if (param.startsWith("expires=")) {
                expiresStr = param.substring("expires=".length());
            } else if (param.startsWith("signature=")) {
                signature = param.substring("signature=".length());
            }
        }
        assertNotNull(expiresStr);
        assertNotNull(signature);

        assertTrue(StorageSecurityUtil.verifyHmacSha256("docs/2026/manual.pdf:" + expiresStr, driver.getSecretKey(), signature));
    }

    @Test
    @DisplayName("复制本地文件成功并保留原文件")
    void testCopySuccess() throws Exception {
        byte[] content = "Copy Source Content".getBytes(StandardCharsets.UTF_8);
        String srcPath = "source/original.txt";
        String dstPath = "archive/copied.txt";

        driver.upload(srcPath, new ByteArrayInputStream(content), "text/plain", content.length);
        assertTrue(driver.exists(srcPath));

        boolean copied = driver.copy(srcPath, dstPath);
        assertTrue(copied);
        assertTrue(driver.exists(srcPath));
        assertTrue(driver.exists(dstPath));

        try (InputStream in = driver.download(dstPath)) {
            assertArrayEquals(content, in.readAllBytes());
        }
    }

    @Test
    @DisplayName("复制不存在的源文件时抛出 OBJECT_NOT_FOUND 异常")
    void testCopySourceNotFound() {
        StorageException ex = assertThrows(StorageException.class, () -> driver.copy("non/exist.txt", "target/file.txt"));
        assertEquals(StorageResultCode.OBJECT_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("移动本地文件成功且源文件被移除")
    void testMoveSuccess() throws Exception {
        byte[] content = "Move Source Content".getBytes(StandardCharsets.UTF_8);
        String srcPath = "source/to_move.txt";
        String dstPath = "archive/moved.txt";

        driver.upload(srcPath, new ByteArrayInputStream(content), "text/plain", content.length);
        assertTrue(driver.exists(srcPath));

        boolean moved = driver.move(srcPath, dstPath);
        assertTrue(moved);
        assertFalse(driver.exists(srcPath));
        assertTrue(driver.exists(dstPath));

        try (InputStream in = driver.download(dstPath)) {
            assertArrayEquals(content, in.readAllBytes());
        }
    }

    @Test
    @DisplayName("移动不存在的源文件时抛出 OBJECT_NOT_FOUND 异常")
    void testMoveSourceNotFound() {
        StorageException ex = assertThrows(StorageException.class, () -> driver.move("non/exist.txt", "target/file.txt"));
        assertEquals(StorageResultCode.OBJECT_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("平铺列举本地目录（非递归，返回直接文件及子目录并过滤系统隐藏项）")
    void testListFlat() {
        driver.upload("repo/file1.txt", new ByteArrayInputStream("f1".getBytes(StandardCharsets.UTF_8)), "text/plain", 2);
        driver.upload("repo/file2.txt", new ByteArrayInputStream("f2".getBytes(StandardCharsets.UTF_8)), "text/plain", 2);
        driver.upload("repo/sub/file3.txt", new ByteArrayInputStream("f3".getBytes(StandardCharsets.UTF_8)), "text/plain", 2);
        driver.upload("repo/.chunks/part1", new ByteArrayInputStream("p1".getBytes(StandardCharsets.UTF_8)), "text/plain", 2);
        driver.upload("repo/desktop.ini", new ByteArrayInputStream("ini".getBytes(StandardCharsets.UTF_8)), "text/plain", 3);

        List<StorageObject> items = driver.list("repo", false);
        assertNotNull(items);
        assertEquals(3, items.size());

        // 验证 file1.txt, file2.txt, sub (目录)
        StorageObject file1 = items.stream().filter(i -> "repo/file1.txt".equals(i.getPath())).findFirst().orElse(null);
        assertNotNull(file1);
        assertFalse(file1.isDirectory());

        StorageObject file2 = items.stream().filter(i -> "repo/file2.txt".equals(i.getPath())).findFirst().orElse(null);
        assertNotNull(file2);
        assertFalse(file2.isDirectory());

        StorageObject subDir = items.stream().filter(i -> "repo/sub".equals(i.getPath())).findFirst().orElse(null);
        assertNotNull(subDir);
        assertTrue(subDir.isDirectory());
        assertEquals("sub", subDir.getFilename());
    }

    @Test
    @DisplayName("递归列举本地目录所有文件并过滤隐藏项")
    void testListRecursive() {
        driver.upload("tree/a.txt", new ByteArrayInputStream("a".getBytes(StandardCharsets.UTF_8)), "text/plain", 1);
        driver.upload("tree/sub1/b.txt", new ByteArrayInputStream("b".getBytes(StandardCharsets.UTF_8)), "text/plain", 1);
        driver.upload("tree/sub1/sub2/c.txt", new ByteArrayInputStream("c".getBytes(StandardCharsets.UTF_8)), "text/plain", 1);
        driver.upload("tree/sub1/.hidden/d.txt", new ByteArrayInputStream("d".getBytes(StandardCharsets.UTF_8)), "text/plain", 1);
        driver.upload("tree/sub1/desktop.ini", new ByteArrayInputStream("ini".getBytes(StandardCharsets.UTF_8)), "text/plain", 3);

        List<StorageObject> items = driver.list("tree", true);
        assertNotNull(items);
        assertEquals(3, items.size());

        List<String> paths = items.stream().map(StorageObject::getPath).toList();
        assertTrue(paths.contains("tree/a.txt"));
        assertTrue(paths.contains("tree/sub1/b.txt"));
        assertTrue(paths.contains("tree/sub1/sub2/c.txt"));
        assertFalse(paths.contains("tree/sub1/.hidden/d.txt"));
        assertFalse(paths.contains("tree/sub1/desktop.ini"));
    }

    @Test
    @DisplayName("列举不存在的路径保底返回空集合")
    void testListNonExistentPath() {
        List<StorageObject> items = driver.list("non/exist/dir", false);
        assertNotNull(items);
        assertTrue(items.isEmpty());
    }

    @Test
    @DisplayName("LocalDriver 生成分片预签名直传外链成功")
    void testLocalGetPresignedUploadPartUrlSuccess() {
        String path = "bigfiles/archive.tar.gz";
        String uploadId = driver.initiateChunkedUpload(path);

        PresignedUrl partUrl = driver.getPresignedUploadPartUrl(path, uploadId, 1, Duration.ofMinutes(15));
        assertNotNull(partUrl);
        assertEquals("PUT", partUrl.getHttpMethod());
        assertNotNull(partUrl.getExpiration());
        assertTrue(partUrl.getUrl().startsWith("http://localhost:8080/storage/local/upload"));
        assertTrue(partUrl.getUrl().contains("path=bigfiles/archive.tar.gz"));
        assertTrue(partUrl.getUrl().contains("uploadId=" + uploadId));
        assertTrue(partUrl.getUrl().contains("chunkIndex=1"));
        assertTrue(partUrl.getUrl().contains("signature="));
    }

    @Test
    @DisplayName("LocalDriver 生成分片直传外链在未找到会话或已被清理时抛出异常")
    void testLocalGetPresignedUploadPartUrlSessionNotFound() {
        assertThrows(StorageException.class, () ->
                driver.getPresignedUploadPartUrl("bigfiles/archive.tar.gz", "nonexistuploadid123", 1, Duration.ofMinutes(15)));
    }

    @Test
    @DisplayName("LocalDriver 生成分片直传外链时分片索引非法拦截")
    void testLocalGetPresignedUploadPartUrlInvalidChunkIndex() {
        String path = "bigfiles/archive.tar.gz";
        String uploadId = driver.initiateChunkedUpload(path);

        StorageException ex = assertThrows(StorageException.class, () ->
                driver.getPresignedUploadPartUrl(path, uploadId, 0, Duration.ofMinutes(15)));
        assertEquals(StorageResultCode.STORAGE_CHUNK_INVALID, ex.getResultCode());
    }

    @Test
    @DisplayName("LocalDriver 目录与前缀级流式批量删除成功")
    void testDeletePrefixAndDirectorySuccess() {
        driver.upload("logs/2026/app.log", new ByteArrayInputStream("app".getBytes()), "text/plain", 3);
        driver.upload("logs/2026/error.log", new ByteArrayInputStream("err".getBytes()), "text/plain", 3);
        driver.upload("logs/audit.log", new ByteArrayInputStream("audit".getBytes()), "text/plain", 5);
        driver.upload("other/file.txt", new ByteArrayInputStream("other".getBytes()), "text/plain", 5);

        // 1. 删除特定子目录 logs/2026
        assertTrue(driver.deleteDirectory("logs/2026"));
        assertFalse(driver.exists("logs/2026/app.log"));
        assertFalse(driver.exists("logs/2026/error.log"));
        assertTrue(driver.exists("logs/audit.log"));
        assertTrue(driver.exists("other/file.txt"));

        // 2. 删除前缀 logs
        assertTrue(driver.deletePrefix("logs"));
        assertFalse(driver.exists("logs/audit.log"));
        assertTrue(driver.exists("other/file.txt"));
    }

    @Test
    @DisplayName("LocalDriver 批量删除防误删熔断保护：根路径、通配符等立即阻断")
    void testDeletePrefixMeltDownProtection() {
        assertThrows(StorageException.class, () -> driver.deletePrefix(null));
        assertThrows(StorageException.class, () -> driver.deletePrefix(""));
        assertThrows(StorageException.class, () -> driver.deletePrefix("/"));
        assertThrows(StorageException.class, () -> driver.deletePrefix("///"));
        assertThrows(StorageException.class, () -> driver.deletePrefix("."));
        assertThrows(StorageException.class, () -> driver.deletePrefix("*"));
        assertThrows(StorageException.class, () -> driver.deletePrefix("logs/*"));
        assertThrows(StorageException.class, () -> driver.deleteDirectory("/"));
    }

    @Test
    @DisplayName("LocalDriver 扫描并清理超时孤儿分片碎片，保留未过期分片")
    void testCleanExpiredChunkSessions() throws Exception {
        String upload1 = driver.initiateChunkedUpload("big/file1.zip");
        String upload2 = driver.initiateChunkedUpload("big/file2.zip");

        Path chunkDir1 = tempDir.resolve(".chunks").resolve(upload1);
        Path chunkDir2 = tempDir.resolve(".chunks").resolve(upload2);
        assertTrue(Files.exists(chunkDir1));
        assertTrue(Files.exists(chunkDir2));

        // 模拟 upload1 为 48 小时前修改（超时孤儿分片）
        Instant twoDaysAgo = Instant.now().minus(Duration.ofHours(48));
        Files.setLastModifiedTime(chunkDir1, FileTime.from(twoDaysAgo));

        // 模拟 upload2 为 1 小时前修改（仍在活跃期）
        Instant oneHourAgo = Instant.now().minus(Duration.ofHours(1));
        Files.setLastModifiedTime(chunkDir2, FileTime.from(oneHourAgo));

        // 清理超过 24 小时的孤儿分片
        int cleaned = driver.cleanExpiredChunkSessions(Duration.ofHours(24));
        assertEquals(1, cleaned);
        assertFalse(Files.exists(chunkDir1));
        assertTrue(Files.exists(chunkDir2));
    }
}
