package io.github.atengk.boot.storage.driver;

import io.github.atengk.boot.storage.exception.StorageException;
import io.github.atengk.boot.storage.factory.StorageDriverFactory;
import io.github.atengk.boot.storage.model.StorageObject;
import io.github.atengk.boot.storage.template.StorageTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 双阶自适应流式分片缓冲防 OOM 单元与对抗性测试
 *
 * @author Ateng
 * @since 2026-10-28
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("S3 双阶自适应流式分片缓冲测试")
class S3AdaptiveStreamingTest {

    @Mock
    private S3Client s3Client;

    private static final int FIVE_MB = 5 * 1024 * 1024;

    @Test
    @DisplayName("测试未知长度小文件 (1KB): 直接走单请求 PutObject，不触发分片初始化")
    void testSmallStreamSinglePutObject() {
        PutObjectResponse putResp = PutObjectResponse.builder().eTag("\"etag-small\"").build();
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenReturn(putResp);

        S3Driver driver = new S3Driver(s3Client, null, "test-bucket", null, null, "http://127.0.0.1:9000", true);

        byte[] smallData = new byte[1024];
        Arrays.fill(smallData, (byte) 'A');
        InputStream in = new ByteArrayInputStream(smallData);

        StorageObject obj = driver.upload("test/small.dat", in, "application/octet-stream", -1);

        assertNotNull(obj);
        assertEquals("test/small.dat", obj.getPath());
        assertEquals(1024, obj.getSize());
        assertEquals("etag-small", obj.getEtag());

        verify(s3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        verify(s3Client, never()).createMultipartUpload(any(CreateMultipartUploadRequest.class));
    }

    @Test
    @DisplayName("测试未知长度临界文件 (恰好 5MB): 直接走单请求 PutObject，不触发分片初始化")
    void testExactly5MbStreamSinglePutObject() {
        PutObjectResponse putResp = PutObjectResponse.builder().eTag("\"etag-5mb\"").build();
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenReturn(putResp);

        S3Driver driver = new S3Driver(s3Client, null, "test-bucket", null, null, "http://127.0.0.1:9000", true);

        byte[] data5Mb = new byte[FIVE_MB];
        Arrays.fill(data5Mb, (byte) 'B');
        InputStream in = new ByteArrayInputStream(data5Mb);

        StorageObject obj = driver.upload("test/5mb.dat", in, "application/octet-stream", -1);

        assertNotNull(obj);
        assertEquals(FIVE_MB, obj.getSize());
        assertEquals("etag-5mb", obj.getEtag());

        verify(s3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        verify(s3Client, never()).createMultipartUpload(any(CreateMultipartUploadRequest.class));
    }

    @Test
    @DisplayName("测试未知长度大文件 (6MB): 自动升级为 Multipart Upload 并分批上传碎片")
    void testLargeStreamMultipartUpload() {
        CreateMultipartUploadResponse createResp = CreateMultipartUploadResponse.builder().uploadId("up-adaptive-999").build();
        when(s3Client.createMultipartUpload(any(CreateMultipartUploadRequest.class))).thenReturn(createResp);

        UploadPartResponse partResp1 = UploadPartResponse.builder().eTag("\"etag-part-1\"").build();
        UploadPartResponse partResp2 = UploadPartResponse.builder().eTag("\"etag-part-2\"").build();
        when(s3Client.uploadPart(any(UploadPartRequest.class), any(RequestBody.class)))
                .thenReturn(partResp1)
                .thenReturn(partResp2);

        CompleteMultipartUploadResponse completeResp = CompleteMultipartUploadResponse.builder().eTag("\"etag-multi-complete\"").build();
        when(s3Client.completeMultipartUpload(any(CompleteMultipartUploadRequest.class))).thenReturn(completeResp);

        S3Driver driver = new S3Driver(s3Client, null, "test-bucket", null, null, "http://127.0.0.1:9000", true);

        int totalSize = FIVE_MB + 1024 * 1024; // 6MB
        byte[] largeData = new byte[totalSize];
        Arrays.fill(largeData, (byte) 'C');
        InputStream in = new ByteArrayInputStream(largeData);

        StorageObject obj = driver.upload("test/large.dat", in, "application/octet-stream", -1);

        assertNotNull(obj);
        assertEquals(totalSize, obj.getSize());
        assertEquals("etag-multi-complete", obj.getEtag());

        verify(s3Client, never()).putObject(any(PutObjectRequest.class), any(RequestBody.class));
        verify(s3Client).createMultipartUpload(any(CreateMultipartUploadRequest.class));

        ArgumentCaptor<UploadPartRequest> partCaptor = ArgumentCaptor.forClass(UploadPartRequest.class);
        verify(s3Client, times(2)).uploadPart(partCaptor.capture(), any(RequestBody.class));

        List<UploadPartRequest> partRequests = partCaptor.getAllValues();
        assertEquals(1, partRequests.get(0).partNumber());
        assertEquals((long) FIVE_MB, partRequests.get(0).contentLength());
        assertEquals(2, partRequests.get(1).partNumber());
        assertEquals((long) 1024 * 1024, partRequests.get(1).contentLength());

        verify(s3Client).completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
    }

    @Test
    @DisplayName("测试大文件分片上传中途异常: 触发 abortMultipartUpload 确定性清理")
    void testMultipartUploadFailureTriggersAbort() {
        CreateMultipartUploadResponse createResp = CreateMultipartUploadResponse.builder().uploadId("up-fail-123").build();
        when(s3Client.createMultipartUpload(any(CreateMultipartUploadRequest.class))).thenReturn(createResp);

        when(s3Client.uploadPart(any(UploadPartRequest.class), any(RequestBody.class)))
                .thenThrow(S3Exception.builder().message("Network Disconnected").build());

        S3Driver driver = new S3Driver(s3Client, null, "test-bucket", null, null, "http://127.0.0.1:9000", true);

        byte[] largeData = new byte[FIVE_MB + 100];
        InputStream in = new ByteArrayInputStream(largeData);

        assertThrows(StorageException.class, () -> driver.upload("test/fail.dat", in, "application/octet-stream", -1));

        ArgumentCaptor<AbortMultipartUploadRequest> abortCaptor = ArgumentCaptor.forClass(AbortMultipartUploadRequest.class);
        verify(s3Client).abortMultipartUpload(abortCaptor.capture());
        assertEquals("up-fail-123", abortCaptor.getValue().uploadId());
        assertEquals("test-bucket", abortCaptor.getValue().bucket());
        assertEquals("test/fail.dat", abortCaptor.getValue().key());
    }

    @Test
    @DisplayName("测试 StorageTemplate 门面无已知长度流自适应上传")
    void testStorageTemplateAdaptiveUpload() {
        PutObjectResponse putResp = PutObjectResponse.builder().eTag("\"etag-tmpl\"").build();
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenReturn(putResp);

        S3Driver driver = new S3Driver(s3Client, null, "test-bucket", null, null, "http://127.0.0.1:9000", true);
        StorageDriverFactory factory = new StorageDriverFactory("s3");
        factory.registerDriver("s3", driver);
        StorageTemplate template = new StorageTemplate(factory);

        byte[] bytes = new byte[512];
        StorageObject obj = template.upload("template/stream.dat", new ByteArrayInputStream(bytes));

        assertNotNull(obj);
        assertEquals(512, obj.getSize());
        verify(s3Client).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    }
}
