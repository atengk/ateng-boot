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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

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
}
