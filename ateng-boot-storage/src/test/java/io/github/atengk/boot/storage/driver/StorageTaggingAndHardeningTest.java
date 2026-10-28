package io.github.atengk.boot.storage.driver;

import io.github.atengk.boot.storage.exception.StorageException;
import io.github.atengk.boot.storage.exception.StorageResultCode;
import io.github.atengk.boot.storage.model.StorageObject;
import io.github.atengk.boot.storage.template.StorageTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.GetObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.GetObjectTaggingResponse;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.NoSuchBucketException;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.ObjectStorageClass;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectTaggingRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.model.StorageClass;
import software.amazon.awssdk.services.s3.model.Tag;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 对象标签 (Object Tagging)、存储分层、服务端加密与 Bucket 自愈建桶测试
 *
 * @author Ateng
 * @since 2026-10-28
 */
@DisplayName("对象标签、存储分层、服务端加密与 Bucket 自愈建桶测试")
class StorageTaggingAndHardeningTest {

    @TempDir
    Path tempDir;

    private LocalDriver localDriver;
    private S3Client mockS3Client;
    private S3Presigner mockS3Presigner;
    private S3Driver s3Driver;
    private StorageTemplate storageTemplate;

    @BeforeEach
    void setUp() {
        localDriver = new LocalDriver(tempDir.toString(), "http://localhost:8080/storage/local/download",
                "http://localhost:8080/storage/local/upload", "test-secret-key-12345678901234567890", "http://cdn.local.com");

        mockS3Client = mock(S3Client.class);
        mockS3Presigner = mock(S3Presigner.class);
        s3Driver = new S3Driver(mockS3Client, mockS3Presigner, "test-bucket", "https://cdn.example.com", "s3-secret", "http://s3.example.com", true,
                false, "STANDARD_IA", "AES256");
        storageTemplate = new StorageTemplate(localDriver);
    }

    @Test
    @DisplayName("LocalDriver 对象标签生命周期 CRUD 对称验证")
    void testLocalDriverObjectTaggingLifecycle() {
        String path = "docs/sample.pdf";
        byte[] data = "Sample PDF Content".getBytes(StandardCharsets.UTF_8);
        localDriver.upload(path, new ByteArrayInputStream(data), "application/pdf", data.length);

        // 初始无标签
        Map<String, String> initialTags = localDriver.getObjectTags(path);
        assertNotNull(initialTags);
        assertTrue(initialTags.isEmpty());

        // 设置标签
        Map<String, String> newTags = Map.of("environment", "production", "department", "finance");
        localDriver.setObjectTags(path, newTags);

        // 读取标签
        Map<String, String> fetchedTags = localDriver.getObjectTags(path);
        assertEquals(2, fetchedTags.size());
        assertEquals("production", fetchedTags.get("environment"));
        assertEquals("finance", fetchedTags.get("department"));

        // 删除标签
        localDriver.deleteObjectTags(path);
        Map<String, String> tagsAfterDelete = localDriver.getObjectTags(path);
        assertNotNull(tagsAfterDelete);
        assertTrue(tagsAfterDelete.isEmpty());
    }

    @Test
    @DisplayName("LocalDriver 对象标签输入卫语句防卫")
    void testLocalDriverTaggingValidation() {
        // 不存在对象
        assertThrows(StorageException.class, () -> localDriver.getObjectTags("non-existent.txt"));
        assertThrows(StorageException.class, () -> localDriver.setObjectTags("non-existent.txt", Map.of("k", "v")));

        // 空入参
        assertThrows(StorageException.class, () -> localDriver.setObjectTags(null, Map.of("k", "v")));
        assertThrows(StorageException.class, () -> localDriver.setObjectTags("safe.txt", null));
        assertThrows(StorageException.class, () -> localDriver.setObjectTags("safe.txt", Collections.emptyMap()));
    }

