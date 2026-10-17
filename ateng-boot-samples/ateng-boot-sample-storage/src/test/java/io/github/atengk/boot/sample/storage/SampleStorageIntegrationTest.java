package io.github.atengk.boot.sample.storage;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.core.model.Result;
import io.github.atengk.boot.core.util.FileUtil;
import io.github.atengk.boot.sample.storage.service.FileStorageDemoService;
import io.github.atengk.boot.storage.model.StorageObject;
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
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
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

    private static final Path DEFAULT_DIR = Path.of("target/sample-storage/default");
    private static final Path BACKUP_DIR = Path.of("target/sample-storage/backup");

    @BeforeEach
    void setUp() throws Exception {
        // 清理并确保测试目录准备就绪
        org.springframework.util.FileSystemUtils.deleteRecursively(DEFAULT_DIR);
        org.springframework.util.FileSystemUtils.deleteRecursively(BACKUP_DIR);
        Files.createDirectories(DEFAULT_DIR);
        Files.createDirectories(BACKUP_DIR);
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

        MvcResult mvcResult = mockMvc.perform(multipart("/api/storage/upload").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.path").value("hello.txt"))
                .andExpect(jsonPath("$.data.size").value(fileBytes.length))
                .andExpect(jsonPath("$.data.contentType").value("text/plain"))
                .andReturn();

        // 验证物理磁盘文件已落盘
        Path targetPath = DEFAULT_DIR.resolve("hello.txt");
        assertThat(Files.exists(targetPath)).isTrue();
        assertThat(Files.readAllBytes(targetPath)).isEqualTo(fileBytes);
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
}
