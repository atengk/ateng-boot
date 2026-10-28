package io.github.atengk.boot.storage.driver;

import io.github.atengk.boot.core.util.FileUtil;
import io.github.atengk.boot.storage.exception.StorageException;
import io.github.atengk.boot.storage.exception.StorageResultCode;
import io.github.atengk.boot.storage.model.PresignedDownloadRequest;
import io.github.atengk.boot.storage.model.PresignedUploadRequest;
import io.github.atengk.boot.storage.model.PresignedUrl;
import io.github.atengk.boot.storage.model.StorageObject;
import io.github.atengk.boot.storage.util.StorageSecurityUtil;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 本地磁盘文件存储驱动实现
 * 基于 Java NIO，提供深度路径防穿越清洗、MIME 推导、天然幂等删除保障与 HMAC-SHA256 预签名下载
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Slf4j
@Getter
public class LocalDriver implements StorageDriver {

    /**
     * 默认预签名下载端点路径
     */
    public static final String DEFAULT_DOWNLOAD_ENDPOINT = "/storage/local/download";

    /**
     * 默认预签名写入直传端点路径
     */
    public static final String DEFAULT_UPLOAD_ENDPOINT = "/storage/local/upload";

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
     * 预签名写入安全直传端点路径
     */
    private final String uploadEndpoint;

    /**
     * 绑定的命名存储源标识（多源模式下用于生成带 source 参数的外链）
     */
    private final String sourceName;

    /**
     * 本地存储对象标签并发映射容器
     */
    private final Map<String, Map<String, String>> objectTagsMap = new ConcurrentHashMap<>();

    /**
     * 构造基础本地磁盘驱动（自动生成随机密钥）
     *
     * @param basePath 本地存储物理基准路径
     */
    public LocalDriver(String basePath) {
        this(basePath, null, null, DEFAULT_DOWNLOAD_ENDPOINT, DEFAULT_UPLOAD_ENDPOINT, null);
    }

    /**
     * 基础参数构造本地磁盘驱动（带访问域名）
     *
     * @param basePath 本地存储物理基准路径
     * @param domain   访问域名或基础 URL
     */
    public LocalDriver(String basePath, String domain) {
        this(basePath, domain, null, DEFAULT_DOWNLOAD_ENDPOINT, DEFAULT_UPLOAD_ENDPOINT, null);
    }

    /**
     * 常用参数构造本地磁盘驱动
     *
     * @param basePath  本地存储物理基准路径
     * @param domain    访问域名或基础 URL
     * @param secretKey 签名密钥（留空时自动生成安全随机密钥）
     */
    public LocalDriver(String basePath, String domain, String secretKey) {
        this(basePath, domain, secretKey, DEFAULT_DOWNLOAD_ENDPOINT, DEFAULT_UPLOAD_ENDPOINT, null);
    }

    /**
     * 参数构造本地磁盘驱动
     *
     * @param basePath         本地存储物理基准路径
     * @param domain           访问域名或基础 URL
     * @param secretKey        签名密钥（留空时自动生成安全随机密钥）
     * @param downloadEndpoint 预签名安全下载端点
     */
    public LocalDriver(String basePath, String domain, String secretKey, String downloadEndpoint) {
        this(basePath, domain, secretKey, downloadEndpoint, DEFAULT_UPLOAD_ENDPOINT, null);
    }

    /**
     * 完整参数构造本地磁盘驱动
     *
     * @param basePath         本地存储物理基准路径
     * @param domain           访问域名或基础 URL
     * @param secretKey        签名密钥（留空时自动生成安全随机密钥）
     * @param downloadEndpoint 预签名安全下载端点
     * @param uploadEndpoint   预签名安全直传端点
     */
    public LocalDriver(String basePath, String domain, String secretKey, String downloadEndpoint, String uploadEndpoint) {
        this(basePath, domain, secretKey, downloadEndpoint, uploadEndpoint, null);
    }