    @Test
    @DisplayName("S3Driver 对象标签 API 与 S3Client 交互捕获")
    void testS3DriverObjectTagging() {
        String path = "photos/avatar.png";

        // 1. GetObjectTagging
        GetObjectTaggingResponse getResp = GetObjectTaggingResponse.builder()
                .tagSet(Tag.builder().key("biz").value("user").build(), Tag.builder().key("stage").value("uat").build())
                .build();
        when(mockS3Client.getObjectTagging(any(GetObjectTaggingRequest.class))).thenReturn(getResp);

        Map<String, String> tags = s3Driver.getObjectTags(path);
        assertEquals(2, tags.size());
        assertEquals("user", tags.get("biz"));
        assertEquals("uat", tags.get("stage"));

        // 2. PutObjectTagging
        s3Driver.setObjectTags(path, Map.of("security", "confidential"));
        ArgumentCaptor<PutObjectTaggingRequest> putCaptor = ArgumentCaptor.forClass(PutObjectTaggingRequest.class);
        verify(mockS3Client).putObjectTagging(putCaptor.capture());
        PutObjectTaggingRequest putReq = putCaptor.getValue();
        assertEquals("test-bucket", putReq.bucket());
        assertEquals("photos/avatar.png", putReq.key());
        assertEquals(1, putReq.tagging().tagSet().size());
        assertEquals("security", putReq.tagging().tagSet().get(0).key());
        assertEquals("confidential", putReq.tagging().tagSet().get(0).value());

        // 3. DeleteObjectTagging
        s3Driver.deleteObjectTags(path);
        ArgumentCaptor<DeleteObjectTaggingRequest> delCaptor = ArgumentCaptor.forClass(DeleteObjectTaggingRequest.class);
        verify(mockS3Client).deleteObjectTagging(delCaptor.capture());
        DeleteObjectTaggingRequest delReq = delCaptor.getValue();
        assertEquals("test-bucket", delReq.bucket());
        assertEquals("photos/avatar.png", delReq.key());
    }

    @Test
    @DisplayName("StorageTemplate 门面对象标签方法转发与防卫")
    void testStorageTemplateTaggingFacade() {
        String path = "test-facade.txt";
        byte[] data = "Facade test".getBytes(StandardCharsets.UTF_8);
        storageTemplate.upload(path, new ByteArrayInputStream(data));

        storageTemplate.setObjectTags(path, Map.of("tag1", "val1"));
        Map<String, String> tags = storageTemplate.getObjectTags(path);
        assertEquals("val1", tags.get("tag1"));

        storageTemplate.deleteObjectTags(path);
        assertTrue(storageTemplate.getObjectTags(path).isEmpty());

        // 校验异常拦截
        assertThrows(StorageException.class, () -> storageTemplate.getObjectTags(""));
        assertThrows(StorageException.class, () -> storageTemplate.setObjectTags(path, Collections.emptyMap()));
    }

    @Test
    @DisplayName("S3Driver 获取元数据解析 StorageClass 与 ServerSideEncryption")
    void testS3DriverMetadataStorageClassAndEncryption() {
        String path = "secure/vault.dat";
        HeadObjectResponse headResp = HeadObjectResponse.builder()
                .contentLength(1024L)
                .contentType("application/octet-stream")
                .eTag("\"etag123\"")
                .lastModified(Instant.now())
                .storageClass(StorageClass.STANDARD_IA)
                .serverSideEncryption(ServerSideEncryption.AES256)
                .build();
        when(mockS3Client.headObject(any(HeadObjectRequest.class))).thenReturn(headResp);

        StorageObject obj = s3Driver.getMetadata(path);
        assertNotNull(obj);
        assertEquals("STANDARD_IA", obj.getStorageClass());
        assertEquals("AES256", obj.getServerSideEncryption());
    }

