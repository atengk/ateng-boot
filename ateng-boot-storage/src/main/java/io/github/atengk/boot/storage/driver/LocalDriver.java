package io.github.atengk.boot.storage.driver;

import io.github.atengk.boot.core.util.FileUtil;
import io.github.atengk.boot.storage.exception.StorageException;
import io.github.atengk.boot.storage.exception.StorageResultCode;
import io.github.atengk.boot.storage.model.PresignedUrl;
import io.github.atengk.boot.storage.model.StorageObject;
import io.github.atengk.boot.storage.util.StorageSecurityUtil;
import lombok.Getter;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 本地磁盘文件存储驱动实现
 * 基于 Java NIO，提供深度路径防穿越清洗、MIME 推导、天然幂等删除保障与 HMAC-SHA256 预签名下载
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Getter
public class LocalDriver implements StorageDriver {

    /**
     * 默认预签名下载端点路径
     */
    public static final String DEFAULT_DOWNLOAD_ENDPOINT = "/storage/local/download";

    /**
     * 本地存储物理基准根目录
     */
    private final Path basePath;

    /**
     * 访问域名或基础 URL（用于构建外链）
     */
    private final String domain;

    /**
     * 预签名 HMAC 密钥（用于防盗链与时效控制）
     */
    private final String secretKey;

    /**
     * 预签名只读安全下载端点路径
     */
    private final String downloadEndpoint;

    /**
     * 构造基础本地磁盘驱动（自动生成随机密钥）
     *
     * @param basePath 本地存储物理基准路径
     */
    public LocalDriver(String basePath) {
        this(basePath, null, null, DEFAULT_DOWNLOAD_ENDPOINT);
    }

    /**
     * 常用参数构造本地磁盘驱动
     *
     * @param basePath  本地存储物理基准路径
     * @param domain    访问域名或基础 URL
     * @param secretKey 签名密钥（留空时自动生成安全随机密钥）
     */
    public LocalDriver(String basePath, String domain, String secretKey) {
        this(basePath, domain, secretKey, DEFAULT_DOWNLOAD_ENDPOINT);
    }

