package io.github.atengk.boot.storage.driver;

import io.github.atengk.boot.core.util.FileUtil;
import io.github.atengk.boot.storage.exception.StorageException;
import io.github.atengk.boot.storage.exception.StorageResultCode;
import io.github.atengk.boot.storage.model.PresignedDownloadRequest;
import io.github.atengk.boot.storage.model.PresignedUploadRequest;
import io.github.atengk.boot.storage.model.PresignedUrl;
import io.github.atengk.boot.storage.model.StorageObject;
import io.github.atengk.boot.storage.properties.S3StorageProperties;
import io.github.atengk.boot.storage.util.StorageSecurityUtil;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CommonPrefix;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.Delete;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsRequest;
import software.amazon.awssdk.services.s3.model.ListMultipartUploadsResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.ListPartsRequest;
import software.amazon.awssdk.services.s3.model.ListPartsResponse;
import software.amazon.awssdk.services.s3.model.MultipartUpload;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectCannedACL;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.Part;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedUploadPartRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.UploadPartPresignRequest;
import io.github.atengk.boot.storage.util.S3ChunkTokenUtil;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 标准 S3 兼容协议对象存储驱动实现
 * 支持自建 MinIO、AWS S3、阿里云 OSS、腾讯云 COS、华为云 OBS 等对象存储；
 * 提供流式上传/下载、元数据获取、天然幂等删除、S3Presigner 预签名下载与直传外链能力，
 * 并将底层 S3Exception 优雅转译为统一受管的 StorageException
 *
 * @author Ateng
 * @since 2026-10-17
 */
@Slf4j
@Getter
public class S3Driver implements StorageDriver {

    /**
     * AWS S3 分片上传规范最小分片容量 (5MB = 5,242,880 字节)
     * 除最后一个分片外，S3 协议严格限制每一片至少 5MB
     */
    public static final int MIN_MULTIPART_PART_SIZE = 5 * 1024 * 1024;

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
     * 用于无状态分片 Token HMAC-SHA256 签名的密钥
     */
    private final String secretKey;

    /**
     * S3 访问服务端点（可选）
     */
    private final String endpoint;

    /**
     * 是否开启 Path-Style 路径寻址模式
     */
    private final boolean pathStyle;

    /**
     * 基础构造函数（仅 S3Client）
     *
     * @param s3Client S3 客户端
     * @param bucket   存储桶名称
     */
    public S3Driver(S3Client s3Client, String bucket) {
        this(s3Client, null, bucket, null, null, null, true);
    }

    /**
     * 常用构造函数（包含 S3Presigner）
     *
     * @param s3Client    S3 客户端
     * @param s3Presigner S3 预签名器
     * @param bucket      存储桶名称
     */
    public S3Driver(S3Client s3Client, S3Presigner s3Presigner, String bucket) {
        this(s3Client, s3Presigner, bucket, null, null, null, true);
    }

    /**
     * 包含自定义域名的构造函数
     *
     * @param s3Client    S3 客户端
     * @param s3Presigner S3 预签名器
     * @param bucket      存储桶名称
     * @param domain      自定义访问域名或 CDN 加速域名
     */
    public S3Driver(S3Client s3Client, S3Presigner s3Presigner, String bucket, String domain) {
        this(s3Client, s3Presigner, bucket, domain, null, null, true);
    }

    /**
     * 完整参数构造函数（包含用于无状态分片 Token 的签名密钥）
     *
     * @param s3Client    S3 客户端
     * @param s3Presigner S3 预签名器
     * @param bucket      存储桶名称
     * @param domain      自定义访问域名或 CDN 加速域名
     * @param secretKey   HMAC 签名密钥（为空时自动生成安全随机密钥保底）
     */
    public S3Driver(S3Client s3Client, S3Presigner s3Presigner, String bucket, String domain, String secretKey) {
        this(s3Client, s3Presigner, bucket, domain, secretKey, null, true);
    }

    /**
     * 生产级全参构造函数（包含服务端点与寻址拓扑）
     *
     * @param s3Client    S3 客户端
     * @param s3Presigner S3 预签名器
     * @param bucket      存储桶名称
     * @param domain      自定义访问域名或 CDN 加速域名
     * @param secretKey   HMAC 签名密钥（为空时自动生成安全随机密钥保底）
     * @param endpoint    S3 物理服务端点
     * @param pathStyle   是否启用 Path-Style 寻址
     */
    public S3Driver(S3Client s3Client, S3Presigner s3Presigner, String bucket, String domain, String secretKey, String endpoint, boolean pathStyle) {
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
        this.secretKey = (secretKey != null && !secretKey.isBlank())
                ? secretKey.trim()
                : StorageSecurityUtil.generateRandomSecretKey();
        this.endpoint = endpoint;
        this.pathStyle = pathStyle;
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

        // 装配生产级 ApacheHttpClient 传输层与连接池超时治理
        ApacheHttpClient.Builder httpClientBuilder = ApacheHttpClient.builder();
        if (properties.getConnectTimeout() != null) {
            httpClientBuilder.connectionTimeout(properties.getConnectTimeout());
        }
        if (properties.getSocketTimeout() != null) {
            httpClientBuilder.socketTimeout(properties.getSocketTimeout());
        }
        if (properties.getConnectionAcquisitionTimeout() != null) {
            httpClientBuilder.connectionAcquisitionTimeout(properties.getConnectionAcquisitionTimeout());
        }
        if (properties.getMaxConnections() > 0) {
            httpClientBuilder.maxConnections(properties.getMaxConnections());
        }
        builder.httpClientBuilder(httpClientBuilder);

        if (properties.getApiCallTimeout() != null) {
            builder.overrideConfiguration(conf -> conf.apiCallTimeout(properties.getApiCallTimeout())
                    .apiCallAttemptTimeout(properties.getSocketTimeout()));
        }

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
        return upload(path, in, contentType, size, null, false);
    }

