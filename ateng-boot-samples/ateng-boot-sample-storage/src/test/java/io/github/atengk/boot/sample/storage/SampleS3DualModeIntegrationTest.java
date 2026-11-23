package io.github.atengk.boot.sample.storage;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.atengk.boot.sample.storage.config.SampleStorageTestS3Config;
import io.github.atengk.boot.sample.storage.service.S3StorageScenarioService;
import io.github.atengk.boot.storage.model.PresignedUrl;
import io.github.atengk.boot.storage.model.StorageObject;
import io.github.atengk.boot.storage.template.StorageTemplate;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 云原生 S3 样例工程双模自适应端到端集成测试
 * 遵循 ADR-0028 与 ADR-0061 规范，实现单测试套件双模自适应架构：
 * 1. 默认/CI 模式 (!local profile)：自动装配 MockS3StorageDriver 内存桩，100% 离线秒级通过
 * 2. Local 真实模式 (local profile)：Mock 桩自动失效，直连物理 S3/RustFS 真实服务进行全真读写与清理断言
 *
 * @author Ateng
 * @since 2026-10-28
 */
@Slf4j
@SpringBootTest
@AutoConfigureMockMvc
@Import(SampleStorageTestS3Config.class)
@DisplayName("ateng-boot-sample-storage S3 样例双模自适应集成测试")
class SampleS3DualModeIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private S3StorageScenarioService scenarioService;

    @Autowired
    private StorageTemplate storageTemplate;

    @Autowired
    private Environment environment;

    private final Set<String> createdPaths = ConcurrentHashMap.newKeySet();

    @BeforeEach
    void setUp() {
        boolean isLocal = Arrays.asList(environment.getActiveProfiles()).contains("local");
        log.info(">>> S3 集成测试启动，当前运行模式: [{}]", isLocal ? "REAL_RUSTFS_PHYSICAL" : "OFFLINE_MOCK_CI");
    }

    @AfterEach
    void tearDown() {
        // 确定性资源清理铁律：清理本次测试产生的全部临时对象，防止脏数据累积
        for (String path : createdPaths) {
            try {
                scenarioService.deleteObject(path);
                log.debug("成功清理测试对象: {}", path);
            } catch (Exception e) {
                log.warn("清理测试对象失败 [{}]: {}", path, e.getMessage());
            }
        }
        createdPaths.clear();
    }

    @Test
    @DisplayName("场景 1：未知流长自适应流式分片上传（双阶缓冲防 OOM）")
    void testUploadAdaptiveStream_ShouldSucceed() throws Exception {
        String path = "test-s3/adaptive-stream-" + System.currentTimeMillis() + ".txt";
        createdPaths.add(path);

        String content = "2026年度经营大报表实时动态生成流数据（未知流长双阶缓冲防OOM）";
        byte[] payload = content.getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(post("/sample/storage/transfer/upload-adaptive")
                        .param("path", path)
                        .param("content", content)
                        .param("contentType", "text/plain")
                        .param("source", "s3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.path").value(path))
                .andExpect(jsonPath("$.data.size").value(payload.length));

        StorageObject details = scenarioService.getObjectDetails(path);
        assertThat(details).isNotNull();
        assertThat(details.getPath()).isEqualTo(path);
        assertThat(details.getSize()).isEqualTo((long) payload.length);
    }

    @Test
    @DisplayName("场景 2：公共读权限直传与永久直接访问链接 (getUrl)")
    void testPublicReadAndPermanentPublicUrl_ShouldSucceed() throws Exception {
        String path = "test-s3/public-direct-" + System.currentTimeMillis() + ".html";
        createdPaths.add(path);

        byte[] payload = "<html><body><h1>Public Read Direct Link</h1></body></html>".getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream in = new ByteArrayInputStream(payload);

        StorageObject result = scenarioService.uploadPublicRead(path, in, "text/html", payload.length);
        assertThat(result).isNotNull();
        assertThat(result.getPath()).isEqualTo(path);

        // 验证通过 StoragePresignedController 获取永久直接访问 URL
        MvcResult mvcResult = mockMvc.perform(get("/sample/storage/presigned/public-url")
                        .param("path", path)
                        .param("source", "s3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isNotEmpty())
                .andReturn();

        String permanentUrl = objectMapper.readTree(mvcResult.getResponse().getContentAsString()).get("data").asText();
        log.info("获取到的永久直接访问 URL: {}", permanentUrl);

        assertThat(permanentUrl).isNotBlank();
        assertThat(permanentUrl).contains(path);
        // 强断言：永久直链不得包含临时签名字段
        assertThat(permanentUrl).doesNotContain("X-Amz-Signature");
        assertThat(permanentUrl).doesNotContain("X-Amz-Expires");
    }

    @Test
    @DisplayName("场景 3：分段切片字节范围读取 (Byte-Range Read)")
    void testByteRangePartialDownload_ShouldSucceed() throws Exception {
        String path = "test-s3/range-data-" + System.currentTimeMillis() + ".dat";
        createdPaths.add(path);

        // 构造有序测试数据: 0123456789abcdefghijklmnopqrstuvwxyz
        String content = "0123456789abcdefghijklmnopqrstuvwxyz";
        byte[] payload = content.getBytes(StandardCharsets.UTF_8);

        scenarioService.uploadAdaptiveStream(path, new ByteArrayInputStream(payload), "text/plain");

        // 通过 HTTP 端点截取索引 10 开始，长度为 5 的切片，预期为 "abcde"
        mockMvc.perform(get("/sample/storage/transfer/download-range")
                        .param("path", path)
                        .param("startOffset", "10")
                        .param("length", "5")
                        .param("source", "s3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("abcde"));
    }

    @Test
    @DisplayName("场景 4：预签名临时下载外链与友好中文文件名定制响应头")
    void testPresignedDownloadUrlWithFriendlyChineseFilename_ShouldSucceed() throws Exception {
        String path = "test-s3/report-" + System.currentTimeMillis() + ".txt";
        createdPaths.add(path);

        byte[] payload = "binary-stream-or-text-data-for-presigned-download".getBytes(StandardCharsets.UTF_8);
        scenarioService.uploadAdaptiveStream(path, new ByteArrayInputStream(payload), "text/plain");

        String downloadFilename = "2026年度业务审计报表.xlsx";

        // 通过 StoragePresignedController 获取友好中文文件名预签名外链
        MvcResult mvcResult = mockMvc.perform(get("/sample/storage/presigned/friendly-download-url")
                        .param("path", path)
                        .param("filename", downloadFilename)
                        .param("durationSeconds", "900")
                        .param("source", "s3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.url").isNotEmpty())
                .andReturn();

        String presignedUrl = objectMapper.readTree(mvcResult.getResponse().getContentAsString())
                .get("data").get("url").asText();
        log.info("生成的友好中文文件名预签名外链: {}", presignedUrl);

        assertThat(presignedUrl).isNotBlank();
        // 强断言：生成的预签名 URL 必须包含 response-content-disposition 响应头重写参数
        assertThat(presignedUrl).contains("response-content-disposition");
    }

    @Test
    @DisplayName("场景 5：对象标签 Tagging 完整生命周期 (设置、获取、清空)")
    void testObjectTaggingLifecycle_ShouldSucceed() throws Exception {
        String path = "test-s3/tagged-object-" + System.currentTimeMillis() + ".log";
        createdPaths.add(path);

        byte[] payload = "log-content-for-tagging-verification".getBytes(StandardCharsets.UTF_8);
        scenarioService.uploadAdaptiveStream(path, new ByteArrayInputStream(payload), "text/plain");

        // 1. 设置对象标签 (通过 StorageGovernanceController)
        Map<String, String> tags = Map.of(
                "environment", "uat",
                "department", "technology",
                "project", "ateng-boot"
        );
        mockMvc.perform(post("/sample/storage/governance/tags")
                        .param("path", path)
                        .param("source", "s3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(tags)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        // 2. 读取并强断言标签存在 (通过 StorageGovernanceController)
        mockMvc.perform(get("/sample/storage/governance/tags")
                        .param("path", path)
                        .param("source", "s3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.environment").value("uat"))
                .andExpect(jsonPath("$.data.department").value("technology"))
                .andExpect(jsonPath("$.data.project").value("ateng-boot"));

        // 3. 删除对象标签并断言已被清空 (通过 StorageGovernanceController)
        mockMvc.perform(delete("/sample/storage/governance/tags")
                        .param("path", path)
                        .param("source", "s3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));

        mockMvc.perform(get("/sample/storage/governance/tags")
                        .param("path", path)
                        .param("source", "s3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    @DisplayName("场景 6：存储元数据与冷热分层/服务端加密元数据提取")
    void testStorageClassAndMetadataExtraction_ShouldSucceed() {
        String path = "test-s3/metadata-test-" + System.currentTimeMillis() + ".txt";
        createdPaths.add(path);

        byte[] payload = "metadata-verification-payload".getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream in = new ByteArrayInputStream(payload);

        StorageObject created = scenarioService.uploadStandard(path, in, "text/plain", payload.length);
        assertThat(created).isNotNull();

        StorageObject details = scenarioService.getObjectDetails(path);
        assertThat(details).isNotNull();
        assertThat(details.getPath()).isEqualTo(path);
        assertThat(details.getSize()).isEqualTo((long) payload.length);
        assertThat(details.getContentType()).isEqualTo("text/plain");
        if (details.getStorageClass() != null) {
            assertThat(details.getStorageClass()).isNotBlank();
        }
        if (details.getServerSideEncryption() != null) {
            assertThat(details.getServerSideEncryption()).isNotBlank();
        }
    }

    @Test
    @DisplayName("场景 7：S3 存储源健康探测 (ping) 与配置/连接池运维诊断 (diagnostics)")
    void testS3HealthPingAndDiagnostics_ShouldSucceed() throws Exception {
        // 1. 验证连通性 ping (通过 StorageGovernanceController)
        mockMvc.perform(get("/sample/storage/governance/ping")
                        .param("source", "s3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value(true));

        // 2. 验证诊断上下文提取 (通过 StorageGovernanceController)
        mockMvc.perform(get("/sample/storage/governance/diagnostics")
                        .param("source", "s3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.type").value("s3"));
    }

    @Test
    @DisplayName("场景 8：Local -> S3 跨源流式复制与 executeWith 作用域协同探测（双模通用）")
    void testCrossSourceCopyToS3AndExecuteWithScope_ShouldSucceed() throws Exception {
        long ts = System.currentTimeMillis();
        String localSourcePath = "cross-s3/local-origin-" + ts + ".txt";
        String s3TargetPath = "test-s3/cross-copy-" + ts + ".txt";
        createdPaths.add(s3TargetPath);

        storageTemplate.uploadString(localSourcePath, "Cross Source Local-to-S3 Dual-Mode Payload");
        try {
            mockMvc.perform(post("/sample/storage/object/copy-to")
                            .param("sourcePath", localSourcePath)
                            .param("targetSourceKey", "s3")
                            .param("targetPath", s3TargetPath))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.path").value(s3TargetPath));

            assertThat(storageTemplate.use("s3").downloadString(s3TargetPath))
                    .isEqualTo("Cross Source Local-to-S3 Dual-Mode Payload");

            mockMvc.perform(get("/sample/storage/governance/execute-with")
                            .param("source", "s3")
                            .param("path", s3TargetPath))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(200))
                    .andExpect(jsonPath("$.data.scopedSource").value("s3"))
                    .andExpect(jsonPath("$.data.exists").value(true))
                    .andExpect(jsonPath("$.data.outerAfter").isEmpty());
        } finally {
            storageTemplate.delete(localSourcePath);
        }
    }
}
