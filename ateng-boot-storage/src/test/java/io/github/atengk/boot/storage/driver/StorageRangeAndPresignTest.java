package io.github.atengk.boot.storage.driver;

import io.github.atengk.boot.storage.exception.StorageException;
import io.github.atengk.boot.storage.model.PresignedDownloadRequest;
import io.github.atengk.boot.storage.model.PresignedUrl;
import io.github.atengk.boot.storage.template.StorageTemplate;
import io.github.atengk.boot.storage.util.StorageSecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.http.AbortableInputStream;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 字节范围读取 (Byte-Range Read) 与预签名下载响应头定制测试
 *
 * @author Ateng
 * @since 2026-10-28
 */
@DisplayName("字节范围读取与预签名响应头定制测试")
class StorageRangeAndPresignTest {

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
        s3Driver = new S3Driver(mockS3Client, mockS3Presigner, "test-bucket", "https://cdn.example.com", "s3-secret", "http://s3.example.com", true);
        storageTemplate = new StorageTemplate(localDriver);
    }

    @Test
    @DisplayName("LocalDriver 字节范围精准分段读取")
    void testLocalDriverByteRangeDownload() throws Exception {
        String path = "test-range.txt";
        byte[] fullData = "0123456789abcdefghijklmnopqrstuvwxyz".getBytes(StandardCharsets.UTF_8);
        localDriver.upload(path, new ByteArrayInputStream(fullData), "text/plain", fullData.length);

        // 读取 offset 10, length 6 -> "abcdef"
        try (InputStream in = localDriver.download(path, 10, 6)) {
            byte[] buffer = in.readAllBytes();
            assertEquals("abcdef", new String(buffer, StandardCharsets.UTF_8));
        }

        // 读取末尾 offset 30, length 6 -> "uvwxyz"
        try (InputStream in = localDriver.download(path, 30, 6)) {
            byte[] buffer = in.readAllBytes();
            assertEquals("uvwxyz", new String(buffer, StandardCharsets.UTF_8));
        }
    }

    @Test
    @DisplayName("LocalDriver 字节范围参数非法与越界防御")
    void testLocalDriverByteRangeValidation() {
        String path = "test-guard.txt";
        byte[] data = "hello world".getBytes(StandardCharsets.UTF_8);
        localDriver.upload(path, new ByteArrayInputStream(data), "text/plain", data.length);

        // 负偏移量
        assertThrows(StorageException.class, () -> localDriver.download(path, -1, 5));
        // 零长度或负长度
        assertThrows(StorageException.class, () -> localDriver.download(path, 0, 0));
        assertThrows(StorageException.class, () -> localDriver.download(path, 0, -2));
        // 偏移量超出文件大小
        assertThrows(StorageException.class, () -> localDriver.download(path, 100, 5));
    }

    @Test
    @DisplayName("S3Driver 发送标准 Range: bytes=start-end 请求")
    void testS3DriverByteRangeRequest() throws Exception {
        String path = "video/clip.mp4";
        long startOffset = 100;
        long length = 50;

        byte[] fakeSlice = new byte[50];
        ResponseInputStream<GetObjectResponse> fakeStream = new ResponseInputStream<>(
                GetObjectResponse.builder().contentLength(50L).build(),
                AbortableInputStream.create(new ByteArrayInputStream(fakeSlice))
        );
        when(mockS3Client.getObject(any(GetObjectRequest.class))).thenReturn(fakeStream);

        try (InputStream in = s3Driver.download(path, startOffset, length)) {
            assertNotNull(in);
            byte[] readBytes = in.readAllBytes();
            assertEquals(50, readBytes.length);
        }

        ArgumentCaptor<GetObjectRequest> captor = ArgumentCaptor.forClass(GetObjectRequest.class);
        verify(mockS3Client).getObject(captor.capture());
        GetObjectRequest captured = captor.getValue();
        assertEquals("test-bucket", captured.bucket());
        assertEquals("video/clip.mp4", captured.key());
        // 验证标准 Range 头格式：bytes=100-149
        assertEquals("bytes=100-149", captured.range());
    }

    @Test
    @DisplayName("S3Driver 范围读取入参校验拦截")
    void testS3DriverByteRangeValidation() {
        assertThrows(StorageException.class, () -> s3Driver.download("test.bin", -1, 10));
        assertThrows(StorageException.class, () -> s3Driver.download("test.bin", 0, 0));
        assertThrows(StorageException.class, () -> s3Driver.download("test.bin", 0, -10));
    }

    @Test
    @DisplayName("StorageTemplate 门面字节范围下载与快速失败防卫")
    void testStorageTemplateByteRangeDownload() {
        assertThrows(StorageException.class, () -> storageTemplate.download("test.bin", -5, 10));
        assertThrows(StorageException.class, () -> storageTemplate.download("test.bin", 0, 0));
    }

    @Test
    @DisplayName("RFC 5987 / RFC 6266 Content-Disposition 工具格式化验证")
    void testContentDispositionFormatting() {
        String result = StorageSecurityUtil.formatContentDisposition("年度财务报表 (2026).pdf");
        assertTrue(result.startsWith("attachment; filename=\""));
        assertTrue(result.contains("filename*=UTF-8''"));
        assertTrue(result.contains("%E5%B9%B4%E5%BA%A6%E8%B4%A2%E5%8A%A1%E6%8A%A5%E8%A1%A8"));

        // 空白或 null 处理
        assertEquals("attachment", StorageSecurityUtil.formatContentDisposition(null));
        assertEquals("attachment", StorageSecurityUtil.formatContentDisposition("   "));

        // CRLF 防注入
        String crlfResult = StorageSecurityUtil.formatContentDisposition("evil\r\nfilename.txt");
        assertTrue(!crlfResult.contains("\r") && !crlfResult.contains("\n"));
    }

    @Test
    @DisplayName("S3Driver 预签名下载支持友好文件名与响应头重写")
    void testS3DriverPresignedDownloadWithCustomHeaders() throws Exception {
        String path = "reports/annual.pdf";
        URL rawUrl = new URL("https://s3.us-east-1.amazonaws.com/test-bucket/reports/annual.pdf?X-Amz-Signature=sig123");
        Instant expiration = Instant.now().plus(Duration.ofMinutes(15));

        PresignedGetObjectRequest mockPresigned = mock(PresignedGetObjectRequest.class);
        when(mockPresigned.url()).thenReturn(rawUrl);
        when(mockPresigned.expiration()).thenReturn(expiration);
        when(mockS3Presigner.presignGetObject(any(GetObjectPresignRequest.class))).thenReturn(mockPresigned);

        PresignedDownloadRequest request = PresignedDownloadRequest.builder()
                .path(path)
                .duration(Duration.ofMinutes(10))
                .downloadFilename("2026年度财务分析.pdf")
                .responseContentType("application/pdf")
                .build();

        PresignedUrl presignedUrl = s3Driver.getPresignedUrl(request);
        assertNotNull(presignedUrl);
        assertEquals("GET", presignedUrl.getHttpMethod());

        ArgumentCaptor<GetObjectPresignRequest> captor = ArgumentCaptor.forClass(GetObjectPresignRequest.class);
        verify(mockS3Presigner).presignGetObject(captor.capture());
        GetObjectPresignRequest presignReq = captor.getValue();
        assertEquals(Duration.ofMinutes(10), presignReq.signatureDuration());

        GetObjectRequest s3GetReq = presignReq.getObjectRequest();
        assertEquals("test-bucket", s3GetReq.bucket());
        assertEquals("reports/annual.pdf", s3GetReq.key());
        assertEquals("application/pdf", s3GetReq.responseContentType());
        assertNotNull(s3GetReq.responseContentDisposition());
        assertTrue(s3GetReq.responseContentDisposition().contains("filename*=UTF-8''"));

        // 便捷重载方法验证
        PresignedUrl presignedOverload = s3Driver.getPresignedUrl(path, Duration.ofMinutes(5), "导出数据.xlsx");
        assertNotNull(presignedOverload);
    }

    @Test
    @DisplayName("LocalDriver 预签名下载携带自定义下载文件名与参数")
    void testLocalDriverPresignedDownloadWithFilename() {
        PresignedDownloadRequest request = PresignedDownloadRequest.builder()
                .path("docs/contract.docx")
                .duration(Duration.ofMinutes(20))
                .downloadFilename("客户合同.docx")
                .responseContentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document")
                .build();

        PresignedUrl presignedUrl = localDriver.getPresignedUrl(request);
        assertNotNull(presignedUrl);
        assertEquals("GET", presignedUrl.getHttpMethod());
        assertTrue(presignedUrl.getUrl().contains("path=docs/contract.docx"));
        assertTrue(presignedUrl.getUrl().contains("filename="));
        assertTrue(presignedUrl.getUrl().contains("contentType="));
    }

    @Test
    @DisplayName("StorageTemplate 预签名重载方法门面透传")
    void testStorageTemplatePresignedOverloads() {
        PresignedUrl url1 = storageTemplate.getPresignedUrl("test.png", Duration.ofMinutes(15), "头像.png");
        assertNotNull(url1);

        PresignedUrl url2 = storageTemplate.getPresignedUrl(PresignedDownloadRequest.builder()
                .path("test.png")
                .downloadFilename("avatar.png")
                .build());
        assertNotNull(url2);
    }
}
