package io.github.atengk.boot.storage.controller;

import io.github.atengk.boot.storage.driver.LocalDriver;
import io.github.atengk.boot.storage.driver.StorageDriver;
import io.github.atengk.boot.storage.exception.StorageException;
import io.github.atengk.boot.storage.exception.StorageResultCode;
import io.github.atengk.boot.storage.factory.StorageDriverFactory;
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
import java.lang.reflect.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
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

    @TempDir
    Path archiveDir;

    private static final String SECRET_KEY = "my-test-secure-hmac-key-2026";
    private static final String ARCHIVE_SECRET_KEY = "archive-secure-hmac-key-2026";
    private static final String DOMAIN = "http://localhost:8080";

    private StorageProperties properties;
    private LocalDriver driver;
    private LocalDriver archiveDriver;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        properties = new StorageProperties();
        properties.getLocal().setBasePath(tempDir.toString());
        properties.getLocal().setDomain(DOMAIN);
        properties.getLocal().setSecretKey(SECRET_KEY);

        driver = new LocalDriver(tempDir.toString(), DOMAIN, SECRET_KEY);
        archiveDriver = new LocalDriver(archiveDir.toString(), DOMAIN, ARCHIVE_SECRET_KEY,
                LocalDriver.DEFAULT_DOWNLOAD_ENDPOINT, LocalDriver.DEFAULT_UPLOAD_ENDPOINT, "archive");
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

    @Test
    @DisplayName("篡改自定义文件名或响应头时签名校验失败并被 403 阻断")
    void testTamperedFilenameOrHeaderReturnsForbidden() throws Exception {
        byte[] fileBytes = "Sensitive Payload".getBytes(StandardCharsets.UTF_8);
        String path = "data/sensitive.bin";
        driver.upload(path, new ByteArrayInputStream(fileBytes), "application/octet-stream", fileBytes.length);

        PresignedDownloadRequest request = PresignedDownloadRequest.builder()
                .path(path)
                .duration(Duration.ofMinutes(15))
                .downloadFilename("report.pdf")
                .responseContentType("application/pdf")
                .build();
        PresignedUrl presigned = driver.getPresignedUrl(request);
        URI uri = URI.create(presigned.getUrl());

        // 尝试篡改文件名参数
        String tamperedQuery = uri.getQuery().replace("report.pdf", "malicious.exe");
        mockMvc.perform(get(uri.getPath() + "?" + tamperedQuery))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("多源模式下根据 source 参数动态路由命名本地源下载文件成功")
    void testMultiSourceDownloadSuccess() throws Exception {
        byte[] archiveBytes = "Archive Cold Data 2026".getBytes(StandardCharsets.UTF_8);
        String path = "cold/2026/audit.log";
        archiveDriver.upload(path, new ByteArrayInputStream(archiveBytes), "text/plain", archiveBytes.length);

        StorageDriverFactory factory = new StorageDriverFactory("localMain", Map.of(
                "localMain", driver,
                "archive", archiveDriver
        ));
        LocalStorageDownloadController multiController = new LocalStorageDownloadController(properties, factory);
        MockMvc multiMockMvc = MockMvcBuilders.standaloneSetup(multiController).build();

        PresignedUrl presigned = archiveDriver.getPresignedUrl(path, Duration.ofMinutes(15));
        assertTrue(presigned.getUrl().contains("source=archive"));
        URI uri = URI.create(presigned.getUrl());

        multiMockMvc.perform(get(uri.getPath() + "?" + uri.getQuery()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "text/plain"))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, Matchers.containsString("audit.log")))
                .andExpect(content().bytes(archiveBytes));

        // 未指定 source 与显式指定 default 均路由至主本地源
        byte[] primaryBytes = "Primary Hot Data 2026".getBytes(StandardCharsets.UTF_8);
        String primaryPath = "hot/readme.txt";
        driver.upload(primaryPath, new ByteArrayInputStream(primaryBytes), "text/plain", primaryBytes.length);
        PresignedUrl primaryUrl = driver.getPresignedUrl(primaryPath, Duration.ofMinutes(15));
        URI primaryUri = URI.create(primaryUrl.getUrl());

        multiMockMvc.perform(get(primaryUri.getPath() + "?" + primaryUri.getQuery()))
                .andExpect(status().isOk())
                .andExpect(content().bytes(primaryBytes));

        PresignedUrl explicitDefaultUrl = driver.getPresignedUrl(PresignedDownloadRequest.builder()
                .path(primaryPath)
                .duration(Duration.ofMinutes(15))
                .source("default")
                .build());
        URI explicitDefaultUri = URI.create(explicitDefaultUrl.getUrl());
        multiMockMvc.perform(get(explicitDefaultUri.getPath() + "?" + explicitDefaultUri.getQuery()))
                .andExpect(status().isOk())
                .andExpect(content().bytes(primaryBytes));
    }

    @Test
    @DisplayName("多源模式下篡改、移除或传递不存在的 source 时均阻断下载")
    void testMultiSourceTamperedOrUnknownSourceBlocked() throws Exception {
        byte[] archiveBytes = "Confidential Archive".getBytes(StandardCharsets.UTF_8);
        String path = "secure/archive.txt";
        archiveDriver.upload(path, new ByteArrayInputStream(archiveBytes), "text/plain", archiveBytes.length);

        StorageDriverFactory factory = new StorageDriverFactory("localMain", Map.of(
                "localMain", driver,
                "archive", archiveDriver
        ));
        LocalStorageDownloadController multiController = new LocalStorageDownloadController(properties, factory);
        MockMvc multiMockMvc = MockMvcBuilders.standaloneSetup(multiController).build();

        PresignedUrl presigned = archiveDriver.getPresignedUrl(path, Duration.ofMinutes(15));
        URI uri = URI.create(presigned.getUrl());

        // 1. 不存在的 source 返回 400
        String unknownQuery = uri.getQuery().replace("source=archive", "source=unknown-source");
        multiMockMvc.perform(get(uri.getPath() + "?" + unknownQuery))
                .andExpect(status().isBadRequest());

        // 2. 移除 source 参数后因签名不匹配返回 403
        String strippedQuery = uri.getQuery().replace("&source=archive", "");
        multiMockMvc.perform(get(uri.getPath() + "?" + strippedQuery))
                .andExpect(status().isForbidden());

        // 3. 篡改为另一合法本地源 localMain 时校验失败返回 403
        String swappedQuery = uri.getQuery().replace("source=archive", "source=localMain");
        multiMockMvc.perform(get(uri.getPath() + "?" + swappedQuery))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("目标存储源对应驱动非 LocalDriver 时抛出语义化 StorageException")
    void testNonLocalDriverSourceThrowsSemanticStorageException() {
        StorageDriver nonLocalStub = (StorageDriver) Proxy.newProxyInstance(
                StorageDriver.class.getClassLoader(),
                new Class<?>[]{StorageDriver.class},
                (proxy, method, args) -> null);
        StorageDriverFactory factory = new StorageDriverFactory("s3Main", Map.of(
                "s3Main", nonLocalStub,
                "archive", archiveDriver
        ));
        LocalStorageDownloadController multiController = new LocalStorageDownloadController(properties, factory);

        long futureExpires = Instant.now().plusSeconds(600).getEpochSecond();
        StorageException ex = assertThrows(StorageException.class, () ->
                multiController.download("docs/file.pdf", futureExpires, "sig", null, null, null, "s3Main"));
        assertEquals(StorageResultCode.OPERATION_FAILED, ex.getResultCode());

        StorageException defaultEx = assertThrows(StorageException.class, () ->
                multiController.download("docs/file.pdf", futureExpires, "sig", null, null, null, null));
        assertEquals(StorageResultCode.OPERATION_FAILED, defaultEx.getResultCode());
    }
}
