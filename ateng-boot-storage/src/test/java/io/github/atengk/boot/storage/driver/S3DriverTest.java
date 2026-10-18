package io.github.atengk.boot.storage.driver;

import io.github.atengk.boot.storage.exception.StorageException;
import io.github.atengk.boot.storage.exception.StorageResultCode;
import io.github.atengk.boot.storage.model.PresignedUrl;
import io.github.atengk.boot.storage.model.StorageObject;
import io.github.atengk.boot.storage.properties.S3StorageProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.CopyObjectResponse;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * S3Driver 单元测试
 * 基于 Mockito 对 S3Client 与 S3Presigner 进行方法级打桩，纯内存极速验证
 *
 * @author Ateng
 * @since 2026-10-17
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("S3Driver 对象存储驱动测试")
class S3DriverTest {

    @Mock
    private S3Client s3Client;

    @Mock
    private S3Presigner s3Presigner;

    @Mock
    private PresignedGetObjectRequest presignedGetObjectRequest;

    @Mock
    private PresignedPutObjectRequest presignedPutObjectRequest;

    private S3Driver s3Driver;
    private static final String TEST_BUCKET = "test-bucket";
    private static final String TEST_DOMAIN = "https://cdn.example.com";

    @BeforeEach
    void setUp() {
        s3Driver = new S3Driver(s3Client, s3Presigner, TEST_BUCKET, TEST_DOMAIN);
    }

    @Test
    @DisplayName("上传对象并校验 S3 参数及 ETag 引号剥离")
    void testUpload() {
        byte[] content = "Hello S3 Storage".getBytes(StandardCharsets.UTF_8);
        String path = "photos/2026/avatar.png";

        PutObjectResponse putResponse = PutObjectResponse.builder()
                .eTag("\"c8e8d2e8e2b8f8a1\"")
                .build();
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenReturn(putResponse);

        StorageObject result = s3Driver.upload(path, new ByteArrayInputStream(content), "image/png", content.length);

        assertNotNull(result);
        assertEquals("photos/2026/avatar.png", result.getPath());
        assertEquals("avatar.png", result.getFilename());
        assertEquals(content.length, result.getSize());
        assertEquals("image/png", result.getContentType());
        assertEquals("c8e8d2e8e2b8f8a1", result.getEtag());
        assertNotNull(result.getLastModified());

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(captor.capture(), any(RequestBody.class));
        PutObjectRequest capturedRequest = captor.getValue();
        assertEquals(TEST_BUCKET, capturedRequest.bucket());
        assertEquals("photos/2026/avatar.png", capturedRequest.key());
        assertEquals("image/png", capturedRequest.contentType());
    }

    @Test
    @DisplayName("流式下载 S3 存储对象成功")
    void testDownload() throws Exception {
        byte[] content = "Downloaded content from S3".getBytes(StandardCharsets.UTF_8);
        String path = "files/doc.txt";

        GetObjectResponse getResponse = GetObjectResponse.builder()
                .contentLength((long) content.length)
                .contentType("text/plain")
                .build();
        ResponseInputStream<GetObjectResponse> responseStream = new ResponseInputStream<>(
                getResponse,
                AbortableInputStream.create(new ByteArrayInputStream(content))
        );
        when(s3Client.getObject(any(GetObjectRequest.class))).thenReturn(responseStream);

        try (InputStream in = s3Driver.download(path)) {
            byte[] downloaded = in.readAllBytes();
            assertArrayEquals(content, downloaded);
        }

        ArgumentCaptor<GetObjectRequest> captor = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(s3Client).getObject(captor.capture());
        assertEquals(TEST_BUCKET, captor.getValue().bucket());
        assertEquals("files/doc.txt", captor.getValue().key());
    }