    /**
     * 完整多源参数构造本地磁盘驱动
     *
     * @param basePath         本地存储物理基准路径
     * @param domain           访问域名或基础 URL
     * @param secretKey        签名密钥（留空时自动生成安全随机密钥）
     * @param downloadEndpoint 预签名安全下载端点
     * @param uploadEndpoint   预签名安全直传端点
     * @param sourceName       绑定的命名存储源名称（多源模式下使用）
     */
    public LocalDriver(String basePath, String domain, String secretKey, String downloadEndpoint, String uploadEndpoint, String sourceName) {
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
        this.uploadEndpoint = (uploadEndpoint != null && !uploadEndpoint.isBlank())
                ? uploadEndpoint
                : DEFAULT_UPLOAD_ENDPOINT;
        this.sourceName = (sourceName != null && !sourceName.isBlank())
                ? sourceName.trim()
                : null;
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
    public StorageObject upload(String path, InputStream in, String contentType, long size, Map<String, String> userMetadata) {
        StorageObject obj = upload(path, in, contentType, size);
        if (userMetadata != null && !userMetadata.isEmpty()) {
            obj.setUserMetadata(Map.copyOf(userMetadata));
        }
        return obj;
    }

    @Override
    public void ping() throws Exception {
        if (!Files.exists(this.basePath)) {
            throw new StorageException(StorageResultCode.STORAGE_HEALTH_CHECK_FAILED, "本地存储基准目录不存在: " + this.basePath);
        }
        if (!Files.isDirectory(this.basePath)) {
            throw new StorageException(StorageResultCode.STORAGE_HEALTH_CHECK_FAILED, "本地存储基准路径不是目录: " + this.basePath);
        }
        if (!Files.isWritable(this.basePath)) {
            throw new StorageException(StorageResultCode.STORAGE_HEALTH_CHECK_FAILED, "本地存储基准目录无写入权限: " + this.basePath);
        }
    }

    @Override
    public Map<String, Object> getDiagnostics() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", "local");
        map.put("basePath", this.basePath.toString());
        return map;
    }

