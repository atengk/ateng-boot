package io.github.atengk.boot.sample.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.sample.storage.config.SampleStorageTestS3Config;
import io.github.atengk.boot.sample.storage.listener.SampleStorageEventListener;
import io.github.atengk.boot.storage.context.StorageContextHolder;
import io.github.atengk.boot.storage.resource.StorageResource;
import io.github.atengk.boot.storage.template.StorageTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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
@Import(SampleStorageTestS3Config.class)
@DisplayName("ateng-boot-sample-storage 对象存储自验集成门禁测试")
class SampleStorageIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StorageTemplate storageTemplate;

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

    @AfterEach
    void tearDown() throws Exception {
        StorageContextHolder.clear();
        org.springframework.util.FileSystemUtils.deleteRecursively(DEFAULT_DIR);
        org.springframework.util.FileSystemUtils.deleteRecursively(BACKUP_DIR);
    }

    @Test
    @DisplayName("端到端测试 1：表单上传电子合同，验证 HTTP 200、Result<StorageObject> 契约与物理文件落盘")
    void testUploadFile() throws Exception {
        byte[] fileBytes = "2026年度核心云基础设施采购合同正文".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "procurement-ht001.txt",
                "text/plain",
                fileBytes
        );

        mockMvc.perform(multipart("/sample/storage/object/upload")
                        .file(file)
                        .param("path", "contracts/2026/procurement-ht001.txt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.path").value("contracts/2026/procurement-ht001.txt"))
                .andExpect(jsonPath("$.data.size").value(fileBytes.length))
                .andExpect(jsonPath("$.data.contentType").value("text/plain"));

        // 验证物理磁盘文件已落盘
        Path targetPath = DEFAULT_DIR.resolve("contracts/2026/procurement-ht001.txt");
        assertThat(Files.exists(targetPath)).isTrue();
        assertThat(Files.readAllBytes(targetPath)).isEqualTo(fileBytes);

        // 验证事件广播已记录
        assertThat(sampleStorageEventListener.getUploadCount()).isGreaterThanOrEqualTo(1);
        assertThat(sampleStorageEventListener.getLastUploadedPath()).isEqualTo("contracts/2026/procurement-ht001.txt");
    }

    @Test
    @DisplayName("端到端测试 2：文件流式下载，验证 Content-Disposition、Content-Type 与响应内容一致性")
    void testDownloadFile() throws Exception {
        // 先写入测试文件
        String content = "Enterprise Contract Attachment Content";
        byte[] contentBytes = content.getBytes(StandardCharsets.UTF_8);
        storageTemplate.upload("contracts/attachment-01.txt", new ByteArrayInputStream(contentBytes), "text/plain", contentBytes.length);

        mockMvc.perform(get("/sample/storage/transfer/download").param("path", "contracts/attachment-01.txt"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, org.hamcrest.Matchers.containsString("attachment-01.txt")))
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, org.hamcrest.Matchers.containsString("text/plain")))
                .andExpect(content().string(content));
    }

    @Test
    @DisplayName("端到端测试 3：HTTP Range 断点续传，验证 206 Partial Content 与指定分片内容")
    void testHttpRangePartialContentDownload() throws Exception {
        // 原始文本共 32 字节: "Hello Ateng Boot Object Storage!"
        String raw = "Hello Ateng Boot Object Storage!";
        byte[] rawBytes = raw.getBytes(StandardCharsets.UTF_8);
        storageTemplate.upload("media/training-video.txt", new ByteArrayInputStream(rawBytes), "text/plain", rawBytes.length);

        // 请求前 5 个字节 [0-4] -> "Hello"
        mockMvc.perform(get("/sample/storage/transfer/download")
                        .param("path", "media/training-video.txt")
                        .header("Range", "bytes=0-4"))
                .andExpect(status().isPartialContent())
                .andExpect(header().string(HttpHeaders.CONTENT_RANGE, "bytes 0-4/32"))
                .andExpect(content().string("Hello"));

        // 请求中间字节 [6-10] -> "Ateng"
        mockMvc.perform(get("/sample/storage/transfer/download")
                        .param("path", "media/training-video.txt")
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
        MvcResult presignedResult = mockMvc.perform(get("/sample/storage/presigned/download-url")
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
        mockMvc.perform(multipart("/sample/storage/object/upload").file(defaultFile))
                .andExpect(status().isOk());

        // 2. 通过 @Storage("backup") 上传到备份源
        byte[] backupBytes = "Backup Source Isolated Payload".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile backupFile = new MockMultipartFile("file", filename, "text/plain", backupBytes);
        mockMvc.perform(multipart("/sample/storage/governance/upload-backup").file(backupFile))
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
    @DisplayName("端到端测试 6：电子合同跨路径复制 (copy)、归档移动 (move) 与删除天然幂等")
    void testCopyMoveAndDeleteIdempotent() throws Exception {
        // 1. 写入暂存区合同文件
        String sourcePath = "contracts/staging/ht-2026-002.txt";
        storageTemplate.uploadString(sourcePath, "Staging Contract Content");
        assertThat(storageTemplate.exists(sourcePath)).isTrue();

        // 2. 复制为归档副本
        String copyPath = "contracts/copy/ht-2026-002-copy.txt";
        mockMvc.perform(post("/sample/storage/object/copy")
                        .param("sourcePath", sourcePath)
                        .param("targetPath", copyPath))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        assertThat(storageTemplate.exists(sourcePath)).isTrue();
        assertThat(storageTemplate.exists(copyPath)).isTrue();
        assertThat(storageTemplate.downloadString(copyPath)).isEqualTo("Staging Contract Content");

        // 3. 移动至正式归档目录
        String movedPath = "contracts/archive/2026/ht-2026-002.txt";
        mockMvc.perform(post("/sample/storage/object/move")
                        .param("sourcePath", copyPath)
                        .param("targetPath", movedPath))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        assertThat(storageTemplate.exists(copyPath)).isFalse();
        assertThat(storageTemplate.exists(movedPath)).isTrue();
        assertThat(storageTemplate.downloadString(movedPath)).isEqualTo("Staging Contract Content");

        // 4. 删除归档文件
        mockMvc.perform(post("/sample/storage/object/delete")
                        .param("path", movedPath))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        assertThat(storageTemplate.exists(movedPath)).isFalse();

        // 5. 验证删除天然幂等（再次删除已不存在的对象静默返回 true）
        mockMvc.perform(post("/sample/storage/object/delete")
                        .param("path", movedPath))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        // 验证删除事件记录
        assertThat(sampleStorageEventListener.getDeleteCount()).isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("端到端测试 7：归档目录前缀平铺列举 (list) 与基于 PageResult 的分页检索 (listPage)")
    void testListAndListPage() throws Exception {
        // 准备树状归档目录文件
        storageTemplate.uploadString("contracts/2026/q1.txt", "Q1 Contract");
        storageTemplate.uploadString("contracts/2026/q2.txt", "Q2 Contract");
        storageTemplate.uploadString("contracts/2026/sub/q3.txt", "Q3 Sub Contract");

        // 1. 非递归列举 contracts/2026/
        mockMvc.perform(get("/sample/storage/object/list")
                        .param("prefix", "contracts/2026/")
                        .param("recursive", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(3)); // q1.txt, q2.txt, sub 虚拟目录

        // 2. 递归列举 contracts/2026/
        mockMvc.perform(get("/sample/storage/object/list")
                        .param("prefix", "contracts/2026/")
                        .param("recursive", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.length()").value(3)); // q1.txt, q2.txt, sub/q3.txt 递归扁平文件

        // 3. 分页检索：current=1, size=2, recursive=true
        mockMvc.perform(get("/sample/storage/object/list-page")
                        .param("prefix", "contracts/2026/")
                        .param("current", "1")
                        .param("size", "2")
                        .param("recursive", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.records.length()").value(2))
                .andExpect(jsonPath("$.data.pages").value(2));

        // 4. 第二页：current=2, size=2
        mockMvc.perform(get("/sample/storage/object/list-page")
                        .param("prefix", "contracts/2026/")
                        .param("current", "2")
                        .param("size", "2")
                        .param("recursive", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.records.length()").value(1));
    }

    @Test
    @DisplayName("端到端测试 8：便捷读写存取（纯文本批注与 ContractAuditSnapshot 合同审计快照 JSON）")
    void testConvenientStringAndJson() throws Exception {
        // 1. 便捷写入与读取合同审批批注纯文本
        String textPath = "contracts/remarks/ht-2026-001-remark.txt";
        mockMvc.perform(post("/sample/storage/object/write-string")
                        .param("path", textPath)
                        .param("content", "法务部已完成合规审查，准予归档"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.path").value(textPath));

        mockMvc.perform(get("/sample/storage/object/read-string")
                        .param("path", textPath))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("法务部已完成合规审查，准予归档"));

        // 2. 便捷写入与读取 ContractAuditSnapshot 强类型合同审计快照 JSON
        String jsonPath = "contracts/snapshots/ht-2026-001.json";
        String payloadJson = """
                {
                  "contractNo": "HT-2026-001",
                  "title": "2026年度核心云基础设施采购合同",
                  "signer": "Ateng",
                  "department": "procurement",
                  "auditStatus": "ARCHIVED"
                }
                """;

        mockMvc.perform(post("/sample/storage/object/write-json")
                        .param("path", jsonPath)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payloadJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.path").value(jsonPath))
                .andExpect(jsonPath("$.data.contentType").value(org.hamcrest.Matchers.containsString("application/json")));

        mockMvc.perform(get("/sample/storage/object/read-json")
                        .param("path", jsonPath))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.contractNo").value("HT-2026-001"))
                .andExpect(jsonPath("$.data.title").value("2026年度核心云基础设施采购合同"))
                .andExpect(jsonPath("$.data.signer").value("Ateng"))
                .andExpect(jsonPath("$.data.department").value("procurement"))
                .andExpect(jsonPath("$.data.auditStatus").value("ARCHIVED"));
    }

    @Test
    @DisplayName("端到端测试 9：多份财务凭证纯流式打包 ZIP 响应下载 (downloadAsZip) 并验证解压内容")
    void testDownloadAsZip() throws Exception {
        // 准备打包测试凭证文件
        storageTemplate.uploadString("vouchers/2026Q1/voucher-01.txt", "Voucher 01 Content");
        storageTemplate.uploadString("vouchers/2026Q1/voucher-02.txt", "Voucher 02 Content");

        MvcResult zipResult = mockMvc.perform(get("/sample/storage/transfer/download-zip")
                        .param("paths", "vouchers/2026Q1/voucher-01.txt,vouchers/2026Q1/voucher-02.txt")
                        .param("zipFilename", "2026Q1_vouchers.zip"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, org.hamcrest.Matchers.containsString("application/zip")))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, org.hamcrest.Matchers.containsString("2026Q1_vouchers.zip")))
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
        assertThat(extractedEntries.get("vouchers/2026Q1/voucher-01.txt")).isEqualTo("Voucher 01 Content");
        assertThat(extractedEntries.get("vouchers/2026Q1/voucher-02.txt")).isEqualTo("Voucher 02 Content");
    }

    @Test
    @DisplayName("端到端测试 10：大文件分片合并暂存完整生命周期（初始化、逐片上传、合并落盘与缺片防御）")
    void testChunkedUploadLifecycleAndValidation() throws Exception {
        String targetPath = "chunks/large-file.bin";

        // 1. 初始化分片会话
        MvcResult initResult = mockMvc.perform(post("/sample/storage/chunk/initiate")
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

        mockMvc.perform(multipart("/sample/storage/chunk/upload")
                        .file(part1)
                        .param("uploadId", uploadId)
                        .param("chunkIndex", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 3. 故意尝试以 totalChunks=2 合并（此时缺少第 2 片），验证 STORAGE_CHUNK_MISSING (40012)
        mockMvc.perform(post("/sample/storage/chunk/complete")
                        .param("uploadId", uploadId)
                        .param("path", targetPath)
                        .param("totalChunks", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40012));

        // 4. 上传第 2 分片（500 字节）
        byte[] chunk2 = new byte[500];
        for (int i = 0; i < 500; i++) chunk2[i] = (byte) 2;
        MockMultipartFile part2 = new MockMultipartFile("file", "part2.bin", "application/octet-stream", chunk2);

        mockMvc.perform(multipart("/sample/storage/chunk/upload")
                        .file(part2)
                        .param("uploadId", uploadId)
                        .param("chunkIndex", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 5. 再次合并（totalChunks=2），校验合并成功
        mockMvc.perform(post("/sample/storage/chunk/complete")
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
        MvcResult initAbortResult = mockMvc.perform(post("/sample/storage/chunk/initiate")
                        .param("path", "chunks/aborted.bin"))
                .andExpect(status().isOk())
                .andReturn();
        String abortUploadId = objectMapper.readTree(initAbortResult.getResponse().getContentAsString()).get("data").asText();

        mockMvc.perform(multipart("/sample/storage/chunk/upload")
                        .file(part1)
                        .param("uploadId", abortUploadId)
                        .param("chunkIndex", "1"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/sample/storage/chunk/abort")
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
                "malicious_seal.png",
                "image/png",
                fakeBytes
        );

        // 上传伪装文件 -> 预期被 StorageGuard 拦截并返回业务错误码 40010
        mockMvc.perform(multipart("/sample/storage/object/upload").file(fakePng))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40010))
                .andExpect(jsonPath("$.msg").value(org.hamcrest.Matchers.containsString("魔数")));
    }

    @Test
    @DisplayName("端到端测试 12：多文件打包 ZIP 包含重复源路径时自动重命名生成两份条目")
    void testDownloadZipWithDuplicatePaths() throws Exception {
        storageTemplate.uploadString("vouchers/dup/voucher.txt", "Duplicate Voucher Content");

        MvcResult result = mockMvc.perform(get("/sample/storage/transfer/download-zip")
                        .param("paths", "vouchers/dup/voucher.txt", "vouchers/dup/voucher.txt")
                        .param("zipFilename", "dup_vouchers.zip"))
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
        assertThat(unzipped).containsKey("vouchers/dup/voucher.txt");
        assertThat(unzipped).containsKey("vouchers/dup/voucher (1).txt");
    }

    @Test
    @DisplayName("端到端测试 13：Controller 传输边界 JSR-303 参数校验拦截")
    void testValidationConstraintDefense() throws Exception {
        // copy 端点缺少 sourcePath (空白)
        mockMvc.perform(post("/sample/storage/object/copy")
                        .param("sourcePath", "   ")
                        .param("targetPath", "contracts/target.txt"))
                .andExpect(jsonPath("$.code").value(org.hamcrest.Matchers.not(200)));
    }

    @Test
    @DisplayName("端到端测试 14：客户端直传预签名生成与前端 PUT 直传交互闭环")
    void testPresignedDirectUploadPut() throws Exception {
        byte[] payload = "Direct PUT Content".getBytes(StandardCharsets.UTF_8);

        // 1. 获取预签名直传写入外链凭据
        MvcResult presignResult = mockMvc.perform(get("/sample/storage/presigned/upload-url")
                        .param("path", "direct/doc.txt")
                        .param("durationSeconds", "300"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.httpMethod").value("PUT"))
                .andReturn();

        JsonNode root = objectMapper.readTree(presignResult.getResponse().getContentAsString());
        String fullUrl = root.get("data").get("url").asText();
        assertThat(fullUrl).contains("/storage/local/upload");

        // 2. 模拟前端直接通过 HTTP PUT 将二进制写入直传端点
        java.net.URI uri = java.net.URI.create(fullUrl);
        mockMvc.perform(put(uri)
                        .content(payload)
                        .contentType(MediaType.TEXT_PLAIN))
                .andExpect(status().isOk());

        // 3. 校验文件在底层存储中真实存在且内容一致
        assertThat(storageTemplate.exists("direct/doc.txt")).isTrue();
        assertThat(new String(storageTemplate.downloadBytes("direct/doc.txt"), StandardCharsets.UTF_8)).isEqualTo("Direct PUT Content");
    }

    @Test
    @DisplayName("端到端测试 15：端侧大文件分块直传预签名与完成合并闭环")
    void testPresignedChunkedUploadPartAndComplete() throws Exception {
        byte[] part1Bytes = "Part 1 Chunk Data".getBytes(StandardCharsets.UTF_8);
        byte[] part2Bytes = "Part 2 Chunk Data".getBytes(StandardCharsets.UTF_8);

        // 1. 初始化分片会话
        MvcResult initResult = mockMvc.perform(post("/sample/storage/chunk/initiate")
                        .param("path", "chunked/large.bin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn();
        String uploadId = objectMapper.readTree(initResult.getResponse().getContentAsString()).get("data").asText();

        // 2. 获取分片 1 直传外链并上传
        MvcResult part1Result = mockMvc.perform(get("/sample/storage/chunk/presigned-part-url")
                        .param("path", "chunked/large.bin")
                        .param("uploadId", uploadId)
                        .param("chunkIndex", "1"))
                .andExpect(status().isOk())
                .andReturn();
        String part1Url = objectMapper.readTree(part1Result.getResponse().getContentAsString()).get("data").get("url").asText();
        mockMvc.perform(put(java.net.URI.create(part1Url)).content(part1Bytes)).andExpect(status().isOk());

        // 3. 获取分片 2 直传外链并上传
        MvcResult part2Result = mockMvc.perform(get("/sample/storage/chunk/presigned-part-url")
                        .param("path", "chunked/large.bin")
                        .param("uploadId", uploadId)
                        .param("chunkIndex", "2"))
                .andExpect(status().isOk())
                .andReturn();
        String part2Url = objectMapper.readTree(part2Result.getResponse().getContentAsString()).get("data").get("url").asText();
        mockMvc.perform(put(java.net.URI.create(part2Url)).content(part2Bytes)).andExpect(status().isOk());

        // 4. 完成合并
        mockMvc.perform(post("/sample/storage/chunk/complete")
                        .param("uploadId", uploadId)
                        .param("path", "chunked/large.bin")
                        .param("totalChunks", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.size").value(part1Bytes.length + part2Bytes.length));

        assertThat(storageTemplate.exists("chunked/large.bin")).isTrue();
    }

    @Test
    @DisplayName("端到端测试 16：StorageHashUtil 散列计算与知识库文档极速秒传业务闭环")
    void testCalculateHashAndFastUpload() throws Exception {
        String content = "2026企业信息安全管理制度白皮书正文";
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);

        // 1. 将文件预先放入哈希池
        String sha256 = io.github.atengk.boot.storage.util.StorageHashUtil.sha256Hex(new ByteArrayInputStream(bytes));
        storageTemplate.uploadString("hash-pool/" + sha256 + ".dat", content);

        // 2. 校验散列计算端点
        mockMvc.perform(get("/sample/storage/transfer/hash")
                        .param("path", "hash-pool/" + sha256 + ".dat")
                        .param("algorithm", "SHA256"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(sha256));

        // 3. 执行秒传（文件已存在于哈希池，秒传成功）
        mockMvc.perform(post("/sample/storage/transfer/fast-upload")
                        .param("targetPath", "knowledge-base/security-whitepaper.txt")
                        .param("hash", sha256))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.path").value("knowledge-base/security-whitepaper.txt"));

        assertThat(storageTemplate.exists("knowledge-base/security-whitepaper.txt")).isTrue();
        assertThat(storageTemplate.downloadString("knowledge-base/security-whitepaper.txt")).isEqualTo(content);

        // 4. 秒传未命中池（返回 40404 引导走普通上传）
        mockMvc.perform(post("/sample/storage/transfer/fast-upload")
                        .param("targetPath", "knowledge-base/not-found.txt")
                        .param("hash", "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40404));
    }

    @Test
    @DisplayName("端到端测试 17：前缀与目录流式批量删除场景闭环")
    void testDeletePrefixAndDirectory() throws Exception {
        storageTemplate.uploadString("logs/audit/2026/01.log", "log1");
        storageTemplate.uploadString("logs/audit/2026/02.log", "log2");
        storageTemplate.uploadString("logs/audit/other.txt", "keep");

        // 1. 删除 logs/audit/2026/ 前缀
        mockMvc.perform(post("/sample/storage/object/delete-prefix")
                        .param("prefix", "logs/audit/2026/"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        assertThat(storageTemplate.exists("logs/audit/2026/01.log")).isFalse();
        assertThat(storageTemplate.exists("logs/audit/2026/02.log")).isFalse();
        assertThat(storageTemplate.exists("logs/audit/other.txt")).isTrue();

        // 2. 级联删除目录 logs/audit
        mockMvc.perform(post("/sample/storage/object/delete-directory")
                        .param("dirPath", "logs/audit"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        assertThat(storageTemplate.exists("logs/audit/other.txt")).isFalse();
    }

    @Test
    @DisplayName("端到端测试 18：上传电子合同携带自定义业务元数据闭环")
    void testUploadWithUserMetadata() throws Exception {
        byte[] fileBytes = "2026年度战略合作协议签署版".getBytes(StandardCharsets.UTF_8);
        MockMultipartFile file = new MockMultipartFile("file", "strategic-agreement.txt", "text/plain", fileBytes);

        mockMvc.perform(multipart("/sample/storage/object/upload-with-metadata")
                        .file(file)
                        .param("path", "contracts/2026/strategic-agreement.txt")
                        .param("contractNo", "HT-2026-088")
                        .param("signer", "Ateng")
                        .param("dept", "procurement"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.userMetadata.contractNo").value("HT-2026-088"))
                .andExpect(jsonPath("$.data.userMetadata.signer").value("Ateng"))
                .andExpect(jsonPath("$.data.userMetadata.dept").value("procurement"));
    }

    @Test
    @DisplayName("端到端测试 19：Spring Boot Actuator /actuator/health 存储健康探针感知")
    void testActuatorHealthIndicator() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.storage.status").value("UP"));
    }

    @Test
    @DisplayName("端到端测试 20：单参快捷上传（自动生成 yyyy/MM/dd/{uuid}.{ext} 日期归档安全路径）")
    void testQuickUploadAutoDatePath() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "quick-report.txt",
                MediaType.TEXT_PLAIN_VALUE,
                "Quick Upload Auto Path Content".getBytes(StandardCharsets.UTF_8)
        );

        MvcResult result = mockMvc.perform(multipart("/sample/storage/object/upload-quick").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.path").isNotEmpty())
                .andReturn();

        String autoPath = objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("path").asText();
        assertThat(autoPath).matches("^\\d{4}/\\d{2}/\\d{2}/[a-f0-9]+\\.txt$");
        assertThat(storageTemplate.downloadString(autoPath)).isEqualTo("Quick Upload Auto Path Content");
    }

    @Test
    @DisplayName("端到端测试 21：幂等防覆盖上传（uploadIfAbsent 首次成功、重传拦截 40024）")
    void testUploadIfAbsentIdempotency() throws Exception {
        String targetPath = "idempotent/seal-contract.txt";
        MockMultipartFile first = new MockMultipartFile(
                "file", "seal-contract.txt", MediaType.TEXT_PLAIN_VALUE, "Version 1".getBytes(StandardCharsets.UTF_8));
        MockMultipartFile duplicate = new MockMultipartFile(
                "file", "seal-contract.txt", MediaType.TEXT_PLAIN_VALUE, "Version 2 Overwrite Attempt".getBytes(StandardCharsets.UTF_8));

        mockMvc.perform(multipart("/sample/storage/object/upload-if-absent")
                        .file(first)
                        .param("path", targetPath))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.path").value(targetPath));

        mockMvc.perform(multipart("/sample/storage/object/upload-if-absent")
                        .file(duplicate)
                        .param("path", targetPath))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40024));

        assertThat(storageTemplate.downloadString(targetPath)).isEqualTo("Version 1");
    }

    @Test
    @DisplayName("端到端测试 22：跨存储源流式复制与迁移（copyTo / moveTo）")
    void testCrossSourceCopyToAndMoveTo() throws Exception {
        storageTemplate.uploadString("cross/copy-src.txt", "Cross Copy Payload");
        storageTemplate.uploadString("cross/move-src.txt", "Cross Move Payload");

        mockMvc.perform(post("/sample/storage/object/copy-to")
                        .param("sourcePath", "cross/copy-src.txt")
                        .param("targetSourceKey", "backup")
                        .param("targetPath", "archive/copied.txt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.path").value("archive/copied.txt"));

        assertThat(storageTemplate.exists("cross/copy-src.txt")).isTrue();
        assertThat(storageTemplate.use("backup").downloadString("archive/copied.txt")).isEqualTo("Cross Copy Payload");

        mockMvc.perform(post("/sample/storage/object/move-to")
                        .param("sourcePath", "cross/move-src.txt")
                        .param("targetSourceKey", "backup")
                        .param("targetPath", "archive/moved.txt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.path").value("archive/moved.txt"));

        assertThat(storageTemplate.exists("cross/move-src.txt")).isFalse();
        assertThat(storageTemplate.use("backup").downloadString("archive/moved.txt")).isEqualTo("Cross Move Payload");
    }

    @Test
    @DisplayName("端到端测试 23：事务感知上传回滚防孤儿清理与提交后延迟删除")
    void testTransactionRollbackAndAfterCommitLifecycle() throws Exception {
        String rollbackPath = "tx-demo/rollback-orphan.txt";
        MockMultipartFile rollbackFile = new MockMultipartFile(
                "file", "rollback-orphan.txt", MediaType.TEXT_PLAIN_VALUE, "Orphan Payload".getBytes(StandardCharsets.UTF_8));

        // 1. 事务回滚 -> 自动删除孤儿文件
        mockMvc.perform(multipart("/sample/storage/object/upload-with-rollback-delete")
                        .file(rollbackFile)
                        .param("path", rollbackPath)
                        .param("simulateFailure", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(50008));
        assertThat(storageTemplate.exists(rollbackPath)).isFalse();

        // 2. 事务提交 -> 保留已上传文件
        String commitPath = "tx-demo/commit-keep.txt";
        MockMultipartFile commitFile = new MockMultipartFile(
                "file", "commit-keep.txt", MediaType.TEXT_PLAIN_VALUE, "Committed Payload".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(multipart("/sample/storage/object/upload-with-rollback-delete")
                        .file(commitFile)
                        .param("path", commitPath)
                        .param("simulateFailure", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
        assertThat(storageTemplate.exists(commitPath)).isTrue();

        // 3. 注册提交后延迟删除，模拟回滚 -> 原对象必须完整保留
        mockMvc.perform(post("/sample/storage/object/delete-after-commit")
                        .param("path", commitPath)
                        .param("simulateFailure", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(50008));
        assertThat(storageTemplate.exists(commitPath)).isTrue();

        // 4. 注册提交后延迟删除，正常提交 -> 原对象物理删除
        mockMvc.perform(post("/sample/storage/object/delete-after-commit")
                        .param("path", commitPath)
                        .param("simulateFailure", "false"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));
        assertThat(storageTemplate.exists(commitPath)).isFalse();
    }

    @Test
    @DisplayName("端到端测试 24：StorageContextHolder.executeWith 函数式作用域切源与自动恢复")
    void testExecuteWithScopedRouting() throws Exception {
        String backupPath = "scoped/audit-2026.txt";
        storageTemplate.use("backup").uploadString(backupPath, "Scoped Backup Audit");

        mockMvc.perform(get("/sample/storage/governance/execute-with")
                        .param("source", "backup")
                        .param("path", backupPath))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.scopedSource").value("backup"))
                .andExpect(jsonPath("$.data.exists").value(true))
                .andExpect(jsonPath("$.data.outerBefore").isEmpty())
                .andExpect(jsonPath("$.data.outerAfter").isEmpty());
    }

    @Test
    @DisplayName("端到端测试 25：StorageGuard disallowed-extensions 黑名单扩展名拦截（STORAGE_FILE_NOT_ALLOWED 40011）")
    void testDisallowedExtensionsBlacklist() throws Exception {
        MockMultipartFile blacklisted = new MockMultipartFile(
                "file",
                "temp-dump.bak",
                MediaType.TEXT_PLAIN_VALUE,
                "Plain backup content with blacklisted suffix".getBytes(StandardCharsets.UTF_8)
        );

        mockMvc.perform(multipart("/sample/storage/governance/upload-guarded")
                        .file(blacklisted)
                        .param("path", "guard/temp-dump.bak"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(40011));
        assertThat(storageTemplate.exists("guard/temp-dump.bak")).isFalse();

        MockMultipartFile allowed = new MockMultipartFile(
                "file",
                "safe-policy.txt",
                MediaType.TEXT_PLAIN_VALUE,
                "Safe policy content".getBytes(StandardCharsets.UTF_8)
        );
        mockMvc.perform(multipart("/sample/storage/governance/upload-guarded")
                        .file(allowed)
                        .param("path", "guard/safe-policy.txt"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.path").value("guard/safe-policy.txt"));
    }
}
