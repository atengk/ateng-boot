package io.github.atengk.boot.sample.storage;

import io.github.atengk.boot.sample.storage.service.S3StorageScenarioService;
import io.github.atengk.boot.storage.driver.StorageDriver;
import io.github.atengk.boot.storage.exception.StorageException;
import io.github.atengk.boot.storage.exception.StorageResultCode;
import io.github.atengk.boot.storage.factory.StorageDriverFactory;
import io.github.atengk.boot.storage.model.PresignedDownloadRequest;
import io.github.atengk.boot.storage.model.PresignedUrl;
import io.github.atengk.boot.storage.model.StorageObject;
import io.github.atengk.boot.storage.properties.StorageProperties;
import io.github.atengk.boot.storage.properties.StorageSourceProperties;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 云原生 S3 样例工程双模自适应端到端集成测试
 * 遵循 ADR-0028 与 ADR-0061 规范，实现单测试套件双模自适应架构：
 * 1. 默认/CI 模式 (!local profile)：自动装配 MockS3StorageDriver 内存桩，100% 离线秒级通过
 * 2. Local 真实模式 (local profile)：Mock 桩自动失效，直连物理 RustFS 真实服务进行全真读写与清理断言
 *
 * @author Ateng
 * @since 2026-10-28
 */
@Slf4j
@SpringBootTest
@Import(SampleS3DualModeIntegrationTest.TestS3MockConfig.class)
@DisplayName("ateng-boot-sample-storage S3 样例双模自适应集成测试")
class SampleS3DualModeIntegrationTest {

    @Autowired
    private S3StorageScenarioService scenarioService;

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
    void testUploadAdaptiveStream_ShouldSucceed() {
        String path = "test-s3/adaptive-stream-" + System.currentTimeMillis() + ".txt";
        createdPaths.add(path);

        byte[] payload = "Hello Adaptive Streaming Upload with Unknown Size!".getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream in = new ByteArrayInputStream(payload);

        StorageObject result = scenarioService.uploadAdaptiveStream(path, in, "text/plain");

        assertThat(result).isNotNull();
        assertThat(result.getPath()).isEqualTo(path);
        assertThat(result.getSize()).isEqualTo((long) payload.length);

        StorageObject details = scenarioService.getObjectDetails(path);
        assertThat(details).isNotNull();
        assertThat(details.getPath()).isEqualTo(path);
        assertThat(details.getSize()).isEqualTo((long) payload.length);
    }

    @Test
    @DisplayName("场景 2：公共读权限直传与永久直接访问链接 (getUrl)")
    void testPublicReadAndPermanentPublicUrl_ShouldSucceed() {
        String path = "test-s3/public-direct-" + System.currentTimeMillis() + ".html";
        createdPaths.add(path);

        byte[] payload = "<html><body><h1>Public Read Direct Link</h1></body></html>".getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream in = new ByteArrayInputStream(payload);

        StorageObject result = scenarioService.uploadPublicRead(path, in, "text/html", payload.length);
        assertThat(result).isNotNull();
        assertThat(result.getPath()).isEqualTo(path);

        String permanentUrl = scenarioService.getPermanentPublicUrl(path);
        log.info("获取到的永久直接访问 URL: {}", permanentUrl);

        assertThat(permanentUrl).isNotBlank();
        assertThat(permanentUrl).contains(path);
        // 强断言：永久直链不得包含临时签名字段
        assertThat(permanentUrl).doesNotContain("X-Amz-Signature");
        assertThat(permanentUrl).doesNotContain("X-Amz-Expires");
    }

    @Test
    @DisplayName("场景 3：分段切片字节范围读取 (Byte-Range Read)")
    void testByteRangePartialDownload_ShouldSucceed() {
        String path = "test-s3/range-data-" + System.currentTimeMillis() + ".dat";
        createdPaths.add(path);

        // 构造有序测试数据: 0123456789abcdefghijklmnopqrstuvwxyz
        String content = "0123456789abcdefghijklmnopqrstuvwxyz";
        byte[] payload = content.getBytes(StandardCharsets.UTF_8);

        scenarioService.uploadAdaptiveStream(path, new ByteArrayInputStream(payload), "text/plain");

        // 截取索引 10 开始，长度为 5 的切片，预期为 "abcde"
        byte[] slice = scenarioService.downloadByteRange(path, 10, 5);

        assertThat(new String(slice, StandardCharsets.UTF_8)).isEqualTo("abcde");
    }

