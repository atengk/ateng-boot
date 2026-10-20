package io.github.atengk.boot.storage.controller;

import io.github.atengk.boot.storage.driver.LocalDriver;
import io.github.atengk.boot.storage.guard.StorageGuard;
import io.github.atengk.boot.storage.model.PresignedUploadRequest;
import io.github.atengk.boot.storage.model.PresignedUrl;
import io.github.atengk.boot.storage.properties.StorageProperties;
import io.github.atengk.boot.storage.util.StorageSecurityUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * LocalStorageUploadController 预签名直传写入端点测试
 *
 * @author Ateng
 * @since 2026-10-20
 */
@DisplayName("LocalStorageUploadController 预签名直传端点测试")
class LocalStorageUploadControllerTest {

    @TempDir
    Path tempDir;

    private static final String SECRET_KEY = "my-test-secure-upload-key-2026";
    private static final String DOMAIN = "http://localhost:8080";

    private LocalDriver driver;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        StorageProperties properties = new StorageProperties();
        properties.getLocal().setBasePath(tempDir.toString());
        properties.getLocal().setDomain(DOMAIN);
        properties.getLocal().setSecretKey(SECRET_KEY);
        properties.getGuard().setEnabled(true);

        driver = new LocalDriver(tempDir.toString(), DOMAIN, SECRET_KEY);
        LocalStorageUploadController controller = new LocalStorageUploadController(properties, driver);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("在有效期内携带合法签名通过 HTTP PUT 直传普通文本文件成功")
    void testUploadValidPresignedUrl() throws Exception {
        byte[] content = "Hello Ateng Boot Direct Upload 2026".getBytes(StandardCharsets.UTF_8);
        String path = "uploads/2026/hello.txt";

        PresignedUploadRequest request = PresignedUploadRequest.builder()
                .path(path)
                .duration(Duration.ofMinutes(15))
                .contentType("text/plain")
                .maxSizeBytes(1024)
                .build();

        PresignedUrl presigned = driver.getPresignedUploadUrl(request);
        assertNotNull(presigned);
        assertEquals("PUT", presigned.getHttpMethod());
        assertEquals("text/plain", presigned.getHeaders().get("Content-Type"));

        URI uri = URI.create(presigned.getUrl());

        mockMvc.perform(put(uri.getPath() + "?" + uri.getQuery())
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(content))
                .andExpect(status().isOk());

        // 校验磁盘物理文件存在且内容完全一致
        Path savedFile = tempDir.resolve("uploads/2026/hello.txt");
        assertTrue(Files.exists(savedFile));
        assertArrayEquals(content, Files.readAllBytes(savedFile));
    }

