package io.github.atengk.boot.storage.resource;

import io.github.atengk.boot.storage.driver.LocalDriver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
}