    @Test
    @DisplayName("场景 4：预签名临时下载外链与友好中文文件名定制响应头")
    void testPresignedDownloadUrlWithFriendlyChineseFilename_ShouldSucceed() {
        String path = "test-s3/report-" + System.currentTimeMillis() + ".txt";
        createdPaths.add(path);

        byte[] payload = "binary-stream-or-text-data-for-presigned-download".getBytes(StandardCharsets.UTF_8);
        scenarioService.uploadAdaptiveStream(path, new ByteArrayInputStream(payload), "text/plain");

        String downloadFilename = "2026年度业务审计报表.xlsx";
        PresignedUrl presignedUrl = scenarioService.getPresignedDownloadUrlWithFriendlyFilename(path, Duration.ofMinutes(15), downloadFilename);

        log.info("生成的友好中文文件名预签名外链: {}", presignedUrl.getUrl());

        assertThat(presignedUrl).isNotNull();
        assertThat(presignedUrl.getUrl()).isNotBlank();
        // 强断言：生成的预签名 URL 必须包含 response-content-disposition 响应头重写参数
        assertThat(presignedUrl.getUrl()).contains("response-content-disposition");
        assertThat(presignedUrl.getExpiration()).isAfter(Instant.now());
    }

    @Test
    @DisplayName("场景 5：对象标签 Tagging 完整生命周期 (设置、获取、清空)")
    void testObjectTaggingLifecycle_ShouldSucceed() {
        String path = "test-s3/tagged-object-" + System.currentTimeMillis() + ".log";
        createdPaths.add(path);

        byte[] payload = "log-content-for-tagging-verification".getBytes(StandardCharsets.UTF_8);
        scenarioService.uploadAdaptiveStream(path, new ByteArrayInputStream(payload), "text/plain");

        // 1. 设置对象标签
        Map<String, String> tags = Map.of(
                "environment", "uat",
                "department", "technology",
                "project", "ateng-boot"
        );
        scenarioService.setObjectTags(path, tags);

        // 2. 读取并强断言标签存在
        Map<String, String> retrievedTags = scenarioService.getObjectTags(path);
        assertThat(retrievedTags)
                .containsEntry("environment", "uat")
                .containsEntry("department", "technology")
                .containsEntry("project", "ateng-boot");

        // 3. 删除对象标签并断言已被清空
        scenarioService.deleteObjectTags(path);
        Map<String, String> emptyTags = scenarioService.getObjectTags(path);
        assertThat(emptyTags).isEmpty();
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
    }

    @Test
    @DisplayName("场景 7：S3 存储源健康探测 (ping) 与配置/连接池运维诊断 (diagnostics)")
    void testS3HealthPingAndDiagnostics_ShouldSucceed() {
        // 1. 验证连通性 ping
        boolean healthy = scenarioService.pingS3Source();
        assertThat(healthy).isTrue();

        // 2. 验证诊断上下文提取
        Map<String, Object> diagnostics = scenarioService.getS3Diagnostics();
        assertThat(diagnostics).isNotNull();
        log.info("提取到的 S3 驱动诊断信息: {}", diagnostics);
        assertThat(diagnostics).containsEntry("type", "s3");
    }

    /**
     * 离线测试桩配置：仅在未激活 local Profile 时装配，提供 100% 内存自洽模拟
     */
    @TestConfiguration
    @Profile("!local")
    static class TestS3MockConfig {

        @Bean
        @Primary
        public StorageDriverFactory storageDriverFactory(StorageProperties properties) {
            String primary = properties.getPrimary();
            if (primary == null || primary.isBlank()) {
                primary = "default";
            }
            StorageDriverFactory factory = new StorageDriverFactory(primary);

            // 注册配置中的除 s3 外的其它驱动（如 default、backup）
            Map<String, StorageSourceProperties> sources = properties.getSources();
            if (sources != null && !sources.isEmpty()) {
                for (Map.Entry<String, StorageSourceProperties> entry : sources.entrySet()) {
                    if (!"s3".equalsIgnoreCase(entry.getKey())) {
                        factory.registerDriver(entry.getKey(), StorageDriverFactory.createDriver(entry.getKey(), entry.getValue()));
                    }
                }
            }

            // 注册内存 Mock S3 驱动
            factory.registerDriver("s3", new MockS3StorageDriver());
            return factory;
        }

        /**
         * 离线内存模拟 S3 驱动实现
         */
        static class MockS3StorageDriver implements StorageDriver {

            private final Map<String, byte[]> storage = new ConcurrentHashMap<>();
            private final Map<String, StorageObject> metadata = new ConcurrentHashMap<>();
            private final Map<String, Map<String, String>> tags = new ConcurrentHashMap<>();

            @Override
            public StorageObject upload(String path, InputStream in, String contentType, long size) {
                try {
                    byte[] bytes = in.readAllBytes();
                    storage.put(path, bytes);
                    StorageObject obj = StorageObject.builder()
                            .path(path)
                            .filename(path.substring(path.lastIndexOf('/') + 1))
                            .size((long) bytes.length)
                            .contentType(contentType != null ? contentType : "application/octet-stream")
                            .lastModified(Instant.now())
                            .build();
                    metadata.put(path, obj);
                    return obj;
                } catch (Exception e) {
                    throw new StorageException(StorageResultCode.OPERATION_FAILED, "Mock 上传失败", e);
                }
            }