    @Test
    @DisplayName("获取 S3 存储对象元数据成功")
    void testGetMetadata() {
        String path = "files/report.pdf";
        Instant now = Instant.now();

        HeadObjectResponse headResponse = HeadObjectResponse.builder()
                .contentLength(1024L)
                .contentType("application/pdf")
                .eTag("\"etag12345\"")
                .lastModified(now)
                .build();
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenReturn(headResponse);

        StorageObject meta = s3Driver.getMetadata(path);

        assertNotNull(meta);
        assertEquals("files/report.pdf", meta.getPath());
        assertEquals("report.pdf", meta.getFilename());
        assertEquals(1024L, meta.getSize());
        assertEquals("application/pdf", meta.getContentType());
        assertEquals("etag12345", meta.getEtag());
        assertEquals(now, meta.getLastModified());
    }

    @Test
    @DisplayName("检查 S3 对象是否存在：存在返回 true，NoSuchKey 或 404 返回 false")
    void testExists() {
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().build())
                .thenThrow(NoSuchKeyException.builder().message("Key not found").build())
                .thenThrow(S3Exception.builder().statusCode(404).message("Not found").build());

        // 第一次存在
        assertTrue(s3Driver.exists("exist.txt"));
        // 第二次 NoSuchKeyException
        assertFalse(s3Driver.exists("not-exist.txt"));
        // 第三次 404 S3Exception
        assertFalse(s3Driver.exists("not-exist-404.txt"));
    }

    @Test
    @DisplayName("S3 删除操作天然幂等：正常删除与 404 均返回 true")
    void testDelete() {
        // 第一次正常删除
        assertTrue(s3Driver.delete("delete-1.txt"));

        // 第二次模拟 S3 返回 404
        when(s3Client.deleteObject(any(DeleteObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(404).message("Not found").build());
        assertTrue(s3Driver.delete("delete-404.txt"));

        // 验证空路径返回 true
        assertTrue(s3Driver.delete(""));
        assertTrue(s3Driver.delete(null));
    }

    @Test
    @DisplayName("批量删除 S3 对象天然幂等")
    void testDeleteBatch() {
        assertTrue(s3Driver.deleteBatch(List.of("batch/1.txt", "batch/2.txt")));
        verify(s3Client).deleteObjects(any(DeleteObjectsRequest.class));

        // 空列表直接返回 true
        assertTrue(s3Driver.deleteBatch(List.of()));
        assertTrue(s3Driver.deleteBatch(null));
    }

    @Test
    @DisplayName("生成 S3 预签名下载外链并应用 CDN 自定义域名")
    void testGetPresignedUrl() throws Exception {
        String path = "media/video.mp4";
        URL rawUrl = new URL("https://s3.us-east-1.amazonaws.com/test-bucket/media/video.mp4?X-Amz-Signature=sig123");
        Instant expiration = Instant.now().plus(Duration.ofMinutes(15));

        when(presignedGetObjectRequest.url()).thenReturn(rawUrl);
        when(presignedGetObjectRequest.expiration()).thenReturn(expiration);
        when(s3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(presignedGetObjectRequest);

        PresignedUrl presigned = s3Driver.getPresignedUrl(path, Duration.ofMinutes(15));

        assertNotNull(presigned);
        assertEquals("GET", presigned.getHttpMethod());
        assertEquals(expiration, presigned.getExpiration());
        assertEquals("https://cdn.example.com/test-bucket/media/video.mp4?X-Amz-Signature=sig123", presigned.getUrl());
    }

    @Test
    @DisplayName("生成 S3 预签名直传外链并指定 PUT 请求方法")
    void testGetPresignedUploadUrl() throws Exception {
        String path = "uploads/raw-data.bin";
        URL rawUrl = new URL("https://s3.us-east-1.amazonaws.com/test-bucket/uploads/raw-data.bin?X-Amz-Signature=sig456");
        Instant expiration = Instant.now().plus(Duration.ofMinutes(30));

        when(presignedPutObjectRequest.url()).thenReturn(rawUrl);
        when(presignedPutObjectRequest.expiration()).thenReturn(expiration);
        when(s3Presigner.presignPutObject(any(PutObjectPresignRequest.class))).thenReturn(presignedPutObjectRequest);

        PresignedUrl presigned = s3Driver.getPresignedUploadUrl(path, Duration.ofMinutes(30));

        assertNotNull(presigned);
        assertEquals("PUT", presigned.getHttpMethod());
        assertEquals(expiration, presigned.getExpiration());
        assertEquals("https://cdn.example.com/test-bucket/uploads/raw-data.bin?X-Amz-Signature=sig456", presigned.getUrl());
    }

    @Test
    @DisplayName("S3 异常精准转译测试：404/NoSuchKey 转为 OBJECT_NOT_FOUND，403 与其他错误转为 OPERATION_FAILED")
    void testExceptionTranslation() {
        // NoSuchKeyException
        when(s3Client.getObject(any(GetObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("The specified key does not exist.").build());
        StorageException ex1 = assertThrows(StorageException.class, () -> s3Driver.download("missing.txt"));
        assertEquals(StorageResultCode.OBJECT_NOT_FOUND.getCode(), ex1.getCode());

        // 403 AccessDenied
        AwsErrorDetails accessDeniedDetails = AwsErrorDetails.builder()
                .errorCode("AccessDenied")
                .errorMessage("Access Denied")
                .build();
        S3Exception accessDeniedEx = (S3Exception) S3Exception.builder()
                .statusCode(403)
                .awsErrorDetails(accessDeniedDetails)
                .message("Access Denied")
                .build();
        when(s3Client.headObject(any(HeadObjectRequest.class))).thenThrow(accessDeniedEx);

        StorageException ex2 = assertThrows(StorageException.class, () -> s3Driver.getMetadata("forbidden.txt"));
        assertEquals(StorageResultCode.OPERATION_FAILED.getCode(), ex2.getCode());
        assertTrue(ex2.getMessage().contains("403"));
    }

    @Test
    @DisplayName("S3Driver 构建工具方法自验")
    void testFactoryMethods() {
        S3StorageProperties properties = new S3StorageProperties();
        properties.setEndpoint("http://localhost:9000");
        properties.setBucket("demo-bucket");
        properties.setAccessKey("minioadmin");
        properties.setSecretKey("minioadmin");
        properties.setRegion("us-east-1");
        properties.setPathStyle(true);

        S3Client client = S3Driver.createS3Client(properties);
        assertNotNull(client);

        S3Presigner presigner = S3Driver.createS3Presigner(properties);
        assertNotNull(presigner);
    }

    @Test
    @DisplayName("S3 复制对象成功")
    void testCopy() {
        when(s3Client.copyObject(any(CopyObjectRequest.class))).thenReturn(CopyObjectResponse.builder().build());

        boolean result = s3Driver.copy("docs/2026/report.pdf", "archive/2026/report.pdf");
        assertTrue(result);

        ArgumentCaptor<CopyObjectRequest> captor = ArgumentCaptor.forClass(CopyObjectRequest.class);
        verify(s3Client).copyObject(captor.capture());
        CopyObjectRequest req = captor.getValue();
        assertEquals(TEST_BUCKET, req.sourceBucket());
        assertEquals("docs/2026/report.pdf", req.sourceKey());
        assertEquals(TEST_BUCKET, req.destinationBucket());
        assertEquals("archive/2026/report.pdf", req.destinationKey());
    }

    @Test
    @DisplayName("S3 复制不存在的对象时转译为 OBJECT_NOT_FOUND 异常")
    void testCopyNotFound() {
        when(s3Client.copyObject(any(CopyObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().message("NoSuchKey").build());

        StorageException ex = assertThrows(StorageException.class, () ->
                s3Driver.copy("missing.txt", "target.txt"));
        assertEquals(StorageResultCode.OBJECT_NOT_FOUND.getCode(), ex.getCode());
    }

    @Test
    @DisplayName("S3 移动对象成功（复制并删除源对象）")
    void testMove() {
        when(s3Client.copyObject(any(CopyObjectRequest.class))).thenReturn(CopyObjectResponse.builder().build());

        boolean result = s3Driver.move("docs/2026/report.pdf", "archive/2026/report.pdf");
        assertTrue(result);

        verify(s3Client).copyObject(any(CopyObjectRequest.class));
        verify(s3Client).deleteObject(any(DeleteObjectRequest.class));
    }
}
