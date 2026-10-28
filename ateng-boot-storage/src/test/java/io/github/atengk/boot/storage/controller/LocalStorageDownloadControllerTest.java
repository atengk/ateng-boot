package io.github.atengk.boot.storage.controller;

import io.github.atengk.boot.storage.driver.LocalDriver;
import io.github.atengk.boot.storage.model.PresignedDownloadRequest;
import io.github.atengk.boot.storage.model.PresignedUrl;
import io.github.atengk.boot.storage.properties.StorageProperties;
import io.github.atengk.boot.storage.util.StorageSecurityUtil;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * LocalStorageDownloadController 预签名下载端点端到端测试
 *
 * @author Ateng
 * @since 2026-10-17
 */
@DisplayName("LocalStorageDownloadController 预签名下载端点测试")
class LocalStorageDownloadControllerTest {

    @TempDir
    Path tempDir;

    private static final String SECRET_KEY = "my-test-secure-hmac-key-2026";
    private static final String DOMAIN = "http://localhost:8080";

    private LocalDriver driver;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        StorageProperties properties = new StorageProperties();
        properties.getLocal().setBasePath(tempDir.toString());
        properties.getLocal().setDomain(DOMAIN);
        properties.getLocal().setSecretKey(SECRET_KEY);

        driver = new LocalDriver(tempDir.toString(), DOMAIN, SECRET_KEY);
        LocalStorageDownloadController controller = new LocalStorageDownloadController(properties, driver);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    @DisplayName("在有效期内携带合法签名下载文件成功，验证流内容与响应头")
    void testDownloadValidPresignedUrl() throws Exception {
        byte[] fileBytes = "PDF Report Content 2026".getBytes(StandardCharsets.UTF_8);
        String path = "reports/2026/financial.pdf";
        driver.upload(path, new ByteArrayInputStream(fileBytes), "application/pdf", fileBytes.length);

        PresignedUrl presigned = driver.getPresignedUrl(path, Duration.ofMinutes(15));
        URI uri = URI.create(presigned.getUrl());

        mockMvc.perform(get(uri.getPath() + "?" + uri.getQuery()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/pdf"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, Matchers.containsString("financial.pdf")))
                .andExpect(content().bytes(fileBytes));
    }

    @Test
    @DisplayName("篡改下载路径参数时签名校验失败并被 403 阻断")
    void testTamperedPathReturnsForbidden() throws Exception {
        String path = "reports/2026/financial.pdf";
        PresignedUrl presigned = driver.getPresignedUrl(path, Duration.ofMinutes(15));
        URI uri = URI.create(presigned.getUrl());

        // 将原 query 中的 path 篡改为 another.pdf
        String tamperedQuery = uri.getQuery().replace("financial.pdf", "another.pdf");

        mockMvc.perform(get(uri.getPath() + "?" + tamperedQuery))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("篡改 expires 过期时间参数时签名校验失败并被 403 阻断")
    void testTamperedExpiresReturnsForbidden() throws Exception {
        String path = "reports/2026/financial.pdf";
        PresignedUrl presigned = driver.getPresignedUrl(path, Duration.ofMinutes(15));
        URI uri = URI.create(presigned.getUrl());

        // 篡改 expires 数值
        String originalQuery = uri.getQuery();
        String tamperedQuery = originalQuery.replaceFirst("expires=\\d+", "expires=9999999999");

        mockMvc.perform(get(uri.getPath() + "?" + tamperedQuery))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("访问已过期的外链时被时效检查阻断并返回 403")
    void testExpiredPresignedUrlReturnsForbidden() throws Exception {
        String path = "reports/expired.txt";
        // 构造一个在过去已过期的签名
        long expiredTimestamp = Instant.now().minusSeconds(60).getEpochSecond();
        String signature = StorageSecurityUtil.hmacSha256(path + ":" + expiredTimestamp, SECRET_KEY);

        mockMvc.perform(get("/storage/local/download")
                        .param("path", path)
                        .param("expires", String.valueOf(expiredTimestamp))
                        .param("signature", signature))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("使用伪造的随机签名访问时被 403 阻断")
    void testFakeSignatureReturnsForbidden() throws Exception {
        long validTimestamp = Instant.now().plusSeconds(600).getEpochSecond();

        mockMvc.perform(get("/storage/local/download")
                        .param("path", "safe/doc.txt")
                        .param("expires", String.valueOf(validTimestamp))
                        .param("signature", "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("尝试路径遍历注入攻击时被二次防卫拦截并返回 400")
    void testPathTraversalAttackReturnsBadRequest() throws Exception {
        long validTimestamp = Instant.now().plusSeconds(600).getEpochSecond();

        mockMvc.perform(get("/storage/local/download")
                        .param("path", "../../etc/shadow")
                        .param("expires", String.valueOf(validTimestamp))
                        .param("signature", "dummy-sig"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("签名与时效均合法但文件已不存在时返回 404")
    void testValidSignatureButFileNotExistsReturnsNotFound() throws Exception {
        String path = "ghost/non-existent.txt";
        long validTimestamp = Instant.now().plusSeconds(600).getEpochSecond();
        String signature = StorageSecurityUtil.hmacSha256(path + ":" + validTimestamp, SECRET_KEY);

        mockMvc.perform(get("/storage/local/download")
                        .param("path", path)
                        .param("expires", String.valueOf(validTimestamp))
                        .param("signature", signature))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("携带自定义友好文件名与响应 Content-Type 预签名下载成功")
    void testDownloadWithCustomFilenameAndContentType() throws Exception {
        byte[] fileBytes = "Custom Filename Test Data".getBytes(StandardCharsets.UTF_8);
        String path = "data/export-raw.bin";
        driver.upload(path, new ByteArrayInputStream(fileBytes), "application/octet-stream", fileBytes.length);

        PresignedDownloadRequest request = PresignedDownloadRequest.builder()
                .path(path)
                .duration(Duration.ofMinutes(15))
                .downloadFilename("2026年度总结.xlsx")
                .responseContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .build();
        PresignedUrl presigned = driver.getPresignedUrl(request);
        URI uri = URI.create(presigned.getUrl());

        mockMvc.perform(get(uri.getPath() + "?" + uri.getQuery()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, Matchers.containsString("filename*=UTF-8''")))
                .andExpect(content().bytes(fileBytes));
    }
}
