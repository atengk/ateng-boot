package io.github.atengk.boot.storage.template;

import io.github.atengk.boot.core.model.PageQuery;
import io.github.atengk.boot.core.model.PageResult;
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

    @Test
    @DisplayName("通过 StorageTemplate 执行文件复制与移动")
    void testCopyAndMove() {
        template.uploadString("source/original.txt", "Original Data");
        assertTrue(template.exists("source/original.txt"));

        // 复制
        boolean copied = template.copy("source/original.txt", "backup/original_bak.txt");
        assertTrue(copied);
        assertTrue(template.exists("source/original.txt"));
        assertEquals("Original Data", template.downloadString("backup/original_bak.txt"));

        // 移动
        boolean moved = template.move("source/original.txt", "moved/target.txt");
        assertTrue(moved);
        assertFalse(template.exists("source/original.txt"));
        assertEquals("Original Data", template.downloadString("moved/target.txt"));
    }

    @Test
    @DisplayName("通过 StorageTemplate 便捷上传与读取纯文本字符串")
    void testUploadAndDownloadString() {
        String content = "Hello UTF-8 Content: 工业级存储 Starter";
        String path = "text/sample.txt";

        StorageObject obj = template.uploadString(path, content);
        assertNotNull(obj);
        assertEquals(path, obj.getPath());
        assertEquals("text/plain;charset=UTF-8", obj.getContentType());

        String downloaded = template.downloadString(path);
        assertEquals(content, downloaded);
    }

    @Test
    @DisplayName("通过 StorageTemplate 便捷上传与读取字节数组")
    void testUploadAndDownloadBytes() {
        byte[] bytes = new byte[]{10, 20, 30, 40, 50};
        String path = "bytes/sample.bin";

        StorageObject obj = template.uploadBytes(path, bytes, "application/octet-stream");
        assertNotNull(obj);
        assertEquals(path, obj.getPath());
        assertEquals(bytes.length, obj.getSize());

        byte[] downloaded = template.downloadBytes(path);
        assertArrayEquals(bytes, downloaded);
    }

    @Test
    @DisplayName("通过 StorageTemplate 便捷上传与读取 JSON POJO")
    void testUploadAndDownloadJson() {
        TestUser user = new TestUser("Ateng", 18);
        String path = "json/user.json";

        StorageObject obj = template.uploadJson(path, user);
        assertNotNull(obj);
        assertEquals(path, obj.getPath());
        assertEquals("application/json;charset=UTF-8", obj.getContentType());

        TestUser downloaded = template.downloadJson(path, TestUser.class);
        assertNotNull(downloaded);
        assertEquals("Ateng", downloaded.getName());
        assertEquals(18, downloaded.getAge());
    }

    @Test
    @DisplayName("通过 StorageTemplate 门面列举对象并支持分页切片")
    void testListAndListPage() {
        for (int i = 1; i <= 5; i++) {
            template.uploadString("page/doc-" + i + ".txt", "Doc content " + i);
        }

        // 1. 平铺列举
        List<StorageObject> items = template.list("page");
        assertEquals(5, items.size());

        // 2. 分页第 1 页 (page=1, size=2)
        PageResult<StorageObject> page1 = template.listPage("page", new PageQuery(1, 2));
        assertNotNull(page1);
        assertEquals(5, page1.getTotal());
        assertEquals(1, page1.getCurrent());
        assertEquals(2, page1.getSize());
        assertEquals(3, page1.getPages());
        assertEquals(2, page1.getRecords().size());
        assertEquals("page/doc-1.txt", page1.getRecords().get(0).getPath());
        assertEquals("page/doc-2.txt", page1.getRecords().get(1).getPath());

        // 3. 分页第 3 页 (page=3, size=2)
        PageResult<StorageObject> page3 = template.listPage("page", new PageQuery(3, 2));
        assertNotNull(page3);
        assertEquals(5, page3.getTotal());
        assertEquals(3, page3.getCurrent());
        assertEquals(1, page3.getRecords().size());
        assertEquals("page/doc-5.txt", page3.getRecords().get(0).getPath());

        // 4. 超出范围的分页 (page=4, size=2)
        PageResult<StorageObject> page4 = template.listPage("page", new PageQuery(4, 2));
        assertNotNull(page4);
        assertEquals(5, page4.getTotal());
        assertTrue(page4.getRecords().isEmpty());
    }

    public static class TestUser {
        private String name;
        private Integer age;

        public TestUser() {
        }

        public TestUser(String name, Integer age) {
            this.name = name;
            this.age = age;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public Integer getAge() {
            return age;
        }

        public void setAge(Integer age) {
            this.age = age;
        }
    }
}