    @Test
    @DisplayName("篡改直传路径参数时签名校验失败并被 403 阻断")
    void testTamperedPathReturnsForbidden() throws Exception {
        String path = "uploads/2026/hello.txt";
        PresignedUploadRequest request = PresignedUploadRequest.builder()
                .path(path)
                .duration(Duration.ofMinutes(15))
                .build();

        PresignedUrl presigned = driver.getPresignedUploadUrl(request);
        URI uri = URI.create(presigned.getUrl());

        // 篡改 query 中的 path
        String tamperedQuery = uri.getQuery().replace("hello.txt", "evil.sh");

        mockMvc.perform(put(uri.getPath() + "?" + tamperedQuery)
                        .contentType(MediaType.APPLICATION_OCTET_STREAM)
                        .content("fake data".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("篡改 HMAC 签名参数时被 403 阻断")
    void testTamperedSignatureReturnsForbidden() throws Exception {
        String path = "uploads/2026/hello.txt";
        PresignedUploadRequest request = PresignedUploadRequest.builder()
                .path(path)
                .duration(Duration.ofMinutes(15))
                .build();

        PresignedUrl presigned = driver.getPresignedUploadUrl(request);
        URI uri = URI.create(presigned.getUrl());

        // 篡改 signature 字符
        String tamperedQuery = uri.getQuery().replaceFirst("signature=[a-f0-9]+", "signature=0000000000000000000000000000000000000000000000000000000000000000");

        mockMvc.perform(put(uri.getPath() + "?" + tamperedQuery)
                        .content("fake data".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("预签名外链过期时直传请求被 403 阻断")
    void testExpiredUrlReturnsForbidden() throws Exception {
        String path = "uploads/2026/hello.txt";
        long pastExpires = 1000000000L; // 过去的时间戳
        String signPayload = path + ":" + pastExpires + "::-1";
        String signature = StorageSecurityUtil.hmacSha256(signPayload, SECRET_KEY);

        String query = "path=" + path + "&expires=" + pastExpires + "&signature=" + signature;

        mockMvc.perform(put("/storage/local/upload?" + query)
                        .content("data".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("直传 Content-Type 与预签名限定类型不匹配时被 400 阻断")
    void testContentTypeMismatchReturnsBadRequest() throws Exception {
        String path = "uploads/2026/contract.pdf";
        PresignedUploadRequest request = PresignedUploadRequest.builder()
                .path(path)
                .duration(Duration.ofMinutes(15))
                .contentType("application/pdf")
                .build();

        PresignedUrl presigned = driver.getPresignedUploadUrl(request);
        URI uri = URI.create(presigned.getUrl());

        // 客户端声明了 text/plain，与预签名的 application/pdf 不符
        mockMvc.perform(put(uri.getPath() + "?" + uri.getQuery())
                        .contentType(MediaType.TEXT_PLAIN)
                        .content("fake pdf".getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("文件大小超出预签名 maxBytes 限制时被 413 Payload Too Large 拦截")
    void testPayloadTooLargeReturns413() throws Exception {
        String path = "uploads/2026/small.txt";
        PresignedUploadRequest request = PresignedUploadRequest.builder()
                .path(path)
                .duration(Duration.ofMinutes(15))
                .maxSizeBytes(10) // 限制最大 10 字节
                .build();

        PresignedUrl presigned = driver.getPresignedUploadUrl(request);
        URI uri = URI.create(presigned.getUrl());

        byte[] largeBytes = "This is a very long string exceeding 10 bytes limit".getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(put(uri.getPath() + "?" + uri.getQuery())
                        .contentType(MediaType.TEXT_PLAIN)
                        .content(largeBytes))
                .andExpect(status().isPayloadTooLarge());

        // 确认磁盘中没有残留此文件
        Path savedFile = tempDir.resolve("uploads/2026/small.txt");
        assertFalse(Files.exists(savedFile));
    }

    @Test
    @DisplayName("伪装文件（扩展名为 png 但内容为 PE EXE）直传触发 StorageGuard 拦截返回 415")
    void testStorageGuardMagicSniffingRejectsCamouflage() throws Exception {
        String path = "uploads/2026/avatar.png";
        PresignedUploadRequest request = PresignedUploadRequest.builder()
                .path(path)
                .duration(Duration.ofMinutes(15))
                .contentType("image/png")
                .build();

        PresignedUrl presigned = driver.getPresignedUploadUrl(request);
        URI uri = URI.create(presigned.getUrl());

        // 伪装文件内容：PE EXE MZ 头
        byte[] fakeExe = new byte[]{(byte) 0x4D, (byte) 0x5A, 0x00, 0x00, 0x01, 0x02};

        mockMvc.perform(put(uri.getPath() + "?" + uri.getQuery())
                        .contentType("image/png")
                        .content(fakeExe))
                .andExpect(status().isUnsupportedMediaType());
    }

    @Test
    @DisplayName("携带 uploadId 与 chunkIndex 通过直传端点上传分片数据成功")
    void testChunkUploadValid() throws Exception {
        String targetPath = "bigfiles/video.mp4";
        String uploadId = driver.initiateChunkedUpload(targetPath);
        int chunkIndex = 1;
        long expires = System.currentTimeMillis() / 1000 + 900;

        String signPayload = targetPath + ":" + expires + ":" + uploadId + ":" + chunkIndex;
        String signature = StorageSecurityUtil.hmacSha256(signPayload, SECRET_KEY);

        byte[] chunkData = "Chunk 1 Binary Data 2026".getBytes(StandardCharsets.UTF_8);
        String query = "path=" + targetPath + "&expires=" + expires + "&signature=" + signature
                + "&uploadId=" + uploadId + "&chunkIndex=" + chunkIndex;

        mockMvc.perform(put("/storage/local/upload?" + query)
                        .content(chunkData))
                .andExpect(status().isOk());

        // 校验分片暂存目录 .chunks/{uploadId}/chunk_1 存在且内容正确
        Path chunkFile = tempDir.resolve(".chunks").resolve(uploadId).resolve("chunk_1");
        assertTrue(Files.exists(chunkFile));
        assertArrayEquals(chunkData, Files.readAllBytes(chunkFile));
    }

    @Test
    @DisplayName("端到端联动测试：通过 driver.getPresignedUploadPartUrl 生成分片直传链接并 PUT 上传成功")
    void testEndToEndPresignedUploadPartUrl() throws Exception {
        String targetPath = "media/movie.mp4";
        String uploadId = driver.initiateChunkedUpload(targetPath);

        // 1. 生成分片直传预签名 URL
        PresignedUrl presignedPartUrl = driver.getPresignedUploadPartUrl(targetPath, uploadId, 1, Duration.ofMinutes(15));
        assertNotNull(presignedPartUrl);
        assertEquals("PUT", presignedPartUrl.getHttpMethod());

        URI uri = URI.create(presignedPartUrl.getUrl());
        byte[] chunkBytes = "High Definition Video Chunk 1".getBytes(StandardCharsets.UTF_8);

        // 2. 模拟客户端直接向生成的预签名 URL 发起 PUT 请求
        mockMvc.perform(put(uri.getPath() + "?" + uri.getQuery())
                        .content(chunkBytes))
                .andExpect(status().isOk());

        // 3. 校验磁盘中成功落盘该分片
        Path chunkFile = tempDir.resolve(".chunks").resolve(uploadId).resolve("chunk_1");
        assertTrue(Files.exists(chunkFile));
        assertArrayEquals(chunkBytes, Files.readAllBytes(chunkFile));
    }
}
