package io.github.atengk.boot.storage.template;

import io.github.atengk.boot.storage.driver.LocalDriver;
import io.github.atengk.boot.storage.exception.StorageException;
import io.github.atengk.boot.storage.exception.StorageResultCode;
import io.github.atengk.boot.storage.model.StorageObject;
import io.github.atengk.boot.storage.resource.StorageResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * StorageTemplate 门面操作测试
 *
 * @author Ateng
 * @since 2026-10-17
 */
@DisplayName("StorageTemplate 操作门面测试")
class StorageTemplateTest {

    @TempDir
    Path tempDir;

    private LocalDriver driver;
    private StorageTemplate template;

    @BeforeEach
    void setUp() {
        driver = new LocalDriver(tempDir.toString());
        template = new StorageTemplate(driver);
    }

    @Test
    @DisplayName("通过 StorageTemplate 门面流式上传与下载")
    void testUploadAndDownload() throws Exception {
        byte[] data = "Template Data".getBytes(StandardCharsets.UTF_8);
        String path = "files/info.txt";

        StorageObject obj = template.upload(path, new ByteArrayInputStream(data));
        assertNotNull(obj);
        assertEquals("files/info.txt", obj.getPath());
        assertEquals(data.length, obj.getSize());

        assertTrue(template.exists(path));

        try (InputStream in = template.download(path)) {
            assertArrayEquals(data, in.readAllBytes());
        }
    }

    @Test
    @DisplayName("通过 StorageTemplate 门面上传 MultipartFile 表单文件")
    void testUploadMultipartFile() throws Exception {
        byte[] imageBytes = new byte[]{1, 2, 3, 4, 5};
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", imageBytes);

        // 1. 上传至目录（自动追加原始安全文件名）
        StorageObject obj1 = template.upload("uploads/avatars/", file);
        assertEquals("uploads/avatars/avatar.png", obj1.getPath());
        assertEquals("image/png", obj1.getContentType());
        assertTrue(template.exists("uploads/avatars/avatar.png"));

        // 2. 上传未指定 path（自动使用原始文件名）
        StorageObject obj2 = template.upload(null, file);
        assertEquals("avatar.png", obj2.getPath());
        assertTrue(template.exists("avatar.png"));

        // 3. 上传空文件快速失败
        MockMultipartFile emptyFile = new MockMultipartFile("empty", "empty.txt", "text/plain", new byte[0]);
        assertThrows(StorageException.class, () -> template.upload("empty.txt", emptyFile));
    }

    @Test
    @DisplayName("通过 StorageTemplate 获取 Spring Resource 资源")
    void testGetResource() throws Exception {
        byte[] data = "Resource content".getBytes(StandardCharsets.UTF_8);
        template.upload("res/data.txt", new ByteArrayInputStream(data));

        StorageResource resource = template.getResource("res/data.txt");
        assertNotNull(resource);
        assertTrue(resource.exists());
        assertEquals("data.txt", resource.getFilename());
        assertEquals(data.length, resource.contentLength());
    }

    @Test
    @DisplayName("通过 StorageTemplate 执行幂等删除与批量删除")
    void testDeleteAndBatch() {
        template.upload("del/1.txt", new ByteArrayInputStream(new byte[]{1}));
        template.upload("del/2.txt", new ByteArrayInputStream(new byte[]{2}));

        assertTrue(template.delete("del/1.txt"));
        assertFalse(template.exists("del/1.txt"));
        // 幂等重复删除
        assertTrue(template.delete("del/1.txt"));

        assertTrue(template.deleteBatch(List.of("del/2.txt", "del/3-not-exist.txt")));
        assertFalse(template.exists("del/2.txt"));
    }

    @Test
    @DisplayName("切源路由调度行为测试")
    void testRoutingUse() {
        // use("primary") 或 use("default") 返回自身
        assertNotNull(template.use("primary"));
        assertNotNull(template.use("default"));
        assertNotNull(template.use(null));

        // use 未知存储源抛出 STORAGE_SOURCE_NOT_FOUND
        StorageException ex = assertThrows(StorageException.class, () -> template.use("non-existent-source"));
        assertEquals(StorageResultCode.STORAGE_SOURCE_NOT_FOUND.getCode(), ex.getCode());
    }
}
