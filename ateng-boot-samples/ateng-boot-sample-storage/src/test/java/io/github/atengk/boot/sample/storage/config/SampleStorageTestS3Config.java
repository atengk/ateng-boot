package io.github.atengk.boot.sample.storage.config;

import io.github.atengk.boot.storage.driver.StorageDriver;
import io.github.atengk.boot.storage.exception.StorageException;
import io.github.atengk.boot.storage.exception.StorageResultCode;
import io.github.atengk.boot.storage.factory.StorageDriverFactory;
import io.github.atengk.boot.storage.model.PresignedDownloadRequest;
import io.github.atengk.boot.storage.model.PresignedUrl;
import io.github.atengk.boot.storage.model.StorageObject;
import io.github.atengk.boot.storage.properties.StorageProperties;
import io.github.atengk.boot.storage.properties.StorageSourceProperties;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对象存储样例工程离线测试桩配置
 * <p>
 * 当且仅当非 local 环境（CI / 默认单测）时激活，提供基于内存的 Mock S3 存储驱动，
 * 保障 100% 离线自洽与全自洽单测运行，避免在未配置外部物理 S3/MinIO 时产生连接报错与健康探测失败。
 * 当激活 local Profile 时，本配置类自动失效，无缝让渡给真实 S3 自动装配。
 * </p>
 *
 * @author Ateng
 * @since 2026-10-29
 */
@TestConfiguration
@Profile("!local")
public class SampleStorageTestS3Config {

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
    public static class MockS3StorageDriver implements StorageDriver {

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
                        .storageClass("STANDARD")
                        .serverSideEncryption("AES256")
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
            return "http://localhost:9000/ateng-test/" + path;
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
            String presigned = "http://localhost:9000/ateng-test/" + request.getPath() + "?response-content-disposition=" + cd;
            return PresignedUrl.builder()
                    .url(presigned)
                    .expiration(Instant.now().plus(request.getDuration()))
                    .build();
        }

        @Override
        public void ping() {
            // Mock 默认连通，保障 Actuator health 在 CI 环境下 100% 离线自洽为 UP
        }

        @Override
        public Map<String, Object> getDiagnostics() {
            return Map.of("type", "s3", "endpoint", "http://localhost:9000", "bucket", "ateng-test");
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