    @Override
    public StorageObject upload(String path, InputStream in, String contentType, long size, Map<String, String> userMetadata) {
        return upload(path, in, contentType, size, userMetadata, false);
    }

    @Override
    public StorageObject upload(String path, InputStream in, String contentType, long size, Map<String, String> userMetadata, boolean publicRead) {
        if (in == null) {
            throw new StorageException(StorageResultCode.UPLOAD_FAILED, "上传对象输入流不能为空");
        }
        String cleanPath = StorageSecurityUtil.normalizePath(path);
        String finalContentType = (contentType != null && !contentType.isBlank())
                ? contentType
                : StorageSecurityUtil.determineContentType(cleanPath);

        if (size >= 0) {
            // 已知字节长度：直接使用单请求 PutObject 流式写出，零内存堆积
            try {
                RequestBody requestBody = RequestBody.fromInputStream(in, size);
                PutObjectRequest.Builder putRequestBuilder = PutObjectRequest.builder()
                        .bucket(this.bucket)
                        .key(cleanPath)
                        .contentType(finalContentType);
                if (publicRead) {
                    putRequestBuilder.acl(ObjectCannedACL.PUBLIC_READ);
                }
                if (userMetadata != null && !userMetadata.isEmpty()) {
                    putRequestBuilder.metadata(userMetadata);
                }

                PutObjectResponse response = this.s3Client.putObject(putRequestBuilder.build(), requestBody);
                String etag = stripQuotes(response.eTag());

                return StorageObject.builder()
                        .path(cleanPath)
                        .filename(FileUtil.cleanFilename(cleanPath))
                        .size(size)
                        .contentType(finalContentType)
                        .etag(etag)
                        .userMetadata(userMetadata != null && !userMetadata.isEmpty() ? Map.copyOf(userMetadata) : Collections.emptyMap())
                        .lastModified(Instant.now())
                        .build();
            } catch (Exception e) {
                throw translateException(e, cleanPath, "上传对象到 S3 失败");
            }
        } else {
            // 未知流长度 (size < 0)：启动双阶自适应流式分片缓冲，峰值内存严格限制在 5MB，彻底根治 OOM
            return uploadAdaptiveStream(cleanPath, in, finalContentType, userMetadata, publicRead);
        }
    }

