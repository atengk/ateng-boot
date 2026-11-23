package io.github.atengk.boot.storage.resource;

import io.github.atengk.boot.storage.driver.LocalDriver;
import io.github.atengk.boot.storage.exception.StorageException;
import io.github.atengk.boot.storage.exception.StorageResultCode;
import io.github.atengk.boot.storage.guard.StorageGuard;
import io.github.atengk.boot.storage.properties.StorageGuardProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;

import java.io.ByteArrayInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * StorageResource Spring 原生资源契约适配测试
 *
 * @author Ateng
 * @since 2026-10-17
 */
@DisplayName("StorageResource Spring 资源适配测试")
class StorageResourceTest {

    @TempDir
    Path tempDir;

    private LocalDriver driver;

    @BeforeEach
    void setUp() {
        driver = new LocalDriver(tempDir.toString());
    }

    @Test
    @DisplayName("读取现有存储对象的 Spring Resource 契约正确有效")
    void testReadResource() throws Exception {
        byte[] content = "Spring Resource Content".getBytes(StandardCharsets.UTF_8);
        String path = "data/sample.txt";
        driver.upload(path, new ByteArrayInputStream(content), "text/plain", content.length);

        StorageResource resource = new StorageResource(path, driver);

        assertTrue(resource.exists());
        assertTrue(resource.isReadable());
        assertTrue(resource.isWritable());
        assertEquals("sample.txt", resource.getFilename());
        assertEquals("text/plain", resource.getContentType());
        assertEquals(content.length, resource.contentLength());
        assertTrue(resource.lastModified() > 0);

        try (InputStream in = resource.getInputStream()) {
            assertArrayEquals(content, in.readAllBytes());
        }
    }

    @Test
    @DisplayName("通过 WritableResource getOutputStream 写入对象成功")
    void testWriteResource() throws Exception {
        String path = "output/written.txt";
        StorageResource resource = new StorageResource(path, driver);

        byte[] writtenBytes = "WritableResource Output Stream".getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = resource.getOutputStream()) {
            out.write(writtenBytes);
        }

        assertTrue(driver.exists(path));
        assertEquals(writtenBytes.length, resource.contentLength());

        try (InputStream in = resource.getInputStream()) {
            assertArrayEquals(writtenBytes, in.readAllBytes());
        }
    }

    @Test
    @DisplayName("目标对象不存在时 Resource 契约抛出标准 FileNotFoundException")
    void testNonExistentResourceThrowsFileNotFoundException() {
        StorageResource resource = new StorageResource("ghost/file.txt", driver);

        assertFalse(resource.exists());
        assertThrows(FileNotFoundException.class, resource::getInputStream);
        assertThrows(FileNotFoundException.class, resource::contentLength);
        assertThrows(FileNotFoundException.class, resource::lastModified);
    }

    @Test
    @DisplayName("多次调用 getOutputStream().close() 具备严格幂等性，仅执行一次落盘上传")
    void testWriteResourceCloseIdempotent() throws Exception {
        LocalDriver spiedDriver = spy(driver);
        String path = "output/idempotent.txt";
        StorageResource resource = new StorageResource(path, spiedDriver);

        byte[] writtenBytes = "Idempotent Close Content".getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = resource.getOutputStream()) {
            out.write(writtenBytes);
            out.close();
            out.close();
        }

        verify(spiedDriver, times(1)).upload(eq(path), any(InputStream.class), any(), anyLong());
        assertEquals(writtenBytes.length, resource.contentLength());
        try (InputStream in = resource.getInputStream()) {
            assertArrayEquals(writtenBytes, in.readAllBytes());
        }
    }

    @Test
    @DisplayName("开启 StorageGuard 且配置 maxFileSize 时，getOutputStream 写入超限拦截 40020 且不触发落盘上传")
    void testWriteResourceExceedsMaxFileSizeThrows40020() throws Exception {
        StorageGuardProperties guardProps = new StorageGuardProperties();
        guardProps.setEnabled(true);
        guardProps.setMaxFileSize(DataSize.ofBytes(16));
        StorageGuard guard = new StorageGuard(guardProps);

        LocalDriver spiedDriver = spy(driver);
        java.util.concurrent.atomic.AtomicInteger callbackCount = new java.util.concurrent.atomic.AtomicInteger(0);
        String overLimitPath = "output/too-large.txt";
        StorageResource overLimitResource = new StorageResource(
                overLimitPath, spiedDriver, guard, meta -> callbackCount.incrementAndGet());

        byte[] oversized = "0123456789ABCDEFG".getBytes(StandardCharsets.UTF_8); // 17 bytes > 16
        StorageException ex = assertThrows(StorageException.class, () -> {
            try (OutputStream out = overLimitResource.getOutputStream()) {
                out.write(oversized);
            }
        });
        assertEquals(StorageResultCode.STORAGE_SIZE_EXCEEDED, ex.getResultCode());
        assertFalse(driver.exists(overLimitPath));
        assertEquals(0, callbackCount.get());
        verify(spiedDriver, org.mockito.Mockito.never()).upload(eq(overLimitPath), any(InputStream.class), any(), anyLong());

        // 未超限字节正常写入落盘并触发单次上传与事件回调
        String okPath = "output/within-limit.txt";
        StorageResource okResource = new StorageResource(
                okPath, spiedDriver, guard, meta -> callbackCount.incrementAndGet());
        byte[] validBytes = "0123456789ABCDEF".getBytes(StandardCharsets.UTF_8); // 16 bytes
        try (OutputStream out = okResource.getOutputStream()) {
            out.write(validBytes);
        }
        assertTrue(driver.exists(okPath));
        assertEquals(1, callbackCount.get());
    }
}
