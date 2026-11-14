package io.github.atengk.boot.storage.template;

import io.github.atengk.boot.core.model.PageQuery;
import io.github.atengk.boot.core.model.PageResult;
import io.github.atengk.boot.core.exception.BusinessException;
import io.github.atengk.boot.storage.driver.LocalDriver;
import io.github.atengk.boot.storage.exception.StorageException;
import io.github.atengk.boot.storage.exception.StorageResultCode;
import io.github.atengk.boot.storage.model.HashAlgorithm;
import io.github.atengk.boot.storage.model.PresignedUrl;
import io.github.atengk.boot.storage.model.StorageObject;
import io.github.atengk.boot.storage.model.StorageUploadRequest;
import io.github.atengk.boot.storage.resource.StorageResource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

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
    @DisplayName("通过 StorageTemplate 上传并携带用户自定义元数据")
    void testUploadWithUserMetadata() {
        byte[] data = "Hello Metadata".getBytes(StandardCharsets.UTF_8);
        Map<String, String> userMetadata = Map.of("creator", "Ateng", "classification", "confidential");

        StorageObject obj = template.upload("meta/file.txt", new ByteArrayInputStream(data), "text/plain", data.length, userMetadata);
        assertNotNull(obj);
        assertEquals("meta/file.txt", obj.getPath());
        assertNotNull(obj.getUserMetadata());
        assertEquals("Ateng", obj.getUserMetadata().get("creator"));
        assertEquals("confidential", obj.getUserMetadata().get("classification"));
    }

    @Test
    @DisplayName("通过 StorageTemplate 上传 MultipartFile 并携带用户自定义元数据")
    void testUploadMultipartFileWithUserMetadata() throws Exception {
        byte[] bytes = new byte[]{1, 2, 3};
        MockMultipartFile file = new MockMultipartFile("file", "doc.pdf", "application/pdf", bytes);
        Map<String, String> userMetadata = Map.of("department", "dev", "version", "v1.0");

        StorageObject obj = template.upload("docs/", file, userMetadata);
        assertNotNull(obj);
        assertEquals("docs/doc.pdf", obj.getPath());
        assertNotNull(obj.getUserMetadata());
        assertEquals("dev", obj.getUserMetadata().get("department"));
        assertEquals("v1.0", obj.getUserMetadata().get("version"));
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
    @DisplayName("通过 StorageTemplate 执行前缀批量删除与目录级联删除")
    void testDeletePrefixAndDirectory() {
        template.uploadString("cleanup/2026/01.log", "log1");
        template.uploadString("cleanup/2026/02.log", "log2");
        template.uploadString("cleanup/2026/sub/03.log", "log3");
        template.uploadString("cleanup/keep.txt", "keep");

        // 1. 前缀删除 cleanup/2026/
        boolean deleted = template.deletePrefix("cleanup/2026/");
        assertTrue(deleted);
        assertFalse(template.exists("cleanup/2026/01.log"));
        assertFalse(template.exists("cleanup/2026/02.log"));
        assertFalse(template.exists("cleanup/2026/sub/03.log"));
        assertTrue(template.exists("cleanup/keep.txt"));

        // 2. 目录删除 cleanup
        boolean deletedDir = template.deleteDirectory("cleanup");
        assertTrue(deletedDir);
        assertFalse(template.exists("cleanup/keep.txt"));

        // 3. 防误删熔断：根路径或空路径抛出异常
        assertThrows(StorageException.class, () -> template.deletePrefix("/"));
        assertThrows(StorageException.class, () -> template.deleteDirectory(""));
    }

    @Test
    @DisplayName("通过 StorageTemplate 执行孤儿分片会话超时清理")
    void testCleanExpiredChunkSessions() throws Exception {
        String uploadId = template.initiateChunkedUpload("chunk-orphan/sample.bin");
        Path chunkDir = tempDir.resolve(".chunks").resolve(uploadId);
        assertTrue(Files.exists(chunkDir));

        // 模拟 48 小时前修改的超时分片
        Files.setLastModifiedTime(chunkDir, FileTime.from(Instant.now().minus(Duration.ofHours(48))));

        // 清理超过 24 小时的孤儿分片
        int cleaned = template.cleanExpiredChunkSessions(Duration.ofHours(24));
        assertEquals(1, cleaned);
        assertFalse(Files.exists(chunkDir));

        // 再次清理已无碎片
        int secondClean = template.cleanExpiredChunkSessions(Duration.ofHours(24));
        assertEquals(0, secondClean);
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

    @Test
    @DisplayName("通过 StorageTemplate 门面获取分片预签名直传外链")
    void testTemplateGetPresignedUploadPartUrl() {
        String path = "chunked/large.zip";
        String uploadId = template.initiateChunkedUpload(path);

        PresignedUrl presigned = template.getPresignedUploadPartUrl(path, uploadId, 1, Duration.ofMinutes(15));
        assertNotNull(presigned);
        assertEquals("PUT", presigned.getHttpMethod());
        assertTrue(presigned.getUrl().contains("uploadId=" + uploadId));
        assertTrue(presigned.getUrl().contains("chunkIndex=1"));
    }

    @Test
    @DisplayName("通过 StorageTemplate 门面流式计算对象散列并支持秒传")
    void testCalculateHashAndFastUploadSupport() {
        String data = "123456789";
        template.uploadString("fast-upload/data.txt", data);

        // 默认计算 SHA-256
        String sha256 = template.calculateHash("fast-upload/data.txt");
        assertEquals("15e2b0d3c33891ebb0f1ef609ec419420c20e320ce94c65fbc8c3312448eb225", sha256);

        // 指定算法 MD5
        String md5 = template.calculateHash("fast-upload/data.txt", HashAlgorithm.MD5);
        assertEquals("25f9e794323b453885f5181f1b624d0b", md5);

        // 联动 copy 支撑秒传（同一哈希文件极速复制至用户业务路径，无需重复传输与落盘）
        boolean fastUploaded = template.copy("fast-upload/data.txt", "user1001/files/sample.txt");
        assertTrue(fastUploaded);
        assertTrue(template.exists("user1001/files/sample.txt"));
        assertEquals(sha256, template.calculateHash("user1001/files/sample.txt"));
    }

    @Test
    @DisplayName("单参快捷 upload(MultipartFile) 自动按日期归档生成唯一安全路径并支持 use(source) 切换")
    void testQuickUploadMultipartFileSingleArg(@TempDir Path archiveDir) {
        byte[] content = "Quick Single Arg Multipart Content".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile(
                "file", "../evil/report-2026.pdf", "application/pdf", content);

        StorageObject primaryObj = template.upload(file);
        assertNotNull(primaryObj);
        assertTrue(primaryObj.getPath().matches("^\\d{4}/\\d{2}/\\d{2}/[a-f0-9]{32}\\.pdf$"),
                "自动归档路径格式不符合预期: " + primaryObj.getPath());
        assertArrayEquals(content, template.downloadBytes(primaryObj.getPath()));

        // 无扩展名时同样生成日期归档路径
        MockMultipartFile noExtFile = new MockMultipartFile("file", "README", "text/plain", "raw".getBytes(StandardCharsets.UTF_8));
        StorageObject noExtObj = template.upload(noExtFile);
        assertTrue(noExtObj.getPath().matches("^\\d{4}/\\d{2}/\\d{2}/[a-f0-9]{32}$"));

        // 通过 use(sourceKey) 调用单参快捷上传，落盘至命名副源
        LocalDriver archiveDriver = new LocalDriver(archiveDir.toString());
        StorageTemplate multiTemplate = new StorageTemplate(driver, Map.of("primary", driver, "archive", archiveDriver));
        StorageObject archiveObj = multiTemplate.use("archive").upload(
                new MockMultipartFile("file", "contract.txt", "text/plain", "Archive Data".getBytes(StandardCharsets.UTF_8)));
        assertTrue(archiveDriver.exists(archiveObj.getPath()));
        assertFalse(driver.exists(archiveObj.getPath()));

        // 空文件防御
        StorageException nullEx = assertThrows(StorageException.class, () -> template.upload((MockMultipartFile) null));
        assertEquals(StorageResultCode.UPLOAD_FAILED, nullEx.getResultCode());
        MockMultipartFile emptyFile = new MockMultipartFile("file", "empty.txt", "text/plain", new byte[0]);
        StorageException emptyEx = assertThrows(StorageException.class, () -> template.upload(emptyFile));
        assertEquals(StorageResultCode.UPLOAD_FAILED, emptyEx.getResultCode());
    }

    @Test
    @DisplayName("uploadIfAbsent 三类重载在路径不存在时成功落盘，路径已存在时抛出 40024 且不覆盖原内容")
    void testUploadIfAbsentPreventsOverwrite() {
        String path1 = "lock/multipart-doc.txt";
        MockMultipartFile firstMp = new MockMultipartFile("file", "doc.txt", "text/plain", "Original Multipart".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile secondMp = new MockMultipartFile("file", "doc.txt", "text/plain", "Overwritten Multipart".getBytes(StandardCharsets.UTF_8));

        StorageObject created1 = template.uploadIfAbsent(firstMp, path1);
        assertEquals(path1, created1.getPath());

        BusinessException ex1 = assertThrows(BusinessException.class, () -> template.uploadIfAbsent(secondMp, path1));
        assertEquals(40024, ex1.getCode());
        assertEquals("Original Multipart", template.downloadString(path1));

        // InputStream 重载
        String path2 = "lock/stream-doc.txt";
        byte[] origBytes = "Original Stream".getBytes(StandardCharsets.UTF_8);
        byte[] newBytes = "Overwritten Stream".getBytes(StandardCharsets.UTF_8);
        StorageObject created2 = template.uploadIfAbsent(new ByteArrayInputStream(origBytes), path2, "text/plain", origBytes.length);
        assertEquals(path2, created2.getPath());

        BusinessException ex2 = assertThrows(BusinessException.class, () ->
                template.uploadIfAbsent(new ByteArrayInputStream(newBytes), path2, "text/plain", newBytes.length));
        assertEquals(40024, ex2.getCode());
        assertEquals("Original Stream", template.downloadString(path2));

        // StorageUploadRequest 重载
        String path3 = "lock/request-doc.json";
        byte[] jsonBytes = "{\"v\":1}".getBytes(StandardCharsets.UTF_8);
        StorageUploadRequest req = StorageUploadRequest.builder()
                .path(path3)
                .inputStream(new ByteArrayInputStream(jsonBytes))
                .contentType("application/json")
                .size(jsonBytes.length)
                .userMetadata(Map.of("env", "prod"))
                .build();
        StorageObject created3 = template.uploadIfAbsent(req);
        assertEquals("application/json", created3.getContentType());
        assertEquals("prod", created3.getUserMetadata().get("env"));

        StorageUploadRequest duplicateReq = StorageUploadRequest.builder()
                .path(path3)
                .inputStream(new ByteArrayInputStream("{\"v\":2}".getBytes(StandardCharsets.UTF_8)))
                .contentType("application/json")
                .size(7L)
                .build();
        BusinessException ex3 = assertThrows(BusinessException.class, () -> template.uploadIfAbsent(duplicateReq));
        assertEquals(40024, ex3.getCode());
        assertEquals("{\"v\":1}", template.downloadString(path3));
    }

    @Test
    @DisplayName("copyTo 与 moveTo 支持跨驱动传输元数据/流并支持同驱动直连短路")
    void testCrossSourceCopyToAndMoveToWithSameDriverShortCircuit(@TempDir Path archiveDir, @TempDir Path spyDir) {
        LocalDriver archiveDriver = new LocalDriver(archiveDir.toString());
        StorageTemplate multiTemplate = new StorageTemplate(driver, Map.of("primary", driver, "archive", archiveDriver));

        byte[] payload = "{\"report\":\"2026\"}".getBytes(StandardCharsets.UTF_8);
        String srcPath = "src/report.bin";
        multiTemplate.upload(srcPath, new ByteArrayInputStream(payload), "application/vnd.custom+json", payload.length, Map.of("author", "ateng", "tier", "hot"));

        // 1. 跨存储源 copyTo：目标内容、声明 MIME 与自定义元数据完整保留，源文件仍存在
        StorageObject copied = multiTemplate.copyTo(srcPath, "archive", "backup/report.bin");
        assertEquals("backup/report.bin", copied.getPath());
        assertEquals(payload.length, copied.getSize());
        assertEquals("application/vnd.custom+json", copied.getContentType());
        assertEquals("ateng", copied.getUserMetadata().get("author"));
        assertEquals("hot", copied.getUserMetadata().get("tier"));
        assertArrayEquals(payload, multiTemplate.use("archive").downloadBytes("backup/report.bin"));
        assertTrue(multiTemplate.exists(srcPath));

        // 2. 跨存储源 moveTo：目标落盘后才删除源文件
        StorageObject moved = multiTemplate.moveTo(srcPath, "archive", "moved/report.bin");
        assertEquals("moved/report.bin", moved.getPath());
        assertEquals("application/vnd.custom+json", moved.getContentType());
        assertEquals("ateng", moved.getUserMetadata().get("author"));
        assertArrayEquals(payload, multiTemplate.use("archive").downloadBytes("moved/report.bin"));
        assertFalse(multiTemplate.exists(srcPath));

        // 2b. 从已绑定副源句柄反向 copyTo 至 primary（driverMap-only 模式无 factory 下仍能准确寻址根主源）
        StorageObject restored = multiTemplate.use("archive").copyTo("moved/report.bin", "primary", "restored/report.bin");
        assertEquals("restored/report.bin", restored.getPath());
        assertArrayEquals(payload, multiTemplate.downloadBytes("restored/report.bin"));

        // 3. 异常防卫：不存在的 targetSourceKey 或源对象不存在
        StorageException badSource = assertThrows(StorageException.class, () ->
                multiTemplate.copyTo("moved/report.bin", "unknown-src", "x/y.bin"));
        assertEquals(StorageResultCode.STORAGE_SOURCE_NOT_FOUND, badSource.getResultCode());

        StorageException missingObj = assertThrows(StorageException.class, () ->
                multiTemplate.moveTo("not/exists.bin", "archive", "x/y.bin"));
        assertEquals(StorageResultCode.OBJECT_NOT_FOUND, missingObj.getResultCode());

        // 4. 同驱动引用直连短路：直接调用 driver.copy / driver.move，不走 download 流回传
        LocalDriver realSameDriver = new LocalDriver(spyDir.toString());
        LocalDriver spyDriver = org.mockito.Mockito.spy(realSameDriver);
        StorageTemplate aliasTemplate = new StorageTemplate(spyDriver, Map.of("primary", spyDriver, "alias", spyDriver));
        aliasTemplate.uploadString("same/a.txt", "same driver content");
        org.mockito.Mockito.clearInvocations(spyDriver);

        StorageObject shortCopy = aliasTemplate.copyTo("same/a.txt", "alias", "same/b.txt");
        assertEquals("same/b.txt", shortCopy.getPath());
        org.mockito.Mockito.verify(spyDriver).copy("same/a.txt", "same/b.txt");
        org.mockito.Mockito.verify(spyDriver, org.mockito.Mockito.never()).download(org.mockito.ArgumentMatchers.anyString());

        StorageObject shortMove = aliasTemplate.moveTo("same/b.txt", "alias", "same/c.txt");
        assertEquals("same/c.txt", shortMove.getPath());
        org.mockito.Mockito.verify(spyDriver).move("same/b.txt", "same/c.txt");
        org.mockito.Mockito.verify(spyDriver, org.mockito.Mockito.never()).download(org.mockito.ArgumentMatchers.anyString());
        assertFalse(aliasTemplate.exists("same/b.txt"));
        assertEquals("same driver content", aliasTemplate.downloadString("same/c.txt"));
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