    /**
     * 未知长度流双阶自适应流式上传机制
     * 1. 首阶：以 5MB 为界限尝试预读；若流数据未满 5MB 或恰好在 5MB 处结束，以单请求 PutObject 极速完成；
     * 2. 次阶：若流数据超过 5MB，自动升级为标准 S3 Multipart Upload 会话，按 5MB 分批次上传碎片并在 EOF 完成合并；
     * 3. 容灾：任何环节发生异常，立即触发 abortMultipartUpload 确定性释放物理会话，杜绝孤儿碎片膨胀。
     */
    private StorageObject uploadAdaptiveStream(String cleanPath, InputStream in, String finalContentType,
                                              Map<String, String> userMetadata, boolean publicRead) {
        byte[] firstBuffer = new byte[MIN_MULTIPART_PART_SIZE];
        int firstRead;
        try {
            firstRead = in.readNBytes(firstBuffer, 0, MIN_MULTIPART_PART_SIZE);
        } catch (IOException e) {
            throw new StorageException(StorageResultCode.UPLOAD_FAILED, "读取待上传流失败: " + cleanPath, e);
        }

        // 阶段一：若流数据未满 5MB，判定为小文件，直接走单请求 PutObject
        if (firstRead < MIN_MULTIPART_PART_SIZE) {
            return putSingleObject(cleanPath, firstBuffer, firstRead, finalContentType, userMetadata, publicRead);
        }

        // 探查是否恰好在 5MB 处到达 EOF
        int nextByte;
        try {
            nextByte = in.read();
        } catch (IOException e) {
            throw new StorageException(StorageResultCode.UPLOAD_FAILED, "探查上传流结束标识失败: " + cleanPath, e);
        }
        if (nextByte == -1) {
            return putSingleObject(cleanPath, firstBuffer, firstRead, finalContentType, userMetadata, publicRead);
        }

        // 阶段二：流数据超过 5MB，无缝升级为 S3 分片流式上传 (Multipart Upload)
        String uploadId = null;
        try {
            CreateMultipartUploadRequest.Builder createBuilder = CreateMultipartUploadRequest.builder()
                    .bucket(this.bucket)
                    .key(cleanPath)
                    .contentType(finalContentType);
            if (publicRead) {
                createBuilder.acl(ObjectCannedACL.PUBLIC_READ);
            }
            if (userMetadata != null && !userMetadata.isEmpty()) {
                createBuilder.metadata(userMetadata);
            }
            CreateMultipartUploadResponse createResponse = this.s3Client.createMultipartUpload(createBuilder.build());
            uploadId = createResponse.uploadId();

            List<CompletedPart> completedParts = new ArrayList<>();
            long totalSize = 0;

            // 上传第一分片 (5MB)
            UploadPartRequest part1Request = UploadPartRequest.builder()
                    .bucket(this.bucket)
                    .key(cleanPath)
                    .uploadId(uploadId)
                    .partNumber(1)
                    .contentLength((long) MIN_MULTIPART_PART_SIZE)
                    .build();
            UploadPartResponse part1Resp = this.s3Client.uploadPart(
                    part1Request,
                    RequestBody.fromRemainingByteBuffer(ByteBuffer.wrap(firstBuffer, 0, MIN_MULTIPART_PART_SIZE))
            );
            completedParts.add(CompletedPart.builder().partNumber(1).eTag(part1Resp.eTag()).build());
            totalSize += MIN_MULTIPART_PART_SIZE;

            // 循环分批流式上传后续分片
            int partNumber = 2;
            byte[] partBuffer = new byte[MIN_MULTIPART_PART_SIZE];
            while (nextByte != -1) {
                partBuffer[0] = (byte) nextByte;
                int additionalRead = in.readNBytes(partBuffer, 1, MIN_MULTIPART_PART_SIZE - 1);
                int currentPartLength = 1 + additionalRead;

                if (currentPartLength < MIN_MULTIPART_PART_SIZE) {
                    // 到达 EOF，此为最后一个分片
                    UploadPartRequest partRequest = UploadPartRequest.builder()
                            .bucket(this.bucket)
                            .key(cleanPath)
                            .uploadId(uploadId)
                            .partNumber(partNumber)
                            .contentLength((long) currentPartLength)
                            .build();
                    UploadPartResponse partResp = this.s3Client.uploadPart(
                            partRequest,
                            RequestBody.fromRemainingByteBuffer(ByteBuffer.wrap(partBuffer, 0, currentPartLength))
                    );
                    completedParts.add(CompletedPart.builder().partNumber(partNumber).eTag(partResp.eTag()).build());
                    totalSize += currentPartLength;
                    break;
                }

                // 当前分片满 5MB，探查下一个字节以确认是否到达 EOF
                nextByte = in.read();
                if (nextByte == -1) {
                    // 恰好在 5MB 边界处到达 EOF，此为最后一个分片
                    UploadPartRequest partRequest = UploadPartRequest.builder()
                            .bucket(this.bucket)
                            .key(cleanPath)
                            .uploadId(uploadId)
                            .partNumber(partNumber)
                            .contentLength((long) MIN_MULTIPART_PART_SIZE)
                            .build();
                    UploadPartResponse partResp = this.s3Client.uploadPart(
                            partRequest,
                            RequestBody.fromRemainingByteBuffer(ByteBuffer.wrap(partBuffer, 0, MIN_MULTIPART_PART_SIZE))
                    );
                    completedParts.add(CompletedPart.builder().partNumber(partNumber).eTag(partResp.eTag()).build());
                    totalSize += MIN_MULTIPART_PART_SIZE;
                    break;
                }

                // 仍有后续数据，上传当前 5MB 分片并进入下一轮
                UploadPartRequest partRequest = UploadPartRequest.builder()
                        .bucket(this.bucket)
                        .key(cleanPath)
                        .uploadId(uploadId)
                        .partNumber(partNumber)
                        .contentLength((long) MIN_MULTIPART_PART_SIZE)
                        .build();
                UploadPartResponse partResp = this.s3Client.uploadPart(
                        partRequest,
                        RequestBody.fromRemainingByteBuffer(ByteBuffer.wrap(partBuffer, 0, MIN_MULTIPART_PART_SIZE))
                );
                completedParts.add(CompletedPart.builder().partNumber(partNumber).eTag(partResp.eTag()).build());
                totalSize += MIN_MULTIPART_PART_SIZE;
                partNumber++;
            }

            // 完成分片合并
            CompleteMultipartUploadRequest completeRequest = CompleteMultipartUploadRequest.builder()
                    .bucket(this.bucket)
                    .key(cleanPath)
                    .uploadId(uploadId)
                    .multipartUpload(CompletedMultipartUpload.builder().parts(completedParts).build())
                    .build();
            CompleteMultipartUploadResponse completeResponse = this.s3Client.completeMultipartUpload(completeRequest);
            String etag = stripQuotes(completeResponse.eTag());

            return StorageObject.builder()
                    .path(cleanPath)
                    .filename(FileUtil.cleanFilename(cleanPath))
                    .size(totalSize)
                    .contentType(finalContentType)
                    .etag(etag)
                    .userMetadata(userMetadata != null && !userMetadata.isEmpty() ? Map.copyOf(userMetadata) : Collections.emptyMap())
                    .lastModified(Instant.now())
                    .build();
        } catch (Exception e) {
            // 异常熔断与确定性清理现场：中止分片会话，防孤儿碎片堆积
            if (uploadId != null) {
                try {
                    this.s3Client.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                            .bucket(this.bucket)
                            .key(cleanPath)
                            .uploadId(uploadId)
                            .build());
                } catch (Exception abortEx) {
                    log.warn("自适应分片上传异常中止会话清理失败: bucket={}, key={}, uploadId={}", this.bucket, cleanPath, uploadId, abortEx);
                }
            }
            throw translateException(e, cleanPath, "自适应分片流式上传到 S3 失败");
        }
    }

    /**
     * 单请求 PutObject 上传辅助方法
     */
    private StorageObject putSingleObject(String cleanPath, byte[] buffer, int length, String finalContentType,
                                          Map<String, String> userMetadata, boolean publicRead) {
        try {
            PutObjectRequest.Builder putRequestBuilder = PutObjectRequest.builder()
                    .bucket(this.bucket)
                    .key(cleanPath)
                    .contentType(finalContentType);
            if (publicRead) {
                putRequestBuilder.acl(ObjectCannedACL.PUBLIC_READ);
            }
            if (userMetadata != null && !userMetadata.isEmpty()) {
                putRequestBuilder.metadata(userMetadata);
            }

            RequestBody requestBody = RequestBody.fromRemainingByteBuffer(ByteBuffer.wrap(buffer, 0, length));
            PutObjectResponse response = this.s3Client.putObject(putRequestBuilder.build(), requestBody);
            String etag = stripQuotes(response.eTag());

            return StorageObject.builder()
                    .path(cleanPath)
                    .filename(FileUtil.cleanFilename(cleanPath))
                    .size((long) length)
                    .contentType(finalContentType)
                    .etag(etag)
                    .userMetadata(userMetadata != null && !userMetadata.isEmpty() ? Map.copyOf(userMetadata) : Collections.emptyMap())
                    .lastModified(Instant.now())
                    .build();
        } catch (Exception e) {
            throw translateException(e, cleanPath, "上传对象到 S3 失败");
        }
    }

    @Override
    public String getUrl(String path) {
        String cleanPath = StorageSecurityUtil.normalizePath(path);
        if (cleanPath.startsWith("/")) {
            cleanPath = cleanPath.substring(1);
        }
        if (domain != null && !domain.isBlank()) {
            String cleanDomain = domain.endsWith("/") ? domain.substring(0, domain.length() - 1) : domain;
            return cleanDomain + "/" + cleanPath;
        }
        if (endpoint != null && !endpoint.isBlank()) {
            String cleanEndpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
            if (pathStyle) {
                return cleanEndpoint + "/" + bucket + "/" + cleanPath;
            } else {
                try {
                    URI uri = URI.create(cleanEndpoint);
                    String scheme = uri.getScheme() != null ? uri.getScheme() : "https";
                    String host = uri.getHost() != null ? uri.getHost() : cleanEndpoint;
                    int port = uri.getPort();
                    String portPart = port > 0 ? ":" + port : "";
                    return scheme + "://" + bucket + "." + host + portPart + "/" + cleanPath;
                } catch (Exception e) {
                    return cleanEndpoint + "/" + bucket + "/" + cleanPath;
                }
            }
        }
        return "https://" + bucket + ".s3.amazonaws.com/" + cleanPath;
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
    public InputStream download(String path, long startOffset, long length) {
        if (startOffset < 0) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "下载起始偏移量不能为负数: " + startOffset);
        }
        if (length <= 0) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "下载读取长度必须大于 0: " + length);
        }
        String cleanPath = StorageSecurityUtil.normalizePath(path);
        long endOffset = startOffset + length - 1;
        String rangeHeader = "bytes=" + startOffset + "-" + endOffset;
        try {
            GetObjectRequest getRequest = GetObjectRequest.builder()
                    .bucket(this.bucket)
                    .key(cleanPath)
                    .range(rangeHeader)
                    .build();
            return this.s3Client.getObject(getRequest);
        } catch (Exception e) {
            throw translateException(e, cleanPath, "从 S3 分段读取字节范围失败: " + rangeHeader);
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
            Map<String, String> userMeta = (response.metadata() != null && !response.metadata().isEmpty())
                    ? Map.copyOf(response.metadata())
                    : Collections.emptyMap();

            return StorageObject.builder()
                    .path(cleanPath)
                    .filename(FileUtil.cleanFilename(cleanPath))
                    .size(response.contentLength())
                    .contentType(contentType)
                    .etag(etag)
                    .userMetadata(userMeta)
                    .lastModified(response.lastModified())
                    .build();
        } catch (Exception e) {
            throw translateException(e, cleanPath, "获取 S3 对象元数据失败");
        }
    }

    @Override
    public void ping() throws Exception {
        HeadBucketRequest headRequest = HeadBucketRequest.builder()
                .bucket(this.bucket)
                .build();
        this.s3Client.headBucket(headRequest);
    }

    @Override
    public Map<String, Object> getDiagnostics() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("type", "s3");
        map.put("bucket", this.bucket);
        return map;
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
    public boolean deletePrefix(String prefix) {
        String cleanPrefix = StorageSecurityUtil.validatePrefixForDeletion(prefix);
        try {
            List<ObjectIdentifier> batch = new ArrayList<>(1000);
            ListObjectsV2Request listReq = ListObjectsV2Request.builder()
                    .bucket(this.bucket)
                    .prefix(cleanPrefix)
                    .build();

            software.amazon.awssdk.services.s3.paginators.ListObjectsV2Iterable paginator = null;
            try {
                paginator = this.s3Client.listObjectsV2Paginator(listReq);
            } catch (Exception e) {
                log.debug("调用 listObjectsV2Paginator 异常，回退至普通分页: {}", e.getMessage());
            }

            if (paginator != null) {
                for (ListObjectsV2Response page : paginator) {
                    processDeletePage(page, batch);
                }
            } else {
                String continuationToken = null;
                do {
                    ListObjectsV2Request req = listReq.toBuilder().continuationToken(continuationToken).build();
                    ListObjectsV2Response listResp = this.s3Client.listObjectsV2(req);
                    processDeletePage(listResp, batch);
                    continuationToken = listResp.isTruncated() ? listResp.nextContinuationToken() : null;
                } while (continuationToken != null);
            }

            if (!batch.isEmpty()) {
                flushDeleteBatch(batch);
                batch.clear();
            }
            return true;
        } catch (StorageException e) {
            throw e;
        } catch (Exception e) {
            throw translateException(e, prefix, "按前缀批量删除 S3 对象失败");
        }
    }

    private void processDeletePage(ListObjectsV2Response page, List<ObjectIdentifier> batch) {
        if (page != null && page.hasContents()) {
            for (S3Object obj : page.contents()) {
                batch.add(ObjectIdentifier.builder().key(obj.key()).build());
                if (batch.size() >= 1000) {
                    flushDeleteBatch(batch);
                    batch.clear();
                }
            }
        }
    }

    @Override
    public boolean deleteDirectory(String dirPath) {
        String cleanDir = StorageSecurityUtil.validatePrefixForDeletion(dirPath);
        String dirPrefix = cleanDir.endsWith("/") ? cleanDir : cleanDir + "/";
        // 1. 批量删除该目录及其下全部对象
        deletePrefix(dirPrefix);
        // 2. 天然幂等删除可能的目录占位符自身
        delete(cleanDir);
        return true;
    }

    private void flushDeleteBatch(List<ObjectIdentifier> identifiers) {
        if (identifiers == null || identifiers.isEmpty()) {
            return;
        }
        DeleteObjectsRequest deleteRequest = DeleteObjectsRequest.builder()
                .bucket(this.bucket)
                .delete(Delete.builder().objects(identifiers).quiet(true).build())
                .build();
        this.s3Client.deleteObjects(deleteRequest);
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
        try {
            CopyObjectRequest copyRequest = CopyObjectRequest.builder()
                    .sourceBucket(this.bucket)
                    .sourceKey(cleanSource)
                    .destinationBucket(this.bucket)
                    .destinationKey(cleanTarget)
                    .build();
            this.s3Client.copyObject(copyRequest);
            return true;
        } catch (NoSuchKeyException e) {
            throw new StorageException(StorageResultCode.OBJECT_NOT_FOUND, "源存储对象不存在: " + sourcePath, e);
        } catch (S3Exception e) {
            if (e.statusCode() == 404 || isNotFoundCode(e)) {
                throw new StorageException(StorageResultCode.OBJECT_NOT_FOUND, "源存储对象不存在: " + sourcePath, e);
            }
            throw translateException(e, cleanSource + " -> " + cleanTarget, "复制 S3 对象失败");
        } catch (Exception e) {
            throw translateException(e, cleanSource + " -> " + cleanTarget, "复制 S3 对象失败");
        }
    }

    @Override
    public boolean move(String sourcePath, String targetPath) {
        copy(sourcePath, targetPath);
        delete(sourcePath);
        return true;
    }

    @Override
    public List<StorageObject> list(String prefix, boolean recursive) {
        String cleanPrefix = (prefix != null && !prefix.isBlank())
                ? StorageSecurityUtil.normalizePath(prefix)
                : "";
        String s3Prefix;
        if (cleanPrefix.isEmpty()) {
            s3Prefix = "";
        } else {
            s3Prefix = cleanPrefix.endsWith("/") ? cleanPrefix : cleanPrefix + "/";
        }

        try {
            List<StorageObject> results = new ArrayList<>();
            String continuationToken = null;
            do {
                ListObjectsV2Request.Builder requestBuilder = ListObjectsV2Request.builder()
                        .bucket(this.bucket)
                        .continuationToken(continuationToken);

                if (!s3Prefix.isEmpty()) {
                    requestBuilder.prefix(s3Prefix);
                }
                if (!recursive) {
                    requestBuilder.delimiter("/");
                }

                ListObjectsV2Response response = this.s3Client.listObjectsV2(requestBuilder.build());

                // 1. 处理虚拟子目录（CommonPrefixes）
                if (!recursive && response.hasCommonPrefixes()) {
                    for (CommonPrefix cp : response.commonPrefixes()) {
                        String dirKey = cp.prefix();
                        if (dirKey.endsWith("/")) {
                            dirKey = dirKey.substring(0, dirKey.length() - 1);
                        }
                        String dirName = dirKey.contains("/") ? dirKey.substring(dirKey.lastIndexOf('/') + 1) : dirKey;
                        if (!isHiddenOrSystemKey(dirKey, s3Prefix)) {
                            results.add(StorageObject.builder()
                                    .path(dirKey)
                                    .filename(dirName)
                                    .directory(true)
                                    .size(0L)
                                    .lastModified(Instant.EPOCH)
                                    .build());
                        }
                    }
                }

                // 2. 处理对象（Contents）
                if (response.hasContents()) {
                    for (S3Object s3Obj : response.contents()) {
                        String key = s3Obj.key();
                        // 忽略以 / 结尾的目录占位符自身
                        if (key.endsWith("/") || key.equals(s3Prefix)) {
                            continue;
                        }
                        if (isHiddenOrSystemKey(key, s3Prefix)) {
                            continue;
                        }
                        String filename = FileUtil.cleanFilename(key);
                        results.add(StorageObject.builder()
                                .path(key)
                                .filename(filename)
                                .size(s3Obj.size())
                                .contentType(StorageSecurityUtil.determineContentType(key))
                                .etag(stripQuotes(s3Obj.eTag()))
                                .lastModified(s3Obj.lastModified())
                                .directory(false)
                                .build());
                    }
                }

                continuationToken = response.isTruncated() ? response.nextContinuationToken() : null;
            } while (continuationToken != null);

            // 若作为目录列举为空，且 cleanPrefix 非空，探测是否本身为单对象文件
            if (results.isEmpty() && !cleanPrefix.isEmpty() && exists(cleanPrefix)) {
                return List.of(getMetadata(cleanPrefix));
            }

            results.sort(Comparator.comparing(StorageObject::getPath));
            return results;
        } catch (Exception e) {
            throw translateException(e, prefix, "列举 S3 对象列表失败");
        }
    }

    @Override
    public PresignedUrl getPresignedUrl(String path, Duration duration) {
        return getPresignedUrl(PresignedDownloadRequest.builder()
                .path(path)
                .duration(duration)
                .build());
    }

    @Override
    public PresignedUrl getPresignedUrl(PresignedDownloadRequest request) {
        if (this.s3Presigner == null) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "未配置 S3Presigner，无法生成预签名下载外链");
        }
        if (request == null || request.getPath() == null || request.getPath().isBlank()) {
            throw new StorageException(StorageResultCode.INVALID_PATH, "预签名下载路径不能为空");
        }
        String cleanPath = StorageSecurityUtil.normalizePath(request.getPath());
        Duration duration = request.getDuration();
        Duration effectiveDuration = (duration != null && !duration.isNegative() && !duration.isZero())
                ? duration
                : Duration.ofMinutes(15);
        try {
            GetObjectRequest.Builder getRequestBuilder = GetObjectRequest.builder()
                    .bucket(this.bucket)
                    .key(cleanPath);

            if (request.getResponseContentType() != null && !request.getResponseContentType().isBlank()) {
                getRequestBuilder.responseContentType(request.getResponseContentType().trim());
            }

            String disposition = request.getResponseContentDisposition();
            if (disposition != null && !disposition.isBlank()) {
                getRequestBuilder.responseContentDisposition(disposition.trim());
            } else if (request.getDownloadFilename() != null && !request.getDownloadFilename().isBlank()) {
                getRequestBuilder.responseContentDisposition(StorageSecurityUtil.formatContentDisposition(request.getDownloadFilename()));
            }

            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(effectiveDuration)
                    .getObjectRequest(getRequestBuilder.build())
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
        return getPresignedUploadUrl(PresignedUploadRequest.builder()
                .path(path)
                .duration(duration)
                .build());
    }

    @Override
    public PresignedUrl getPresignedUploadUrl(PresignedUploadRequest request) {
        if (this.s3Presigner == null) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "未配置 S3Presigner，无法生成预签名上传外链");
        }
        if (request == null) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "预签名直传请求参数不能为空");
        }
        String cleanPath = StorageSecurityUtil.normalizePath(request.getPath());
        Duration effectiveDuration = (request.getDuration() != null && !request.getDuration().isNegative() && !request.getDuration().isZero())
                ? request.getDuration()
                : Duration.ofMinutes(15);
        try {
            PutObjectRequest.Builder putRequestBuilder = PutObjectRequest.builder()
                    .bucket(this.bucket)
                    .key(cleanPath);

            java.util.Map<String, String> signedHeaders = new java.util.LinkedHashMap<>();
            if (request.getContentType() != null && !request.getContentType().isBlank()) {
                String ct = request.getContentType().trim();
                putRequestBuilder.contentType(ct);
                signedHeaders.put("Content-Type", ct);
            }
            if (request.getHeaders() != null && !request.getHeaders().isEmpty()) {
                request.getHeaders().forEach((k, v) -> {
                    if (k != null && v != null) {
                        putRequestBuilder.overrideConfiguration(c -> c.putHeader(k, v));
                        signedHeaders.put(k, v);
                    }
                });
            }

            PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                    .signatureDuration(effectiveDuration)
                    .putObjectRequest(putRequestBuilder.build())
                    .build();
            PresignedPutObjectRequest presigned = this.s3Presigner.presignPutObject(presignRequest);
            String finalUrl = applyDomain(presigned.url().toString(), this.domain);

            return PresignedUrl.builder()
                    .url(finalUrl)
                    .expiration(presigned.expiration())
                    .httpMethod("PUT")
                    .headers(signedHeaders)
                    .build();
        } catch (Exception e) {
            throw translateException(e, cleanPath, "生成 S3 预签名直传外链失败");
        }
    }

    @Override
    public PresignedUrl getPresignedUploadPartUrl(String path, String uploadId, int chunkIndex, Duration duration) {
        if (this.s3Presigner == null) {
            throw new StorageException(StorageResultCode.OPERATION_FAILED, "未配置 S3Presigner，无法生成预签名分片直传外链");
        }
        if (uploadId == null || uploadId.isBlank()) {
            throw new StorageException(StorageResultCode.INVALID_PATH, "分片上传会话标识 uploadId 不能为空");
        }
        if (chunkIndex < 1) {
            throw new StorageException(StorageResultCode.STORAGE_CHUNK_INVALID, "分片索引必须 >= 1 (1-based)，当前为: " + chunkIndex);
        }
        S3ChunkTokenUtil.S3ChunkToken token = S3ChunkTokenUtil.decode(uploadId, this.secretKey);
        String key = token.path();
        String rawUploadId = token.rawUploadId();

        if (path != null && !path.isBlank()) {
            String cleanPath = StorageSecurityUtil.normalizePath(path);
            if (!cleanPath.equals(key)) {
                throw new StorageException(StorageResultCode.INVALID_PATH,
                        "分片直传路径与上传会话绑定的路径不一致: expected=" + key + ", actual=" + cleanPath);
            }
        }

        Duration effectiveDuration = (duration != null && !duration.isNegative() && !duration.isZero())
                ? duration
                : Duration.ofMinutes(15);
        try {
            UploadPartRequest uploadPartRequest = UploadPartRequest.builder()
                    .bucket(this.bucket)
                    .key(key)
                    .uploadId(rawUploadId)
                    .partNumber(chunkIndex)
                    .build();

            UploadPartPresignRequest presignRequest = UploadPartPresignRequest.builder()
                    .signatureDuration(effectiveDuration)
                    .uploadPartRequest(uploadPartRequest)
                    .build();

            PresignedUploadPartRequest presigned = this.s3Presigner.presignUploadPart(presignRequest);
            String finalUrl = applyDomain(presigned.url().toString(), this.domain);

            return PresignedUrl.builder()
                    .url(finalUrl)
                    .expiration(presigned.expiration())
                    .httpMethod("PUT")
                    .build();
        } catch (Exception e) {
            throw translateException(e, key, "生成 S3 预签名分片直传外链失败 (chunk=" + chunkIndex + ")");
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
            } catch (Exception e) {
                log.debug("关闭 S3Client 客户端发生异常: {}", e.getMessage());
            }
        }
        if (this.s3Presigner != null) {
            try {
                this.s3Presigner.close();
            } catch (Exception e) {
                log.debug("关闭 S3Presigner 预签名器发生异常: {}", e.getMessage());
            }
        }
    }

    @Override
    public String initiateChunkedUpload(String path) {
        if (path == null || path.isBlank()) {
            throw new StorageException(StorageResultCode.INVALID_PATH, "分片上传目标路径不能为空");
        }
        String cleanPath = StorageSecurityUtil.normalizePath(path);
        String contentType = StorageSecurityUtil.determineContentType(cleanPath);
        try {
            CreateMultipartUploadRequest req = CreateMultipartUploadRequest.builder()
                    .bucket(this.bucket)
                    .key(cleanPath)
                    .contentType(contentType)
                    .build();
            CreateMultipartUploadResponse resp = this.s3Client.createMultipartUpload(req);
            String rawUploadId = resp.uploadId();
            return S3ChunkTokenUtil.encode(cleanPath, rawUploadId, this.secretKey);
        } catch (Exception e) {
            throw translateException(e, cleanPath, "初始化 S3 分片上传会话失败");
        }
    }

    @Override
    public void uploadChunk(String uploadId, int chunkIndex, InputStream in, long size) {
        if (in == null) {
            throw new StorageException(StorageResultCode.UPLOAD_FAILED, "分片数据输入流不能为空");
        }
        if (uploadId == null || uploadId.isBlank()) {
            throw new StorageException(StorageResultCode.INVALID_PATH, "分片上传会话标识 uploadId 不能为空");
        }
        if (chunkIndex < 1) {
            throw new StorageException(StorageResultCode.STORAGE_CHUNK_INVALID, "分片索引必须 >= 1 (1-based)，当前为: " + chunkIndex);
        }
        S3ChunkTokenUtil.S3ChunkToken token = S3ChunkTokenUtil.decode(uploadId, this.secretKey);
        String key = token.path();
        String rawUploadId = token.rawUploadId();
        try {
            RequestBody body;
            if (size >= 0) {
                body = RequestBody.fromInputStream(in, size);
            } else {
                byte[] bytes = in.readAllBytes();
                body = RequestBody.fromBytes(bytes);
            }
            UploadPartRequest partReq = UploadPartRequest.builder()
                    .bucket(this.bucket)
                    .key(key)
                    .uploadId(rawUploadId)
                    .partNumber(chunkIndex)
                    .build();
            this.s3Client.uploadPart(partReq, body);
        } catch (IOException e) {
            throw new StorageException(StorageResultCode.UPLOAD_FAILED, "读取分片数据输入流异常: " + uploadId, e);
        } catch (Exception e) {
            throw translateException(e, key, "上传 S3 分片失败 (chunk=" + chunkIndex + ")");
        }
    }

    @Override
    public StorageObject completeChunkedUpload(String uploadId, String targetPath, int totalChunks) {
        if (uploadId == null || uploadId.isBlank()) {
            throw new StorageException(StorageResultCode.INVALID_PATH, "分片上传会话标识 uploadId 不能为空");
        }
        if (totalChunks < 1) {
            throw new StorageException(StorageResultCode.STORAGE_CHUNK_INVALID, "分片总片数必须 >= 1，当前为: " + totalChunks);
        }
        S3ChunkTokenUtil.S3ChunkToken token = S3ChunkTokenUtil.decode(uploadId, this.secretKey);
        String key = token.path();
        String rawUploadId = token.rawUploadId();

        if (targetPath != null && !targetPath.isBlank()) {
            String cleanTargetPath = StorageSecurityUtil.normalizePath(targetPath);
            if (!cleanTargetPath.equals(key)) {
                throw new StorageException(StorageResultCode.STORAGE_CHUNK_INVALID,
                        "分片上传目标路径不匹配: tokenPath=" + key + ", targetPath=" + cleanTargetPath);
            }
        }

        List<CompletedPart> completedParts = new ArrayList<>(totalChunks);
        try {
            Map<Integer, String> s3Parts = new HashMap<>();
            Integer partNumberMarker = null;
            do {
                ListPartsRequest.Builder listPartsBuilder = ListPartsRequest.builder()
                        .bucket(this.bucket)
                        .key(key)
                        .uploadId(rawUploadId);
                if (partNumberMarker != null) {
                    listPartsBuilder.partNumberMarker(partNumberMarker);
                }
                ListPartsResponse listPartsResp = this.s3Client.listParts(listPartsBuilder.build());
                if (listPartsResp.hasParts()) {
                    for (Part p : listPartsResp.parts()) {
                        s3Parts.put(p.partNumber(), stripQuotes(p.eTag()));
                    }
                }
                partNumberMarker = listPartsResp.isTruncated() ? listPartsResp.nextPartNumberMarker() : null;
            } while (partNumberMarker != null);

            for (int i = 1; i <= totalChunks; i++) {
                String etag = s3Parts.get(i);
                if (etag == null || etag.isBlank()) {
                    throw new StorageException(StorageResultCode.STORAGE_CHUNK_MISSING,
                            "S3 分片合并失败，缺失第 " + i + " 个分片（预期总片数: " + totalChunks + "）");
                }
                completedParts.add(CompletedPart.builder().partNumber(i).eTag(etag).build());
            }
        } catch (StorageException e) {
            throw e;
        } catch (Exception e) {
            throw translateException(e, key, "查询 S3 分片列表失败");
        }

        try {
            CompletedMultipartUpload multipartUpload = CompletedMultipartUpload.builder()
                    .parts(completedParts)
                    .build();
            CompleteMultipartUploadRequest completeReq = CompleteMultipartUploadRequest.builder()
                    .bucket(this.bucket)
                    .key(key)
                    .uploadId(rawUploadId)
                    .multipartUpload(multipartUpload)
                    .build();
            this.s3Client.completeMultipartUpload(completeReq);
            return getMetadata(key);
        } catch (Exception e) {
            throw translateException(e, key, "完成 S3 分片合并失败");
        }
    }

    @Override
    public void abortChunkedUpload(String uploadId) {
        if (uploadId == null || uploadId.isBlank()) {
            return;
        }
        String key = null;
        String rawUploadId = uploadId;
        try {
            S3ChunkTokenUtil.S3ChunkToken token = S3ChunkTokenUtil.decode(uploadId, this.secretKey);
            key = token.path();
            rawUploadId = token.rawUploadId();
        } catch (Exception e) {
            log.debug("分片上传 uploadId 无法作为 S3 签名 Token 解码，尝试作为原生 S3 uploadId 处理: uploadId={}, cause={}", uploadId, e.getMessage());
        }

        if (key == null) {
            try {
                ListMultipartUploadsResponse uploadsResp = this.s3Client.listMultipartUploads(
                        ListMultipartUploadsRequest.builder().bucket(this.bucket).build());
                if (uploadsResp.hasUploads()) {
                    for (MultipartUpload u : uploadsResp.uploads()) {
                        if (uploadId.equals(u.uploadId())) {
                            key = u.key();
                            break;
                        }
                    }
                }
            } catch (Exception e) {
                log.warn("反查 S3 原生分片上传会话所属 Key 失败: uploadId={}, cause={}", uploadId, e.getMessage());
            }
        }

        if (key != null) {
            try {
                this.s3Client.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                        .bucket(this.bucket)
                        .key(key)
                        .uploadId(rawUploadId)
                        .build());
            } catch (Exception e) {
                throw translateException(e, key, "中止 S3 分片上传会话失败");
            }
        } else {
            log.warn("无法中止 S3 分片上传会话，未定位到对应的存储对象: uploadId={}", uploadId);
        }
    }

    private boolean isHiddenOrSystemKey(String key, String prefix) {
        if (key == null || key.isBlank()) {
            return false;
        }
        String relKey = (prefix != null && !prefix.isBlank() && key.startsWith(prefix))
                ? key.substring(prefix.length())
                : key;
        while (relKey.startsWith("/")) {
            relKey = relKey.substring(1);
        }
        String[] segments = relKey.split("/");
        for (String seg : segments) {
            if (seg.startsWith(".") || "desktop.ini".equalsIgnoreCase(seg) || "thumbs.db".equalsIgnoreCase(seg)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int cleanExpiredChunkSessions(Duration maxAge) {
        Duration effectiveMaxAge = (maxAge != null && !maxAge.isNegative() && !maxAge.isZero())
                ? maxAge
                : Duration.ofHours(24);
        Instant threshold = Instant.now().minus(effectiveMaxAge);
        int cleanedCount = 0;
        try {
            String keyMarker = null;
            String uploadIdMarker = null;
            boolean isTruncated;
            do {
                ListMultipartUploadsRequest.Builder builder = ListMultipartUploadsRequest.builder()
                        .bucket(this.bucket);
                if (keyMarker != null) {
                    builder.keyMarker(keyMarker);
                }
                if (uploadIdMarker != null) {
                    builder.uploadIdMarker(uploadIdMarker);
                }
                ListMultipartUploadsResponse resp = this.s3Client.listMultipartUploads(builder.build());
                if (resp.hasUploads()) {
                    for (MultipartUpload u : resp.uploads()) {
                        if (u.initiated() != null && u.initiated().isBefore(threshold)) {
                            try {
                                this.s3Client.abortMultipartUpload(AbortMultipartUploadRequest.builder()
                                        .bucket(this.bucket)
                                        .key(u.key())
                                        .uploadId(u.uploadId())
                                        .build());
                                cleanedCount++;
                            } catch (Exception e) {
                                log.warn("清理超时 S3 分片上传会话失败: key={}, uploadId={}, cause={}", u.key(), u.uploadId(), e.getMessage());
                            }
                        }
                    }
                }
                isTruncated = Boolean.TRUE.equals(resp.isTruncated());
                if (isTruncated) {
                    keyMarker = resp.nextKeyMarker();
                    uploadIdMarker = resp.nextUploadIdMarker();
                }
            } while (isTruncated);
        } catch (Exception e) {
            log.error("扫描与清理超时 S3 分片上传会话发生异常: cause={}", e.getMessage(), e);
        }
        return cleanedCount;
    }
}
