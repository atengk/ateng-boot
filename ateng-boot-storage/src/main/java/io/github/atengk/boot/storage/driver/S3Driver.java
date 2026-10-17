package io.github.atengk.boot.storage.driver;

import io.github.atengk.boot.core.util.FileUtil;
import io.github.atengk.boot.storage.exception.StorageException;
import io.github.atengk.boot.storage.exception.StorageResultCode;
import io.github.atengk.boot.storage.model.PresignedUrl;
import io.github.atengk.boot.storage.model.StorageObject;
import io.github.atengk.boot.storage.properties.S3StorageProperties;
import io.github.atengk.boot.storage.util.StorageSecurityUtil;
import lombok.Getter;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.InputStream;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * 标准 S3 兼容协议对象存储驱动实现
 * 支持自建 MinIO、AWS S3、阿里云 OSS、腾讯云 COS、华为云 OBS 等对象存储；
 * 提供流式上传/下载、元数据获取、天然幂等删除、S3Presigner 预签名下载与直传外链能力，
 * 并将底层 S3Exception 优雅转译为统一受管的 StorageException
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Getter
public class S3Driver implements StorageDriver {

    /**
     * S3 客户端操作实例
     */
    private final S3Client s3Client;

    /**
     * S3 预签名器实例（可选，为空时无法使用预签名外链功能）
     */
    private final S3Presigner s3Presigner;

    /**
     * 目标存储桶名称
     */
    private final String bucket;

    /**
     * 自定义访问域名或 CDN 加速域名（可选）
     */
    private final String domain;

    /**
     * 基础构造函数（仅 S3Client）
     *
     * @param s3Client S3 客户端
     * @param bucket   存储桶名称
     */
    public S3Driver(S3Client s3Client, String bucket) {
        this(s3Client, null, bucket, null);
    }

    /**
     * 常用构造函数（包含 S3Presigner）
     *
     * @param s3Client    S3 客户端
     * @param s3Presigner S3 预签名器
     * @param bucket      存储桶名称
     */
    public S3Driver(S3Client s3Client, S3Presigner s3Presigner, String bucket) {
        this(s3Client, s3Presigner, bucket, null);
    }