    /**
     * 获取本地物理基准路径
     *
     * @return 基准路径
     */
    public Path getBasePath() {
        return this.basePath;
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
    public InputStream download(String path, long startOffset, long length) {
        if (startOffset < 0) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "下载起始偏移量不能为负数: " + startOffset);
        }
        if (length <= 0) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "下载读取长度必须大于 0: " + length);
        }
        String cleanPath = StorageSecurityUtil.normalizePath(path);
        Path targetFile = resolveAndVerify(cleanPath);
        if (!Files.exists(targetFile) || Files.isDirectory(targetFile)) {
            throw new StorageException(StorageResultCode.OBJECT_NOT_FOUND, "存储对象不存在: " + cleanPath);
        }
        try {
            long fileSize = Files.size(targetFile);
            if (startOffset >= fileSize) {
                throw new StorageException(StorageResultCode.OPERATION_FAILED, "下载起始偏移量超出文件大小: offset=" + startOffset + ", fileSize=" + fileSize);
            }
            FileChannel channel = FileChannel.open(targetFile, StandardOpenOption.READ);
            try {
                channel.position(startOffset);
                InputStream rawStream = Channels.newInputStream(channel);
                return new BoundedInputStream(rawStream, length);
            } catch (Exception e) {
                try {
                    channel.close();
                } catch (IOException closeEx) {
                    log.warn("分段读取异常释放 FileChannel 失败: path={}", cleanPath, closeEx);
                }
                throw e;
            }
        } catch (IOException e) {
            throw new StorageException(StorageResultCode.DOWNLOAD_FAILED, "分段读取本地文件失败: " + cleanPath, e);
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
    public Map<String, String> getObjectTags(String path) {
        if (path == null || path.isBlank()) {
            throw new StorageException(StorageResultCode.INVALID_PATH, "存储路径不能为空");
        }
        String cleanPath = StorageSecurityUtil.normalizePath(path);
        Path targetFile = resolveAndVerify(cleanPath);
        if (!Files.exists(targetFile) || Files.isDirectory(targetFile)) {
            throw new StorageException(StorageResultCode.OBJECT_NOT_FOUND, "存储对象不存在: " + cleanPath);
        }
        Map<String, String> tags = objectTagsMap.get(cleanPath);
        return tags != null ? Collections.unmodifiableMap(tags) : Collections.emptyMap();
    }

    @Override
    public void setObjectTags(String path, Map<String, String> tags) {
        if (path == null || path.isBlank()) {
            throw new StorageException(StorageResultCode.INVALID_PATH, "存储路径不能为空");
        }
        if (tags == null || tags.isEmpty()) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "设置对象标签不能为空");
        }
        String cleanPath = StorageSecurityUtil.normalizePath(path);
        Path targetFile = resolveAndVerify(cleanPath);
        if (!Files.exists(targetFile) || Files.isDirectory(targetFile)) {
            throw new StorageException(StorageResultCode.OBJECT_NOT_FOUND, "存储对象不存在: " + cleanPath);
        }
        objectTagsMap.put(cleanPath, Map.copyOf(tags));
    }

    @Override
    public void deleteObjectTags(String path) {
        if (path == null || path.isBlank()) {
            throw new StorageException(StorageResultCode.INVALID_PATH, "存储路径不能为空");
        }
        String cleanPath = StorageSecurityUtil.normalizePath(path);
        objectTagsMap.remove(cleanPath);
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
            objectTagsMap.remove(cleanPath);
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
    public boolean deletePrefix(String prefix) {
        String cleanPrefix = StorageSecurityUtil.validatePrefixForDeletion(prefix);
        Path target = resolveAndVerify(cleanPrefix);
        try {
            if (cleanPrefix.endsWith("/")) {
                // 1. 若前缀明确以 / 结尾，代表目录前缀删除（如 temp/logs/），仅删除该目录自身及内容
                if (Files.exists(target) && Files.isDirectory(target)) {
                    deleteRecursively(target);
                }
            } else {
                // 2. 若不以 / 结尾，代表按前缀匹配删除（如 temp/log 匹配 temp/log.txt、temp/logs 等）
                if (Files.exists(target)) {
                    deleteRecursively(target);
                }
                Path parent = target.getParent();
                if (parent != null && Files.exists(parent) && Files.isDirectory(parent)) {
                    String prefixName = target.getFileName().toString();
                    try (Stream<Path> stream = Files.list(parent)) {
                        stream.filter(p -> p.getFileName().toString().startsWith(prefixName))
                                .forEach(this::deleteRecursively);
                    }
                }
            }
            return true;
        } catch (IOException e) {
            throw new StorageException(StorageResultCode.DELETE_FAILED, "批量删除本地存储对象失败: " + prefix, e);
        }
    }

    @Override
    public boolean deleteDirectory(String dirPath) {
        String cleanDir = StorageSecurityUtil.validatePrefixForDeletion(dirPath);
        Path target = resolveAndVerify(cleanDir);
        if (Files.exists(target) && Files.isDirectory(target)) {
            deleteRecursively(target);
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
                            .filter(p -> !isHiddenOrSystemFile(p.getFileName().toString()))
                            .sorted()
                            .map(p -> {
                                String relPath = basePath.relativize(p).toString().replace('\\', '/');
                                if (Files.isDirectory(p)) {
                                    Instant lastModified = Instant.EPOCH;
                                    try {
                                        lastModified = Files.getLastModifiedTime(p).toInstant();
                                    } catch (IOException e) {
                                        log.debug("获取本地目录修改时间失败: path={}, cause={}", p, e.getMessage());
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
                                    if (isHiddenOrSystemFile(part.toString())) {
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
    public String getUrl(String path) {
        String cleanPath = StorageSecurityUtil.normalizePath(path);
        return StorageSecurityUtil.formatDomainUrl(this.domain, cleanPath);
    }

    @Override
    public PresignedUrl getPresignedUrl(PresignedDownloadRequest request) {
        if (request == null || request.getPath() == null || request.getPath().isBlank()) {
            throw new StorageException(StorageResultCode.INVALID_PATH, "预签名下载路径不能为空");
        }
        String cleanPath = StorageSecurityUtil.normalizePath(request.getPath());
        Duration duration = request.getDuration();
        Duration effectiveDuration = (duration != null && !duration.isNegative() && !duration.isZero())
                ? duration
                : Duration.ofMinutes(15);
        Instant expiration = Instant.now().plus(effectiveDuration);
        long expires = expiration.getEpochSecond();

        String payload = StorageSecurityUtil.buildPresignedDownloadSignaturePayload(
                cleanPath, expires, request.getDownloadFilename(), request.getResponseContentType(), request.getResponseContentDisposition());
        String signature = StorageSecurityUtil.hmacSha256(payload, this.secretKey);
        StringBuilder urlBuilder = new StringBuilder();
        urlBuilder.append(buildEndpointUrl(downloadEndpoint, DEFAULT_DOWNLOAD_ENDPOINT))
                .append("?path=").append(cleanPath)
                .append("&expires=").append(expires)
                .append("&signature=").append(signature);

        if (request.getDownloadFilename() != null && !request.getDownloadFilename().isBlank()) {
            urlBuilder.append("&filename=").append(URLEncoder.encode(request.getDownloadFilename().trim(), StandardCharsets.UTF_8));
        }
        if (request.getResponseContentType() != null && !request.getResponseContentType().isBlank()) {
            urlBuilder.append("&contentType=").append(URLEncoder.encode(request.getResponseContentType().trim(), StandardCharsets.UTF_8));
        }
        if (request.getResponseContentDisposition() != null && !request.getResponseContentDisposition().isBlank()) {
            urlBuilder.append("&disposition=").append(URLEncoder.encode(request.getResponseContentDisposition().trim(), StandardCharsets.UTF_8));
        }

        return PresignedUrl.builder()
                .url(urlBuilder.toString())
                .expiration(expiration)
                .httpMethod("GET")
                .build();
    }

    @Override
    public PresignedUrl getPresignedUploadUrl(PresignedUploadRequest request) {
        if (request == null) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "预签名直传请求参数不能为空");
        }
        String cleanPath = StorageSecurityUtil.normalizePath(request.getPath());
        Duration effectiveDuration = (request.getDuration() != null && !request.getDuration().isNegative() && !request.getDuration().isZero())
                ? request.getDuration()
                : Duration.ofMinutes(15);
        Instant expiration = Instant.now().plus(effectiveDuration);
        long expires = expiration.getEpochSecond();
        String contentType = (request.getContentType() != null && !request.getContentType().isBlank())
                ? request.getContentType().trim()
                : "";
        long maxBytes = request.getMaxSizeBytes() > 0 ? request.getMaxSizeBytes() : -1;

        String effectiveSource = (request.getSource() != null && !request.getSource().isBlank())
                ? request.getSource().trim()
                : this.sourceName;

        // 签名计算规则：cleanPath:expires:contentType:maxBytes[:source] 与 LocalStorageUploadController 保持严格对称
        String signPayload = cleanPath + ":" + expires + ":" + contentType + ":" + maxBytes
                + (effectiveSource != null && !effectiveSource.isBlank() ? ":" + effectiveSource : "");
        String signature = StorageSecurityUtil.hmacSha256(signPayload, this.secretKey);

        StringBuilder urlBuilder = new StringBuilder();
        urlBuilder.append(buildEndpointUrl(uploadEndpoint, DEFAULT_UPLOAD_ENDPOINT))
                .append("?path=").append(cleanPath)
                .append("&expires=").append(expires)
                .append("&signature=").append(signature);
        if (!contentType.isBlank()) {
            urlBuilder.append("&contentType=").append(URLEncoder.encode(contentType, StandardCharsets.UTF_8));
        }
        if (maxBytes > 0) {
            urlBuilder.append("&maxBytes=").append(maxBytes);
        }
        if (effectiveSource != null && !effectiveSource.isBlank()) {
            urlBuilder.append("&source=").append(URLEncoder.encode(effectiveSource, StandardCharsets.UTF_8));
        }

        Map<String, String> signedHeaders = new LinkedHashMap<>();
        if (!contentType.isBlank()) {
            signedHeaders.put("Content-Type", contentType);
        }
        if (request.getHeaders() != null && !request.getHeaders().isEmpty()) {
            signedHeaders.putAll(request.getHeaders());
        }

        return PresignedUrl.builder()
                .url(urlBuilder.toString())
                .expiration(expiration)
                .httpMethod("PUT")
                .headers(signedHeaders)
                .build();
    }

    @Override
    public PresignedUrl getPresignedUploadPartUrl(String path, String uploadId, int chunkIndex, Duration duration) {
        String cleanPath = StorageSecurityUtil.normalizePath(path);
        validateUploadId(uploadId);
        if (chunkIndex < 1) {
            throw new StorageException(StorageResultCode.STORAGE_CHUNK_INVALID, "分片索引必须 >= 1 (1-based)，当前为: " + chunkIndex);
        }
        Path chunkDir = resolveChunkDir(uploadId);
        if (!Files.exists(chunkDir)) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "未找到分片上传会话或已被清理: " + uploadId);
        }

        Duration effectiveDuration = (duration != null && !duration.isNegative() && !duration.isZero())
                ? duration
                : Duration.ofMinutes(15);
        Instant expiration = Instant.now().plus(effectiveDuration);
        long expires = expiration.getEpochSecond();

        String effectiveSource = this.sourceName;

        // 签名计算规则：cleanPath:expires:uploadId:chunkIndex[:source] 与 LocalStorageUploadController 严格对称
        String signPayload = cleanPath + ":" + expires + ":" + uploadId + ":" + chunkIndex
                + (effectiveSource != null && !effectiveSource.isBlank() ? ":" + effectiveSource : "");
        String signature = StorageSecurityUtil.hmacSha256(signPayload, this.secretKey);

        StringBuilder urlBuilder = new StringBuilder();
        urlBuilder.append(buildEndpointUrl(uploadEndpoint, DEFAULT_UPLOAD_ENDPOINT))
                .append("?path=").append(cleanPath)
                .append("&expires=").append(expires)
                .append("&uploadId=").append(uploadId)
                .append("&chunkIndex=").append(chunkIndex)
                .append("&signature=").append(signature);
        if (effectiveSource != null && !effectiveSource.isBlank()) {
            urlBuilder.append("&source=").append(URLEncoder.encode(effectiveSource, StandardCharsets.UTF_8));
        }

        return PresignedUrl.builder()
                .url(urlBuilder.toString())
                .expiration(expiration)
                .httpMethod("PUT")
                .build();
    }

    /**
     * 规范化拼装带基础域名与端点路径的完整 URL 前缀
     *
     * @param endpoint        配置的端点路径
     * @param defaultEndpoint 默认端点路径
     * @return 完整 URL 前缀
     */
    private String buildEndpointUrl(String endpoint, String defaultEndpoint) {
        String baseUrl = (domain != null && !domain.isBlank()) ? domain.trim() : "";
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String ep = (endpoint != null && !endpoint.isBlank()) ? endpoint.trim() : defaultEndpoint;
        if (!ep.startsWith("/")) {
            ep = "/" + ep;
        }
        return baseUrl + ep;
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
            throw new StorageException(StorageResultCode.STORAGE_CHUNK_EXPIRED, "未找到分片上传会话或已过期清理: " + uploadId);
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
            throw new StorageException(StorageResultCode.STORAGE_CHUNK_EXPIRED, "未找到分片上传会话或已过期清理: " + uploadId);
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
                        } catch (IOException e) {
                            log.warn("删除本地文件失败: path={}, cause={}", p, e.getMessage());
                        }
                    });
        } catch (IOException e) {
            log.warn("递归删除本地路径时遍历失败: dir={}, cause={}", dir, e.getMessage());
        }
    }

    private boolean isHiddenOrSystemFile(String name) {
        if (name == null || name.isBlank()) {
            return false;
        }
        return name.startsWith(".") || "desktop.ini".equalsIgnoreCase(name) || "thumbs.db".equalsIgnoreCase(name);
    }

    @Override
    public int cleanExpiredChunkSessions(Duration maxAge) {
        Duration effectiveMaxAge = (maxAge != null && !maxAge.isNegative() && !maxAge.isZero())
                ? maxAge
                : Duration.ofHours(24);
        Path chunksBaseDir = this.basePath.resolve(CHUNKS_DIR_NAME);
        if (!Files.exists(chunksBaseDir) || !Files.isDirectory(chunksBaseDir)) {
            return 0;
        }

        Instant threshold = Instant.now().minus(effectiveMaxAge);
        int cleanedCount = 0;
        try (Stream<Path> stream = Files.list(chunksBaseDir)) {
            List<Path> sessionDirs = stream.filter(Files::isDirectory).toList();
            for (Path sessionDir : sessionDirs) {
                Instant lastModified = getLastModifiedTimeSafely(sessionDir);
                if (lastModified != null && lastModified.isBefore(threshold)) {
                    deleteRecursively(sessionDir);
                    cleanedCount++;
                }
            }
        } catch (IOException e) {
            log.warn("扫描并清理本地孤儿分片会话失败: dir={}, cause={}", chunksBaseDir, e.getMessage());
        }
        return cleanedCount;
    }

    private Instant getLastModifiedTimeSafely(Path sessionDir) {
        try {
            Instant maxTime = Files.getLastModifiedTime(sessionDir).toInstant();
            try (Stream<Path> files = Files.list(sessionDir)) {
                for (Path p : files.toList()) {
                    Instant t = Files.getLastModifiedTime(p).toInstant();
                    if (t.isAfter(maxTime)) {
                        maxTime = t;
                    }
                }
            }
            return maxTime;
        } catch (Exception e) {
            log.debug("获取会话目录最后修改时间失败: sessionDir={}, cause={}", sessionDir, e.getMessage());
            return null;
        }
    }

    /**
     * 具备严格长度上限的受限包装输入流
     * 确保仅读取指定的字节长度后即返回 EOF，防止多读
     */
    private static class BoundedInputStream extends FilterInputStream {
        private long remaining;

        BoundedInputStream(InputStream in, long limit) {
            super(in);
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int result = in.read();
            if (result != -1) {
                remaining--;
            }
            return result;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (remaining <= 0) {
                return -1;
            }
            int toRead = (int) Math.min(len, remaining);
            int result = in.read(b, off, toRead);
            if (result != -1) {
                remaining -= result;
            }
            return result;
        }

        @Override
        public long skip(long n) throws IOException {
            long toSkip = Math.min(n, remaining);
            long skipped = in.skip(toSkip);
            remaining -= skipped;
            return skipped;
        }

        @Override
        public int available() throws IOException {
            return (int) Math.min(in.available(), remaining);
        }
    }
}