            @Override
            public StorageObject upload(String path, InputStream in, String contentType, long size, Map<String, String> userMetadata, boolean publicRead) {
                return upload(path, in, contentType, size);
            }

            @Override
            public String getUrl(String path) {
                return "http://103.236.97.120:47875/ateng-test/" + path;
            }

            @Override
            public InputStream download(String path) {
                byte[] data = storage.get(path);
                if (data == null) {
                    throw new StorageException(StorageResultCode.OBJECT_NOT_FOUND, "Mock 对象不存在: " + path);
                }
                return new ByteArrayInputStream(data);
            }

            @Override
            public InputStream download(String path, long startOffset, long length) {
                byte[] data = storage.get(path);
                if (data == null) {
                    throw new StorageException(StorageResultCode.OBJECT_NOT_FOUND, "Mock 对象不存在: " + path);
                }
                int from = (int) Math.min(startOffset, data.length);
                int to = (int) Math.min(startOffset + length, data.length);
                byte[] slice = Arrays.copyOfRange(data, from, to);
                return new ByteArrayInputStream(slice);
            }

            @Override
            public StorageObject getMetadata(String path) {
                StorageObject obj = metadata.get(path);
                if (obj == null) {
                    throw new StorageException(StorageResultCode.OBJECT_NOT_FOUND, "Mock 对象不存在: " + path);
                }
                return obj;
            }

            @Override
            public Map<String, String> getObjectTags(String path) {
                return tags.getOrDefault(path, Collections.emptyMap());
            }

            @Override
            public void setObjectTags(String path, Map<String, String> newTags) {
                tags.put(path, new ConcurrentHashMap<>(newTags != null ? newTags : Collections.emptyMap()));
            }

            @Override
            public void deleteObjectTags(String path) {
                tags.remove(path);
            }

            @Override
            public boolean exists(String path) {
                return storage.containsKey(path);
            }

            @Override
            public boolean delete(String path) {
                storage.remove(path);
                metadata.remove(path);
                tags.remove(path);
                return true;
            }

            @Override
            public boolean deleteBatch(List<String> paths) {
                if (paths != null) {
                    paths.forEach(this::delete);
                }
                return true;
            }

            @Override
            public boolean deletePrefix(String prefix) {
                storage.keySet().removeIf(k -> k.startsWith(prefix));
                metadata.keySet().removeIf(k -> k.startsWith(prefix));
                tags.keySet().removeIf(k -> k.startsWith(prefix));
                return true;
            }

            @Override
            public boolean copy(String sourcePath, String targetPath) {
                byte[] data = storage.get(sourcePath);
                if (data != null) {
                    storage.put(targetPath, data);
                    return true;
                }
                return false;
            }

            @Override
            public boolean move(String sourcePath, String targetPath) {
                if (copy(sourcePath, targetPath)) {
                    delete(sourcePath);
                    return true;
                }
                return false;
            }

            @Override
            public List<StorageObject> list(String prefix, boolean recursive) {
                return metadata.values().stream()
                        .filter(o -> prefix == null || prefix.isBlank() || o.getPath().startsWith(prefix))
                        .toList();
            }

            @Override
            public PresignedUrl getPresignedUrl(PresignedDownloadRequest request) {
                String filename = request.getDownloadFilename();
                String cd = (filename != null && !filename.isBlank())
                        ? "attachment; filename=\"" + filename + "\""
                        : "attachment";
                String presigned = "http://103.236.97.120:47875/ateng-test/" + request.getPath() + "?response-content-disposition=" + cd;
                return PresignedUrl.builder()
                        .url(presigned)
                        .expiration(Instant.now().plus(request.getDuration()))
                        .build();
            }

            @Override
            public void ping() {
                // Mock 默认连通
            }

            @Override
            public Map<String, Object> getDiagnostics() {
                return Map.of("type", "s3", "endpoint", "http://103.236.97.120:47875", "bucket", "ateng-test");
            }

            @Override
            public String initiateChunkedUpload(String path) {
                return "mock-upload-id-" + System.currentTimeMillis();
            }

            @Override
            public void uploadChunk(String uploadId, int chunkIndex, InputStream in, long size) {
            }

            @Override
            public StorageObject completeChunkedUpload(String uploadId, String targetPath, int totalChunks) {
                return upload(targetPath, new ByteArrayInputStream(new byte[0]), "application/octet-stream", 0);
            }

            @Override
            public void abortChunkedUpload(String uploadId) {
            }
        }
    }
}