    /**
     * 完整参数构造函数
     *
     * @param s3Client    S3 客户端
     * @param s3Presigner S3 预签名器
     * @param bucket      存储桶名称
     * @param domain      自定义访问域名或 CDN 加速域名
     */
    public S3Driver(S3Client s3Client, S3Presigner s3Presigner, String bucket, String domain) {
        if (s3Client == null) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "S3Client 不能为空");
        }
        if (bucket == null || bucket.isBlank()) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "S3 存储桶名称 bucket 不能为空");
        }
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
        this.bucket = bucket.trim();
        this.domain = domain;
    }

    /**
     * 根据 S3StorageProperties 配置快速构建 S3Client 实例
     *
     * @param properties S3 存储属性
     * @return S3Client 实例
     */
    public static S3Client createS3Client(S3StorageProperties properties) {
        if (properties == null) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "S3 配置属性不能为空");
        }
        S3ClientBuilder builder = S3Client.builder();
        if (properties.getRegion() != null && !properties.getRegion().isBlank()) {
            builder.region(Region.of(properties.getRegion()));
        }
        if (properties.getEndpoint() != null && !properties.getEndpoint().isBlank()) {
            builder.endpointOverride(URI.create(properties.getEndpoint()));
        }
        if (properties.getAccessKey() != null && !properties.getAccessKey().isBlank()) {
            AwsBasicCredentials credentials = AwsBasicCredentials.create(
                    properties.getAccessKey(),
                    properties.getSecretKey() != null ? properties.getSecretKey() : ""
            );
            builder.credentialsProvider(StaticCredentialsProvider.create(credentials));
        }
        builder.serviceConfiguration(S3Configuration.builder()
                .pathStyleAccessEnabled(properties.isPathStyle())
                .build());
        return builder.build();
    }

    /**
     * 根据 S3StorageProperties 配置快速构建 S3Presigner 实例
     *
     * @param properties S3 存储属性
     * @return S3Presigner 实例
     */
    public static S3Presigner createS3Presigner(S3StorageProperties properties) {
        if (properties == null) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "S3 配置属性不能为空");
        }
        S3Presigner.Builder builder = S3Presigner.builder();
        if (properties.getRegion() != null && !properties.getRegion().isBlank()) {
            builder.region(Region.of(properties.getRegion()));
        }
        if (properties.getEndpoint() != null && !properties.getEndpoint().isBlank()) {
            builder.endpointOverride(URI.create(properties.getEndpoint()));
        }
        if (properties.getAccessKey() != null && !properties.getAccessKey().isBlank()) {
            AwsBasicCredentials credentials = AwsBasicCredentials.create(
                    properties.getAccessKey(),
                    properties.getSecretKey() != null ? properties.getSecretKey() : ""
            );
            builder.credentialsProvider(StaticCredentialsProvider.create(credentials));
        }
        builder.serviceConfiguration(S3Configuration.builder()
                .pathStyleAccessEnabled(properties.isPathStyle())
                .build());
        return builder.build();
    }

    @Override
    public StorageObject upload(String path, InputStream in, String contentType, long size) {
        if (in == null) {
            throw new StorageException(StorageResultCode.UPLOAD_FAILED, "上传对象输入流不能为空");
        }
        String cleanPath = StorageSecurityUtil.normalizePath(path);
        String finalContentType = (contentType != null && !contentType.isBlank())
                ? contentType
                : StorageSecurityUtil.determineContentType(cleanPath);
        try {
            RequestBody requestBody;
            long actualSize;
            if (size >= 0) {
                actualSize = size;
                requestBody = RequestBody.fromInputStream(in, size);
            } else {
                byte[] bytes = in.readAllBytes();
                actualSize = bytes.length;
                requestBody = RequestBody.fromBytes(bytes);
            }

            PutObjectRequest putRequest = PutObjectRequest.builder()
                    .bucket(this.bucket)
                    .key(cleanPath)
                    .contentType(finalContentType)
                    .build();

            PutObjectResponse response = this.s3Client.putObject(putRequest, requestBody);
            String etag = stripQuotes(response.eTag());

            return StorageObject.builder()
                    .path(cleanPath)
                    .filename(FileUtil.cleanFilename(cleanPath))
                    .size(actualSize)
                    .contentType(finalContentType)
                    .etag(etag)
                    .lastModified(Instant.now())
                    .build();
        } catch (Exception e) {
            throw translateException(e, cleanPath, "上传对象到 S3 失败");
        }
    }

    @Override
    public InputStream download(String path) {
        String cleanPath = StorageSecurityUtil.normalizePath(path);
        try {
            GetObjectRequest getRequest = GetObjectRequest.builder()
                    .bucket(this.bucket)
                    .key(cleanPath)
                    .build();
            return this.s3Client.getObject(getRequest);
        } catch (Exception e) {
            throw translateException(e, cleanPath, "从 S3 下载对象失败");
        }
    }

    @Override
    public StorageObject getMetadata(String path) {
        String cleanPath = StorageSecurityUtil.normalizePath(path);
        try {
            HeadObjectRequest headRequest = HeadObjectRequest.builder()
                    .bucket(this.bucket)
                    .key(cleanPath)
                    .build();
            HeadObjectResponse response = this.s3Client.headObject(headRequest);
            String contentType = (response.contentType() != null && !response.contentType().isBlank())
                    ? response.contentType()
                    : StorageSecurityUtil.determineContentType(cleanPath);
            String etag = stripQuotes(response.eTag());

            return StorageObject.builder()
                    .path(cleanPath)
                    .filename(FileUtil.cleanFilename(cleanPath))
                    .size(response.contentLength())
                    .contentType(contentType)
                    .etag(etag)
                    .lastModified(response.lastModified())
                    .build();
        } catch (Exception e) {
            throw translateException(e, cleanPath, "获取 S3 对象元数据失败");
        }
    }

    @Override
    public boolean exists(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        String cleanPath = StorageSecurityUtil.normalizePath(path);
        try {
            HeadObjectRequest headRequest = HeadObjectRequest.builder()
                    .bucket(this.bucket)
                    .key(cleanPath)
                    .build();
            this.s3Client.headObject(headRequest);
            return true;
        } catch (NoSuchKeyException e) {
            return false;
        } catch (S3Exception e) {
            if (e.statusCode() == 404 || isNotFoundCode(e)) {
                return false;
            }
            throw translateException(e, cleanPath, "检查 S3 对象是否存在失败");
        } catch (Exception e) {
            throw translateException(e, cleanPath, "检查 S3 对象是否存在失败");
        }
    }

    @Override
    public boolean delete(String path) {
        if (path == null || path.isBlank()) {
            return true;
        }
        String cleanPath = StorageSecurityUtil.normalizePath(path);
        try {
            DeleteObjectRequest deleteRequest = DeleteObjectRequest.builder()
                    .bucket(this.bucket)
                    .key(cleanPath)
                    .build();
            this.s3Client.deleteObject(deleteRequest);
            return true;
        } catch (NoSuchKeyException e) {
            // 天然幂等
            return true;
        } catch (S3Exception e) {
            if (e.statusCode() == 404 || isNotFoundCode(e)) {
                return true;
            }
            throw translateException(e, cleanPath, "删除 S3 对象失败");
        } catch (Exception e) {
            throw translateException(e, cleanPath, "删除 S3 对象失败");
        }
    }

    @Override
    public boolean deleteBatch(List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return true;
        }
        List<ObjectIdentifier> toDelete = paths.stream()
                .filter(p -> p != null && !p.isBlank())
                .map(p -> ObjectIdentifier.builder().key(StorageSecurityUtil.normalizePath(p)).build())
                .toList();
        if (toDelete.isEmpty()) {
            return true;
        }
        try {
            DeleteObjectsRequest deleteRequest = DeleteObjectsRequest.builder()
                    .bucket(this.bucket)
                    .delete(Delete.builder().objects(toDelete).quiet(true).build())
                    .build();
            this.s3Client.deleteObjects(deleteRequest);
            return true;
        } catch (Exception e) {
            throw translateException(e, String.join(",", paths), "批量删除 S3 对象失败");
        }
    }

    @Override
    public PresignedUrl getPresignedUrl(String path, Duration duration) {
        if (this.s3Presigner == null) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "未配置 S3Presigner，无法生成预签名下载外链");
        }
        String cleanPath = StorageSecurityUtil.normalizePath(path);
        Duration effectiveDuration = (duration != null && !duration.isNegative() && !duration.isZero())
                ? duration
                : Duration.ofMinutes(15);
        try {
            GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                    .bucket(this.bucket)
                    .key(cleanPath)
                    .build();
            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(effectiveDuration)
                    .getObjectRequest(getObjectRequest)
                    .build();
            PresignedGetObjectRequest presigned = this.s3Presigner.presignGetObject(presignRequest);
            String finalUrl = applyDomain(presigned.url().toString(), this.domain);

            return PresignedUrl.builder()
                    .url(finalUrl)
                    .expiration(presigned.expiration())
                    .httpMethod("GET")
                    .build();
        } catch (Exception e) {
            throw translateException(e, cleanPath, "生成 S3 预签名下载外链失败");
        }
    }

    @Override
    public PresignedUrl getPresignedUploadUrl(String path, Duration duration) {
        if (this.s3Presigner == null) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "未配置 S3Presigner，无法生成预签名上传外链");
        }
        String cleanPath = StorageSecurityUtil.normalizePath(path);
        Duration effectiveDuration = (duration != null && !duration.isNegative() && !duration.isZero())
                ? duration
                : Duration.ofMinutes(15);
        try {
            PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                    .bucket(this.bucket)
                    .key(cleanPath)
                    .build();
            PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                    .signatureDuration(effectiveDuration)
                    .putObjectRequest(putObjectRequest)
                    .build();
            PresignedPutObjectRequest presigned = this.s3Presigner.presignPutObject(presignRequest);
            String finalUrl = applyDomain(presigned.url().toString(), this.domain);

            return PresignedUrl.builder()
                    .url(finalUrl)
                    .expiration(presigned.expiration())
                    .httpMethod("PUT")
                    .build();
        } catch (Exception e) {
            throw translateException(e, cleanPath, "生成 S3 预签名直传外链失败");
        }
    }

    /**
     * 将底层 AWS SDK 抛出的异常精准转译为受管的 StorageException
     *
     * @param e                 底层异常
     * @param path              操作对象路径
     * @param actionDescription 操作动作描述
     * @return 转译后的 StorageException
     */
    private StorageException translateException(Exception e, String path, String actionDescription) {
        if (e instanceof StorageException se) {
            return se;
        }
        if (e instanceof NoSuchKeyException) {
            return new StorageException(StorageResultCode.OBJECT_NOT_FOUND, "存储对象不存在: " + path, e);
        }
        if (e instanceof S3Exception s3Ex) {
            if (s3Ex.statusCode() == 404 || isNotFoundCode(s3Ex)) {
                return new StorageException(StorageResultCode.OBJECT_NOT_FOUND, "存储对象或存储桶不存在: " + path, e);
            }
            if (s3Ex.statusCode() == 403 || "AccessDenied".equalsIgnoreCase(getErrorCode(s3Ex))) {
                return new StorageException(StorageResultCode.OPERATION_FAILED, "S3 访问被拒绝 (403): " + path, e);
            }
            String errorCode = getErrorCode(s3Ex);
            String errorMsg = s3Ex.awsErrorDetails() != null ? s3Ex.awsErrorDetails().errorMessage() : s3Ex.getMessage();
            return new StorageException(StorageResultCode.OPERATION_FAILED,
                    String.format("%s [%s]: %s", actionDescription, errorCode, errorMsg), e);
        }
        if (e instanceof AwsServiceException awsEx) {
            return new StorageException(StorageResultCode.OPERATION_FAILED,
                    actionDescription + " (AWS异常): " + awsEx.getMessage(), e);
        }
        return new StorageException(StorageResultCode.OPERATION_FAILED,
                    actionDescription + ": " + e.getMessage(), e);
    }

    /**
     * 判断 S3Exception 是否为对象或桶不存在错误码
     */
    private boolean isNotFoundCode(S3Exception s3Ex) {
        String code = getErrorCode(s3Ex);
        return "NoSuchKey".equalsIgnoreCase(code)
                || "NoSuchBucket".equalsIgnoreCase(code)
                || "NotFound".equalsIgnoreCase(code);
    }

    /**
     * 获取 S3Exception 的错误码
     */
    private String getErrorCode(S3Exception s3Ex) {
        if (s3Ex.awsErrorDetails() != null && s3Ex.awsErrorDetails().errorCode() != null) {
            return s3Ex.awsErrorDetails().errorCode();
        }
        return "UNKNOWN";
    }

    /**
     * 清理 ETag 两侧可能包含的双引号
     *
     * @param etag 原始 ETag
     * @return 清理后的干净字符串
     */
    private String stripQuotes(String etag) {
        if (etag == null) {
            return "";
        }
        String trimmed = etag.trim();
        if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            return trimmed.substring(1, trimmed.length() - 1);
        }
        return trimmed;
    }

    /**
     * 若配置了自定义域名，将预签名原始 URL 替换为目标域名
     *
     * @param rawUrl       AWS SDK 生成的原始 URL
     * @param customDomain 用户配置的自定义域名
     * @return 替换后的最终 URL
     */
    private String applyDomain(String rawUrl, String customDomain) {
        if (customDomain == null || customDomain.isBlank()) {
            return rawUrl;
        }
        try {
            URI originalUri = URI.create(rawUrl);
            String domainPrefix = customDomain.trim();
            if (domainPrefix.endsWith("/")) {
                domainPrefix = domainPrefix.substring(0, domainPrefix.length() - 1);
            }
            String path = originalUri.getRawPath() != null ? originalUri.getRawPath() : "";
            String query = originalUri.getRawQuery() != null ? "?" + originalUri.getRawQuery() : "";
            return domainPrefix + path + query;
        } catch (Exception e) {
            return rawUrl;
        }
    }

    @Override
    public void close() {
        if (this.s3Client != null) {
            try {
                this.s3Client.close();
            } catch (Exception ignored) {
            }
        }
        if (this.s3Presigner != null) {
            try {
                this.s3Presigner.close();
            } catch (Exception ignored) {
            }
        }
    }
}