    /**
     * 完整参数构造本地磁盘驱动
     *
     * @param basePath         本地存储物理基准路径
     * @param domain           访问域名或基础 URL
     * @param secretKey        签名密钥（留空时自动生成安全随机密钥）
     * @param downloadEndpoint 预签名安全下载端点
     */
    public LocalDriver(String basePath, String domain, String secretKey, String downloadEndpoint) {
        if (basePath == null || basePath.isBlank()) {
            throw new StorageException(StorageResultCode.INVALID_PATH, "本地存储基础路径 basePath 不能为空");
        }
        try {
            this.basePath = Paths.get(basePath).toAbsolutePath().normalize();
            if (!Files.exists(this.basePath)) {
                Files.createDirectories(this.basePath);
            }
        } catch (IOException e) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "初始化本地存储目录失败: " + basePath, e);
        }
        this.domain = domain;
        this.secretKey = (secretKey != null && !secretKey.isBlank())
                ? secretKey
                : StorageSecurityUtil.generateRandomSecretKey();
        this.downloadEndpoint = (downloadEndpoint != null && !downloadEndpoint.isBlank())
                ? downloadEndpoint
                : DEFAULT_DOWNLOAD_ENDPOINT;
    }

    @Override
    public StorageObject upload(String path, InputStream in, String contentType, long size) {
        if (in == null) {
            throw new StorageException(StorageResultCode.UPLOAD_FAILED, "上传对象输入流不能为空");
        }
        String cleanPath = StorageSecurityUtil.normalizePath(path);
        Path targetFile = resolveAndVerify(cleanPath);
        try {
            Path parent = targetFile.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            Files.copy(in, targetFile, StandardCopyOption.REPLACE_EXISTING);

            long actualSize = size > 0 ? size : Files.size(targetFile);
            String finalContentType = (contentType != null && !contentType.isBlank())
                    ? contentType
                    : StorageSecurityUtil.determineContentType(cleanPath);
            Instant lastModified = Files.getLastModifiedTime(targetFile).toInstant();
            String etag = calculateEtag(targetFile);

            return StorageObject.builder()
                    .path(cleanPath)
                    .filename(FileUtil.cleanFilename(cleanPath))
                    .size(actualSize)
                    .contentType(finalContentType)
                    .etag(etag)
                    .lastModified(lastModified)
                    .build();
        } catch (IOException e) {
            throw new StorageException(StorageResultCode.UPLOAD_FAILED, "文件写入本地磁盘失败: " + cleanPath, e);
        }
    }

    @Override
    public InputStream download(String path) {
        String cleanPath = StorageSecurityUtil.normalizePath(path);
        Path targetFile = resolveAndVerify(cleanPath);
        if (!Files.exists(targetFile) || Files.isDirectory(targetFile)) {
            throw new StorageException(StorageResultCode.OBJECT_NOT_FOUND, "存储对象不存在: " + cleanPath);
        }
        try {
            return Files.newInputStream(targetFile);
        } catch (IOException e) {
            throw new StorageException(StorageResultCode.DOWNLOAD_FAILED, "读取本地文件流失败: " + cleanPath, e);
        }
    }

    @Override
    public StorageObject getMetadata(String path) {
        String cleanPath = StorageSecurityUtil.normalizePath(path);
        Path targetFile = resolveAndVerify(cleanPath);
        if (!Files.exists(targetFile) || Files.isDirectory(targetFile)) {
            throw new StorageException(StorageResultCode.OBJECT_NOT_FOUND, "存储对象不存在: " + cleanPath);
        }
        try {
            long size = Files.size(targetFile);
            Instant lastModified = Files.getLastModifiedTime(targetFile).toInstant();
            String contentType = StorageSecurityUtil.determineContentType(cleanPath);
            String etag = calculateEtag(targetFile);

            return StorageObject.builder()
                    .path(cleanPath)
                    .filename(FileUtil.cleanFilename(cleanPath))
                    .size(size)
                    .contentType(contentType)
                    .etag(etag)
                    .lastModified(lastModified)
                    .build();
        } catch (IOException e) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "获取本地文件元数据失败: " + cleanPath, e);
        }
    }

    @Override
    public boolean exists(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        try {
            String cleanPath = StorageSecurityUtil.normalizePath(path);
            Path targetFile = resolveAndVerify(cleanPath);
            return Files.exists(targetFile) && !Files.isDirectory(targetFile);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public boolean delete(String path) {
        if (path == null || path.isBlank()) {
            return true;
        }
        try {
            String cleanPath = StorageSecurityUtil.normalizePath(path);
            Path targetFile = resolveAndVerify(cleanPath);
            if (!Files.exists(targetFile)) {
                // 天然幂等：对象本身不存在即视为删除成功
                return true;
            }
            Files.deleteIfExists(targetFile);
            return true;
        } catch (StorageException e) {
            throw e;
        } catch (IOException e) {
            throw new StorageException(StorageResultCode.DELETE_FAILED, "删除本地存储对象失败: " + path, e);
        }
    }

    @Override
    public boolean deleteBatch(List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return true;
        }
        for (String path : paths) {
            delete(path);
        }
        return true;
    }

    @Override
    public boolean copy(String sourcePath, String targetPath) {
        if (sourcePath == null || sourcePath.isBlank()) {
            throw new StorageException(StorageResultCode.INVALID_PATH, "源文件路径不能为空");
        }
        if (targetPath == null || targetPath.isBlank()) {
            throw new StorageException(StorageResultCode.INVALID_PATH, "目标文件路径不能为空");
        }
        String cleanSource = StorageSecurityUtil.normalizePath(sourcePath);
        String cleanTarget = StorageSecurityUtil.normalizePath(targetPath);
        Path src = resolveAndVerify(cleanSource);
        if (!Files.exists(src) || Files.isDirectory(src)) {
            throw new StorageException(StorageResultCode.OBJECT_NOT_FOUND, "源文件不存在或为目录: " + sourcePath);
        }
        Path dst = resolveAndVerify(cleanTarget);
        try {
            if (dst.getParent() != null && !Files.exists(dst.getParent())) {
                Files.createDirectories(dst.getParent());
            }
            Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
            return true;
        } catch (IOException e) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "复制本地文件失败: " + sourcePath + " -> " + targetPath, e);
        }
    }

    @Override
    public boolean move(String sourcePath, String targetPath) {
        if (sourcePath == null || sourcePath.isBlank()) {
            throw new StorageException(StorageResultCode.INVALID_PATH, "源文件路径不能为空");
        }
        if (targetPath == null || targetPath.isBlank()) {
            throw new StorageException(StorageResultCode.INVALID_PATH, "目标文件路径不能为空");
        }
        String cleanSource = StorageSecurityUtil.normalizePath(sourcePath);
        String cleanTarget = StorageSecurityUtil.normalizePath(targetPath);
        Path src = resolveAndVerify(cleanSource);
        if (!Files.exists(src) || Files.isDirectory(src)) {
            throw new StorageException(StorageResultCode.OBJECT_NOT_FOUND, "源文件不存在或为目录: " + sourcePath);
        }
        Path dst = resolveAndVerify(cleanTarget);
        try {
            if (dst.getParent() != null && !Files.exists(dst.getParent())) {
                Files.createDirectories(dst.getParent());
            }
            try {
                Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "移动本地文件失败: " + sourcePath + " -> " + targetPath, e);
        }
    }

    @Override
    public List<StorageObject> list(String prefix, boolean recursive) {
        String cleanPrefix = (prefix != null && !prefix.isBlank())
                ? StorageSecurityUtil.normalizePath(prefix)
                : "";
        Path targetDir = resolveAndVerify(cleanPrefix);
        if (!Files.exists(targetDir)) {
            return Collections.emptyList();
        }
        if (Files.isRegularFile(targetDir)) {
            return List.of(getMetadata(cleanPrefix));
        }

        try {
            if (!recursive) {
                try (Stream<Path> stream = Files.list(targetDir)) {
                    return stream
                            .filter(p -> !p.getFileName().toString().startsWith("."))
                            .sorted()
                            .map(p -> {
                                String relPath = basePath.relativize(p).toString().replace('\\', '/');
                                if (Files.isDirectory(p)) {
                                    Instant lastModified = Instant.EPOCH;
                                    try {
                                        lastModified = Files.getLastModifiedTime(p).toInstant();
                                    } catch (IOException ignored) {
                                    }
                                    return StorageObject.builder()
                                            .path(relPath)
                                            .filename(p.getFileName().toString())
                                            .directory(true)
                                            .size(0L)
                                            .lastModified(lastModified)
                                            .build();
                                } else {
                                    return getMetadata(relPath);
                                }
                            })
                            .toList();
                }
            } else {
                try (Stream<Path> stream = Files.walk(targetDir)) {
                    return stream
                            .filter(p -> !p.equals(targetDir))
                            .filter(Files::isRegularFile)
                            .filter(p -> {
                                Path relToTarget = targetDir.relativize(p);
                                for (Path part : relToTarget) {
                                    if (part.toString().startsWith(".")) {
                                        return false;
                                    }
                                }
                                return true;
                            })
                            .sorted()
                            .map(p -> {
                                String relPath = basePath.relativize(p).toString().replace('\\', '/');
                                return getMetadata(relPath);
                            })
                            .toList();
                }
            }
        } catch (IOException e) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "列举本地存储目录失败: " + prefix, e);
        }
    }

    @Override
    public PresignedUrl getPresignedUrl(String path, Duration duration) {
        String cleanPath = StorageSecurityUtil.normalizePath(path);
        Duration effectiveDuration = (duration != null && !duration.isNegative() && !duration.isZero())
                ? duration
                : Duration.ofMinutes(15);
        Instant expiration = Instant.now().plus(effectiveDuration);
        long expires = expiration.getEpochSecond();

        String signature = StorageSecurityUtil.hmacSha256(cleanPath + ":" + expires, this.secretKey);

        String baseUrl = (domain != null && !domain.isBlank()) ? domain.trim() : "";
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String endpoint = (downloadEndpoint != null && !downloadEndpoint.isBlank()) ? downloadEndpoint.trim() : DEFAULT_DOWNLOAD_ENDPOINT;
        if (!endpoint.startsWith("/")) {
            endpoint = "/" + endpoint;
        }

        String url = baseUrl + endpoint + "?path=" + cleanPath + "&expires=" + expires + "&signature=" + signature;

        return PresignedUrl.builder()
                .url(url)
                .expiration(expiration)
                .httpMethod("GET")
                .build();
    }

    /**
     * 校验并将安全路径解析为物理 Path，防御根目录逃逸
     *
     * @param cleanPath 经过 normalizePath 清洗后的安全相对路径
     * @return 解析后的绝对 Path
     */
    private Path resolveAndVerify(String cleanPath) {
        Path target = basePath.resolve(cleanPath).normalize();
        if (!target.startsWith(basePath)) {
            throw new StorageException(StorageResultCode.INVALID_PATH, "目标路径超出存储根目录边界: " + cleanPath);
        }
        return target;
    }

    /**
     * 计算本地文件 MD5 哈希作为 ETag
     *
     * @param file 物理文件路径
     * @return MD5 十六进制字符串；发生异常时返回空串
     */
    private String calculateEtag(Path file) {
        try (InputStream is = Files.newInputStream(file)) {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = is.read(buffer)) != -1) {
                md.update(buffer, 0, read);
            }
            byte[] digest = md.digest();
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private static final String CHUNKS_DIR_NAME = ".chunks";
    private static final Pattern UPLOAD_ID_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]+$");

    @Override
    public String initiateChunkedUpload(String path) {
        if (path == null || path.isBlank()) {
            throw new StorageException(StorageResultCode.INVALID_PATH, "分片上传目标路径不能为空");
        }
        StorageSecurityUtil.normalizePath(path);
        String uploadId = UUID.randomUUID().toString().replace("-", "");
        Path chunkDir = resolveChunkDir(uploadId);
        try {
            Files.createDirectories(chunkDir);
        } catch (IOException e) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "创建分片暂存目录失败: " + uploadId, e);
        }
        return uploadId;
    }

    @Override
    public void uploadChunk(String uploadId, int chunkIndex, InputStream in, long size) {
        if (in == null) {
            throw new StorageException(StorageResultCode.UPLOAD_FAILED, "分片数据输入流不能为空");
        }
        validateUploadId(uploadId);
        if (chunkIndex < 1) {
            throw new StorageException(StorageResultCode.STORAGE_CHUNK_INVALID, "分片索引必须 >= 1 (1-based)，当前为: " + chunkIndex);
        }
        Path chunkDir = resolveChunkDir(uploadId);
        if (!Files.exists(chunkDir)) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "未找到分片上传会话或已被清理: " + uploadId);
        }
        Path chunkFile = chunkDir.resolve("chunk_" + chunkIndex);
        try {
            Files.copy(in, chunkFile, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new StorageException(StorageResultCode.UPLOAD_FAILED, "写入第 " + chunkIndex + " 个分片失败: " + uploadId, e);
        }
    }

    @Override
    public StorageObject completeChunkedUpload(String uploadId, String targetPath, int totalChunks) {
        validateUploadId(uploadId);
        if (totalChunks < 1) {
            throw new StorageException(StorageResultCode.STORAGE_CHUNK_INVALID, "分片总片数必须 >= 1，当前为: " + totalChunks);
        }
        String cleanPath = StorageSecurityUtil.normalizePath(targetPath);
        Path chunkDir = resolveChunkDir(uploadId);
        if (!Files.exists(chunkDir)) {
            throw new StorageException(StorageResultCode.STORAGE_CHUNK_MISSING, "未找到分片上传会话或已被清理: " + uploadId);
        }

        // 严格校验 1 ~ totalChunks 连续性
        for (int i = 1; i <= totalChunks; i++) {
            Path chunkFile = chunkDir.resolve("chunk_" + i);
            if (!Files.exists(chunkFile)) {
                throw new StorageException(StorageResultCode.STORAGE_CHUNK_MISSING,
                        "分片合并失败，缺失第 " + i + " 个分片（预期总片数: " + totalChunks + "）");
            }
        }

        Path targetFile = resolveAndVerify(cleanPath);
        try {
            if (targetFile.getParent() != null && !Files.exists(targetFile.getParent())) {
                Files.createDirectories(targetFile.getParent());
            }

            // 正序流式顺序拼接合并分片
            try (FileChannel outChannel = FileChannel.open(targetFile,
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                for (int i = 1; i <= totalChunks; i++) {
                    Path chunkFile = chunkDir.resolve("chunk_" + i);
                    try (FileChannel inChannel = FileChannel.open(chunkFile, StandardOpenOption.READ)) {
                        long chunkSize = inChannel.size();
                        long transferred = 0;
                        while (transferred < chunkSize) {
                            long bytes = inChannel.transferTo(transferred, chunkSize - transferred, outChannel);
                            if (bytes <= 0) {
                                break;
                            }
                            transferred += bytes;
                        }
                    }
                }
            }

            long actualSize = Files.size(targetFile);
            String contentType = StorageSecurityUtil.determineContentType(cleanPath);
            Instant lastModified = Files.getLastModifiedTime(targetFile).toInstant();
            String etag = calculateEtag(targetFile);

            // 合并成功后即时清理临时碎片
            deleteRecursively(chunkDir);

            return StorageObject.builder()
                    .path(cleanPath)
                    .filename(FileUtil.cleanFilename(cleanPath))
                    .size(actualSize)
                    .contentType(contentType)
                    .etag(etag)
                    .lastModified(lastModified)
                    .build();
        } catch (IOException e) {
            throw new StorageException(StorageResultCode.UPLOAD_FAILED, "合并分片写入目标文件失败: " + cleanPath, e);
        }
    }

    @Override
    public void abortChunkedUpload(String uploadId) {
        validateUploadId(uploadId);
        Path chunkDir = resolveChunkDir(uploadId);
        deleteRecursively(chunkDir);
    }

    private void validateUploadId(String uploadId) {
        if (uploadId == null || uploadId.isBlank() || !UPLOAD_ID_PATTERN.matcher(uploadId).matches()) {
            throw new StorageException(StorageResultCode.INVALID_PATH, "非法的分片上传会话标识 uploadId: " + uploadId);
        }
    }

    private Path resolveChunkDir(String uploadId) {
        return this.basePath.resolve(CHUNKS_DIR_NAME).resolve(uploadId).normalize();
    }

    private void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder())
                    .forEach(p -> {
                        try {
                            Files.deleteIfExists(p);
                        } catch (IOException ignored) {
                        }
                    });
        } catch (IOException ignored) {
        }
    }
}
