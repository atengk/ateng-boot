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
import java.nio.file.Path;
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
}