    @Test
    @DisplayName("S3Driver 列举对象时保留 StorageClass 属性")
    void testS3DriverListRetainsStorageClass() {
        S3Object s3Obj = S3Object.builder()
                .key("archive/2026.zip")
                .size(5000L)
                .eTag("\"etag456\"")
                .lastModified(Instant.now())
                .storageClass(ObjectStorageClass.GLACIER)
                .build();
        ListObjectsV2Response listResp = ListObjectsV2Response.builder()
                .contents(List.of(s3Obj))
                .isTruncated(false)
                .build();
        when(mockS3Client.listObjectsV2(any(ListObjectsV2Request.class))).thenReturn(listResp);

        List<StorageObject> list = s3Driver.list("archive", false);
        assertEquals(1, list.size());
        assertEquals("archive/2026.zip", list.get(0).getPath());
        assertEquals("GLACIER", list.get(0).getStorageClass());
    }

    @Test
    @DisplayName("S3Driver 上传时自动挂载默认 StorageClass 与 ServerSideEncryption")
    void testS3DriverUploadWithDefaultStorageClassAndEncryption() {
        String path = "auto-tier.txt";
        byte[] data = "Tier data".getBytes(StandardCharsets.UTF_8);
        when(mockS3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class)))
                .thenReturn(PutObjectResponse.builder().eTag("\"etag789\"").build());

        StorageObject obj = s3Driver.upload(path, new ByteArrayInputStream(data), "text/plain", data.length);
        assertNotNull(obj);

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(mockS3Client).putObject(captor.capture(), any(RequestBody.class));
        PutObjectRequest captured = captor.getValue();
        assertEquals(StorageClass.STANDARD_IA.toString(), captured.storageClassAsString());
        assertEquals(ServerSideEncryption.AES256.toString(), captured.serverSideEncryptionAsString());
    }

    @Test
    @DisplayName("S3Driver 当 autoCreateBucket=true 且桶不存在时自愈建桶")
    void testS3DriverAutoCreateBucketOnNoSuchBucket() throws Exception {
        S3Driver autoCreateDriver = new S3Driver(mockS3Client, mockS3Presigner, "self-heal-bucket", null, null, null, true,
                true, null, null);

        // 模拟第一次探测 HeadBucket 抛出 NoSuchBucketException
        when(mockS3Client.headBucket(any(HeadBucketRequest.class)))
                .thenThrow(NoSuchBucketException.builder().message("The specified bucket does not exist").build());

        autoCreateDriver.ping();

        // 验证执行了 createBucket
        ArgumentCaptor<CreateBucketRequest> captor = ArgumentCaptor.forClass(CreateBucketRequest.class);
        verify(mockS3Client).createBucket(captor.capture());
        assertEquals("self-heal-bucket", captor.getValue().bucket());
    }

    @Test
    @DisplayName("S3Driver 当 autoCreateBucket=false 且桶不存在时抛出健康检查异常")
    void testS3DriverThrowsWhenBucketNotExistsWithoutAutoCreate() {
        when(mockS3Client.headBucket(any(HeadBucketRequest.class)))
                .thenThrow(NoSuchBucketException.builder().message("The specified bucket does not exist").build());

        StorageException ex = assertThrows(StorageException.class, () -> s3Driver.ping());
        assertEquals(StorageResultCode.STORAGE_HEALTH_CHECK_FAILED, ex.getResultCode());
        assertTrue(ex.getMessage().contains("auto-create-bucket"));
    }

    @Test
    @DisplayName("S3Driver 诊断字典包含存储分层与自愈配置")
    void testS3DriverDiagnostics() {
        Map<String, Object> diag = s3Driver.getDiagnostics();
        assertEquals("s3", diag.get("type"));
        assertEquals("test-bucket", diag.get("bucket"));
        assertEquals(false, diag.get("autoCreateBucket"));
        assertEquals("STANDARD_IA", diag.get("defaultStorageClass"));
        assertEquals("AES256", diag.get("defaultServerSideEncryption"));
    }
}
