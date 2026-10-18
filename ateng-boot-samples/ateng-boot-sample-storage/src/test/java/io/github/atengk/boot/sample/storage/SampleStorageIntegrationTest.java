package io.github.atengk.boot.sample.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.sample.storage.listener.SampleStorageEventListener;
import io.github.atengk.boot.sample.storage.service.FileStorageDemoService;
import io.github.atengk.boot.storage.resource.StorageResource;
import io.github.atengk.boot.storage.template.StorageTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 对象存储样例工程端到端集成测试
 * 覆盖：
 * 1. MultipartFile 表单上传与 Result 契约
 * 2. 文件流式下载与标准响应头
 * 3. HTTP Range 206 断点续传部分内容自验
 * 4. HMAC 预签名外链访问与防篡改/防过期时效闭环
 * 5. @Storage 与 storageTemplate.use(...) 动态多源物理路径隔离自验
 * 6. 对象复移 (copy/move) 与删除天然幂等
 * 7. 目录列举 (list) 与分页检索 (listPage)
 * 8. 便捷存取 (String/JSON)
 * 9. 多文件流式打包 ZIP 下载 (downloadAsZip)
 * 10. 大文件分片上传完整生命周期 (initiate/upload/complete/abort) 与缺片防御
 * 11. StorageGuard 文件魔数嗅探与伪装文件拦截
 * 12. StorageUploadedEvent / StorageDeletedEvent 领域事件监听
 *
 * @author Ateng
 * @since 2026-10-17
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("ateng-boot-sample-storage 对象存储自验集成门禁测试")
class SampleStorageIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StorageTemplate storageTemplate;

    @Autowired
    private FileStorageDemoService fileStorageDemoService;

    @Autowired
    private SampleStorageEventListener sampleStorageEventListener;

    private static final Path DEFAULT_DIR = Path.of("target/sample-storage/default");
    private static final Path BACKUP_DIR = Path.of("target/sample-storage/backup");

    @BeforeEach
    void setUp() throws Exception {
        // 清理并确保测试目录准备就绪
        org.springframework.util.FileSystemUtils.deleteRecursively(DEFAULT_DIR);
        org.springframework.util.FileSystemUtils.deleteRecursively(BACKUP_DIR);
        Files.createDirectories(DEFAULT_DIR);
        Files.createDirectories(BACKUP_DIR);

        // 重置领域事件计数器
        sampleStorageEventListener.reset();
    }

    @Test
    @DisplayName("端到端测试 1：表单上传文件，验证 HTTP 200、Result<StorageObject> 契约与物理文件落盘")
    void testUploadFile() throws Exception {
        byte[] fileBytes = "Hello Ateng Boot Object Storage!".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "hello.txt",
                "text/plain",
                fileBytes
        );

        mockMvc.perform(multipart("/api/storage/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.path").value("hello.txt"))
                .andExpect(jsonPath("$.data.size").value(fileBytes.length))
                .andExpect(jsonPath("$.data.contentType").value("text/plain"));

        // 验证物理磁盘文件已落盘
        Path targetPath = DEFAULT_DIR.resolve("hello.txt");
        assertThat(Files.exists(targetPath)).isTrue();
        assertThat(Files.readAllBytes(targetPath)).isEqualTo(fileBytes);

        // 验证事件广播已记录
        assertThat(sampleStorageEventListener.getUploadCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("端到端测试 2：文件流式下载，验证 Content-Disposition、Content-Type 与响应内容一致性")
    void testDownloadFile() throws Exception {
        // 先写入测试文件
        String content = "Downloadable Content for E2E Test";
        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        storageTemplate.upload("download-test.txt", new ByteArrayInputStream(contentBytes), "text/plain", contentBytes.length);

        mockMvc.perform(get("/api/storage/download").param("path", "download-test.txt"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, org.hamcrest.Matchers.containsString("download-test.txt")))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, org.hamcrest.Matchers.containsString("text/plain")))
                .andExpect(content().string(content));
    }

    @Test
    @DisplayName("端到端测试 3：HTTP Range 断点续传，验证 206 Partial Content 与指定分片内容")
    void testHttpRangePartialContentDownload() throws Exception {
        // 原始文本共 32 字节: "Hello Ateng Boot Object Storage!"
        String raw = "Hello Ateng Boot Object Storage!";
        byte[] rawBytes = raw.getBytes(StandardCharsets.UTF_8);
        storageTemplate.upload("range-test.txt", new ByteArrayInputStream(rawBytes), "text/plain", rawBytes.length);

        // 请求前 5 个字节 [0-4] -> "Hello"
        mockMvc.perform(get("/api/storage/download")
                        .param("path", "range-test.txt")
                        .header("Range", "bytes=0-4"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 0-4/32"))
                .andExpect(content().string("Hello"));

        // 请求中间字节 [6-10] -> "Ateng"
        mockMvc.perform(get("/api/storage/download")
                        .param("path", "range-test.txt")
                        .header("Range", "bytes=6-10"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 6-10/32"))
                .andExpect(content().string("Ateng"));
    }

    @Test
    @DisplayName("端到端测试 4：HMAC 预签名外链端点，验证正常授权下载与签名篡改拦截（HTTP 403）")
    void testHmacPresignedUrlAndTamperDefense() throws Exception {
        // 准备文件
        String secretData = "TopSecretData-MustBeProtected";
        byte[] dataBytes = secretData.getBytes(StandardCharsets.UTF_8);
        storageTemplate.upload("secure/data.txt", new ByteArrayInputStream(dataBytes), "text/plain", dataBytes.length);

        // 获取预签名外链
        MvcResult presignedResult = mockMvc.perform(get("/api/storage/presigned-url")
                        .param("path", "secure/data.txt")
                        .param("durationSeconds", "60"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.url").isNotEmpty())
                .andReturn();

        String responseJson = presignedResult.getResponse().getContentAsString();
        JsonNode jsonNode = objectMapper.readTree(responseJson);
        String fullUrl = jsonNode.get("data").get("url").asText();
        assertThat(fullUrl).contains("/storage/local/download");

        // 解析 URL 中的相对路径及 query 参数用于 MockMvc 模拟外网下载
        int queryIndex = fullUrl.indexOf('?');
        assertThat(queryIndex).isGreaterThan(0);
        String queryString = fullUrl.substring(queryIndex + 1);

        String[] pairs = queryString.split("&");
        String pathParam = null;
        String expiresParam = null;
        String signatureParam = null;
        for (String pair : pairs) {
            String[] kv = pair.split("=");
            if ("path".equals(kv[0])) pathParam = java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8);
            if ("expires".equals(kv[0])) expiresParam = kv[1];
            if ("signature".equals(kv[0])) signatureParam = kv[1];
        }

        assertThat(pathParam).isEqualTo("secure/data.txt");
        assertThat(expiresParam).isNotNull();
        assertThat(signatureParam).isNotNull();

        // 1. 使用有效预签名凭证访问受保护端点 -> HTTP 200
        mockMvc.perform(get("/storage/local/download")
                        .param("path", pathParam)
                        .param("expires", expiresParam)
                        .param("signature", signatureParam))
                .andExpect(status().isOk())
                .andExpect(content().string(secretData));

        // 2. 篡改签名 -> HTTP 403 Forbidden
        mockMvc.perform(get("/storage/local/download")
                        .param("path", pathParam)
                        .param("expires", expiresParam)
                        .param("signature", "tampered-invalid-signature-123456"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("端到端测试 5：@Storage 注解与 storageTemplate.use(...) 动态多源物理路径隔离自验")
    void testDynamicMultiSourcePhysicalIsolation() throws Exception {
        String filename = "multi-source-test.txt";

        // 1. 上传到默认存储源
        byte[] defaultBytes = "Default Source Payload".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile defaultFile = new MockMultipartFile("file", filename, "text/plain", defaultBytes);
        mockMvc.perform(multipart("/api/storage/upload").file(defaultFile))
                .andExpect(status().isOk());

        // 2. 通过 @Storage("backup") 上传到备份源
        byte[] backupBytes = "Backup Source Isolated Payload".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile backupFile = new MockMultipartFile("file", filename, "text/plain", backupBytes);
        mockMvc.perform(multipart("/api/storage/upload-backup").file(backupFile))
                .andExpect(status().isOk());

        // 3. 校验物理文件隔离情况
        Path defaultPhysicalFile = DEFAULT_DIR.resolve(filename);
        Path backupPhysicalFile = BACKUP_DIR.resolve(filename);

        assertThat(Files.exists(defaultPhysicalFile)).isTrue();
        assertThat(Files.exists(backupPhysicalFile)).isTrue();
        assertThat(Files.readAllBytes(defaultPhysicalFile)).isEqualTo(defaultBytes);
        assertThat(Files.readAllBytes(backupPhysicalFile)).isEqualTo(backupBytes);

        // 4. 验证编程式 use(...) 路由分别读取各自源数据
        StorageResource defaultRes = storageTemplate.getResource(filename);
        try (InputStream in = defaultRes.getInputStream()) {
            assertThat(in.readAllBytes()).isEqualTo(defaultBytes);
        }

        StorageResource backupRes = storageTemplate.use("backup").getResource(filename);
        try (InputStream in = backupRes.getInputStream()) {
            assertThat(in.readAllBytes()).isEqualTo(backupBytes);
        }
    }

    @Test
    @DisplayName("端到端测试 6：对象跨路径复制 (copy)、移动 (move) 与删除天然幂等")
    void testCopyMoveAndDeleteIdempotent() throws Exception {
        // 1. 写入源文件
        String sourcePath = "orig/source.txt";
        storageTemplate.uploadString(sourcePath, "Source File Content");
        assertThat(storageTemplate.exists(sourcePath)).isTrue();

        // 2. 复制为 target.txt
        String copyPath = "backup/target.txt";
        mockMvc.perform(post("/api/storage/copy")
                        .param("sourcePath", sourcePath)
                        .param("targetPath", copyPath))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        assertThat(storageTemplate.exists(sourcePath)).isTrue();
        assertThat(storageTemplate.exists(copyPath)).isTrue();
        assertThat(storageTemplate.downloadString(copyPath)).isEqualTo("Source File Content");

        // 3. 移动 target.txt 为 moved.txt
        String movedPath = "final/moved.txt";
        mockMvc.perform(post("/api/storage/move")
                        .param("sourcePath", copyPath)
                        .param("targetPath", movedPath))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        assertThat(storageTemplate.exists(copyPath)).isFalse();
        assertThat(storageTemplate.exists(movedPath)).isTrue();
        assertThat(storageTemplate.downloadString(movedPath)).isEqualTo("Source File Content");

        // 4. 删除 moved.txt
        mockMvc.perform(post("/api/storage/delete")
                        .param("path", movedPath))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        assertThat(storageTemplate.exists(movedPath)).isFalse();

        // 5. 验证删除天然幂等（再次删除已不存在的对象静默返回 true）
        mockMvc.perform(post("/api/storage/delete")
                        .param("path", movedPath))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        // 验证删除事件记录
        assertThat(sampleStorageEventListener.getDeleteCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("端到端测试 7：目录前缀平铺列举 (list) 与基于 PageResult 的分页检索 (listPage)")
    void testListAndListPage() throws Exception {
        // 准备树状目录文件
        storageTemplate.uploadString("reports/2026/01.txt", "Report Jan");
        storageTemplate.uploadString("reports/2026/02.txt", "Report Feb");
        storageTemplate.uploadString("reports/2026/sub/03.txt", "Report Mar");

        // 1. 非递归列举 reports/2026/
        mockMvc.perform(get("/api/storage/list")
                        .param("prefix", "reports/2026/")
                        .param("recursive", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(3)); // 01.txt, 02.txt, sub/ 虚拟目录

        // 2. 递归列举 reports/2026/
        mockMvc.perform(get("/api/storage/list")
                        .param("prefix", "reports/2026/")
                        .param("recursive", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(3)); // 01.txt, 02.txt, sub/03.txt 递归扁平文件

        // 3. 分页检索：current=1, size=2, recursive=true
        mockMvc.perform(get("/api/storage/list-page")
                        .param("prefix", "reports/2026/")
                        .param("current", "1")
                        .param("size", "2")
                        .param("recursive", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.records.length()").value(2))
                .andExpect(jsonPath("$.data.pages").value(2));

        // 4. 第二页：current=2, size=2
        mockMvc.perform(get("/api/storage/list-page")
                        .param("prefix", "reports/2026/")
                        .param("current", "2")
                        .param("size", "2")
                        .param("recursive", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.records.length()").value(1));
    }

    @Test
    @DisplayName("端到端测试 8：便捷读写存取（writeString/readString 与 writeJson/readJson）")
    void testConvenientStringAndJson() throws Exception {
        // 1. 便捷写入与读取纯文本
        String textPath = "convenient/note.txt";
        mockMvc.perform(post("/api/storage/write-string")
                        .param("path", textPath)
                        .param("content", "Convenient Plain Text"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.path").value(textPath));

        mockMvc.perform(get("/api/storage/read-string")
                        .param("path", textPath))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("Convenient Plain Text"));

        // 2. 便捷写入与读取 JSON 对象
        String jsonPath = "convenient/user.json";
        String payloadJson = "{\"username\":\"ateng\",\"role\":\"architect\",\"age\":30}";

        mockMvc.perform(post("/api/storage/write-json")
                        .param("path", jsonPath)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payloadJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.path").value(jsonPath))
                .andExpect(jsonPath("$.data.contentType").value(org.hamcrest.Matchers.containsString("application/json")));

        mockMvc.perform(get("/api/storage/read-json")
                        .param("path", jsonPath))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.username").value("ateng"))
                .andExpect(jsonPath("$.data.role").value("architect"))
                .andExpect(jsonPath("$.data.age").value(30));
    }

    @Test
    @DisplayName("端到端测试 9：多文件纯流式打包 ZIP 响应下载 (downloadAsZip) 并验证解压内容")
    void testDownloadAsZip() throws Exception {
        // 准备打包测试文件
        storageTemplate.uploadString("bundle/doc1.txt", "Document 1 Content");
        storageTemplate.uploadString("bundle/doc2.txt", "Document 2 Content");

        MvcResult zipResult = mockMvc.perform(get("/api/storage/download-zip")
                        .param("paths", "bundle/doc1.txt,bundle/doc2.txt")
                        .param("zipFilename", "export.zip"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, org.hamcrest.Matchers.containsString("application/zip")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, org.hamcrest.Matchers.containsString("export.zip")))
                .andReturn();

        byte[] zipBytes = zipResult.getResponse().getContentAsByteArray();
        assertThat(zipBytes).isNotEmpty();

        // 解析并验证 ZIP 压缩包内部 Entry 条目与数据
        Map<String, String> extractedEntries = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                byte[] entryBytes = zis.readAllBytes();
                extractedEntries.put(entry.getName(), new String(entryBytes, StandardCharsets.UTF_8));
                zis.closeEntry();
            }
        }

        assertThat(extractedEntries).hasSize(2);
        assertThat(extractedEntries.get("bundle/doc1.txt")).isEqualTo("Document 1 Content");
        assertThat(extractedEntries.get("bundle/doc2.txt")).isEqualTo("Document 2 Content");
    }

    @Test
    @DisplayName("端到端测试 10：大文件分片合并暂存完整生命周期（初始化、逐片上传、合并落盘与缺片防御）")
    void testChunkedUploadLifecycleAndValidation() throws Exception {
        String targetPath = "chunks/large-file.bin";

        // 1. 初始化分片会话
        MvcResult initResult = mockMvc.perform(post("/api/storage/chunk/initiate")
                        .param("path", targetPath))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isNotEmpty())
                .andReturn();

        String uploadId = objectMapper.readTree(initResult.getResponse().getContentAsString())
                .get("data").asText();
        assertThat(uploadId).isNotBlank();

        // 2. 上传第 1 分片（500 字节）
        byte[] chunk1 = new byte[500];
        for (int i = 0; i < 500; i++) chunk1[i] = (byte) 1;
        MockMultipartFile part1 = new MockMultipartFile("file", "part1.bin", "application/octet-stream", chunk1);

        mockMvc.perform(multipart("/api/storage/chunk/upload")
                        .file(part1)
                        .param("uploadId", uploadId)
                        .param("chunkIndex", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 3. 故意尝试以 totalChunks=2 合并（此时缺少第 2 片），验证 STORAGE_CHUNK_MISSING (40012)
        mockMvc.perform(post("/api/storage/chunk/complete")
                        .param("uploadId", uploadId)
                        .param("path", targetPath)
                        .param("totalChunks", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40012));

        // 4. 上传第 2 分片（500 字节）
        byte[] chunk2 = new byte[500];
        for (int i = 0; i < 500; i++) chunk2[i] = (byte) 2;
        MockMultipartFile part2 = new MockMultipartFile("file", "part2.bin", "application/octet-stream", chunk2);

        mockMvc.perform(multipart("/api/storage/chunk/upload")
                        .file(part2)
                        .param("uploadId", uploadId)
                        .param("chunkIndex", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 5. 再次合并（totalChunks=2），校验合并成功
        mockMvc.perform(post("/api/storage/chunk/complete")
                        .param("uploadId", uploadId)
                        .param("path", targetPath)
                        .param("totalChunks", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.path").value(targetPath))
                .andExpect(jsonPath("$.data.size").value(1000));

        // 6. 验证物理文件落盘大小与内容正序无误
        byte[] mergedBytes = storageTemplate.downloadBytes(targetPath);
        assertThat(mergedBytes).hasSize(1000);
        assertThat(mergedBytes[0]).isEqualTo((byte) 1);
        assertThat(mergedBytes[499]).isEqualTo((byte) 1);
        assertThat(mergedBytes[500]).isEqualTo((byte) 2);
        assertThat(mergedBytes[999]).isEqualTo((byte) 2);

        // 7. 验证分片中止清理 (abort)
        MvcResult initAbortResult = mockMvc.perform(post("/api/storage/chunk/initiate")
                        .param("path", "chunks/aborted.bin"))
                .andExpect(status().isOk())
                .andReturn();
        String abortUploadId = objectMapper.readTree(initAbortResult.getResponse().getContentAsString()).get("data").asText();

        mockMvc.perform(multipart("/api/storage/chunk/upload")
                        .file(part1)
                        .param("uploadId", abortUploadId)
                        .param("chunkIndex", "1"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/storage/chunk/abort")
                        .param("uploadId", abortUploadId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 临时分片目录应已彻底清理
        Path chunkDir = DEFAULT_DIR.resolve(".chunks").resolve(abortUploadId);
        assertThat(Files.exists(chunkDir)).isFalse();
    }

    @Test
    @DisplayName("端到端测试 11：StorageGuard 文件魔数嗅探防伪装拦截（STORAGE_FILE_TYPE_MISMATCH 40010）")
    void testStorageGuardCamouflageDefense() throws Exception {
        // 伪装文件：扩展名为 .png，但内部字节为纯文本 "This is plain text pretending to be PNG"
        byte[] fakeBytes = "This is plain text pretending to be PNG".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile fakePng = new MockMultipartFile(
                "file",
                "hacker_avatar.png",
                "image/png",
                fakeBytes
        );

        // 上传伪装文件 -> 预期被 StorageGuard 拦截并返回业务错误码 40010
        mockMvc.perform(multipart("/api/storage/upload").file(fakePng))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40010))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("魔数")));
    }

    @Test
    @DisplayName("端到端测试 12：多文件打包 ZIP 包含重复源路径时自动重命名生成两份条目")
    void testDownloadZipWithDuplicatePaths() throws Exception {
        storageTemplate.uploadString("dups/doc.txt", "Duplicate Template Content");

        MvcResult result = mockMvc.perform(get("/api/storage/download-zip")
                        .param("paths", "dups/doc.txt", "dups/doc.txt")
                        .param("zipFilename", "dup_bundle.zip"))
                .andExpect(status().isOk())
                .andReturn();

        byte[] zipBytes = result.getResponse().getContentAsByteArray();
        Map<String, byte[]> unzipped = new HashMap<>();
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes), StandardCharsets.UTF_8)) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (!entry.isDirectory()) {
                    unzipped.put(entry.getName(), zis.readAllBytes());
                }
                zis.closeEntry();
            }
        }
        assertThat(unzipped).hasSize(2);
        assertThat(unzipped).containsKey("dups/doc.txt");
        assertThat(unzipped).containsKey("dups/doc (1).txt");
    }

    @Test
    @DisplayName("端到端测试 13：Controller 传输边界 JSR-303 参数校验拦截")
    void testValidationConstraintDefense() throws Exception {
        // copy 端点缺少 sourcePath (空白)
        mockMvc.perform(post("/api/storage/copy")
                        .param("sourcePath", "   ")
                        .param("targetPath", "target.txt"))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(200)));
    }
}
