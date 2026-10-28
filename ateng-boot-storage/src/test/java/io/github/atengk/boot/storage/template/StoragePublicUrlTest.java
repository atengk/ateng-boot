package io.github.atengk.boot.storage.template;

import io.github.atengk.boot.storage.driver.LocalDriver;
import io.github.atengk.boot.storage.driver.S3Driver;
import io.github.atengk.boot.storage.driver.StorageDriver;
import io.github.atengk.boot.storage.factory.StorageDriverFactory;
import io.github.atengk.boot.storage.model.StorageObject;
import io.github.atengk.boot.storage.properties.S3StorageProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ObjectCannedACL;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectResponse;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 永久公开直接访问链接与公共读 CannedAcl 单元测试
 *
 * @author Ateng
 * @since 2026-10-28
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Storage 永久公开直链与 Canned ACL 测试")
class StoragePublicUrlTest {

    @Mock
    private S3Client s3Client;

    @Test
    @DisplayName("测试 S3Driver getUrl: 配置 CDN 自定义域名模式")
    void testS3GetUrlWithCustomDomain() {
        S3StorageProperties properties = new S3StorageProperties();
        properties.setEndpoint("http://127.0.0.1:9000");
        properties.setBucket("public-bucket");
        properties.setDomain("https://cdn.mycompany.com");
        properties.setPathStyle(true);

        S3Driver driver = new S3Driver(s3Client, null, properties.getBucket(), properties.getDomain(), null, properties.getEndpoint(), properties.isPathStyle());
        assertEquals("https://cdn.mycompany.com/avatars/user1.png", driver.getUrl("avatars/user1.png"));
        assertEquals("https://cdn.mycompany.com/avatars/user1.png", driver.getUrl("/avatars/user1.png"));
    }

    @Test
    @DisplayName("测试 S3Driver getUrl: Path-Style 模式 (MinIO / RustFS)")
    void testS3GetUrlPathStyle() {
        S3StorageProperties properties = new S3StorageProperties();
        properties.setEndpoint("http://103.236.97.120:47875");
        properties.setBucket("ateng-test");
        properties.setPathStyle(true);

        S3Driver driver = new S3Driver(s3Client, null, properties.getBucket(), null, null, properties.getEndpoint(), properties.isPathStyle());
        assertEquals("http://103.236.97.120:47875/ateng-test/docs/readme.txt", driver.getUrl("docs/readme.txt"));
    }

    @Test
    @DisplayName("测试 S3Driver getUrl: Virtual-Host 模式 (AWS / 阿里云 OSS)")
    void testS3GetUrlVirtualHostStyle() {
        S3StorageProperties properties = new S3StorageProperties();
        properties.setEndpoint("https://oss-cn-hangzhou.aliyuncs.com");
        properties.setBucket("my-oss-bucket");
        properties.setPathStyle(false);

        S3Driver driver = new S3Driver(s3Client, null, properties.getBucket(), null, null, properties.getEndpoint(), properties.isPathStyle());
        assertEquals("https://my-oss-bucket.oss-cn-hangzhou.aliyuncs.com/images/banner.jpg", driver.getUrl("images/banner.jpg"));
    }

    @Test
    @DisplayName("测试 LocalDriver getUrl")
    void testLocalDriverGetUrl() {
        LocalDriver driverWithDomain = new LocalDriver("target/test-storage", "http://localhost:8080");
        assertEquals("http://localhost:8080/files/doc.pdf", driverWithDomain.getUrl("files/doc.pdf"));

        LocalDriver driverWithoutDomain = new LocalDriver("target/test-storage");
        assertEquals("/files/doc.pdf", driverWithoutDomain.getUrl("files/doc.pdf"));
    }

    @Test
    @DisplayName("测试 StorageTemplate getUrl 门面方法透传")
    void testStorageTemplateGetUrl() {
        LocalDriver localDriver = new LocalDriver("target/test-storage", "http://localhost:8080");
        StorageDriverFactory factory = new StorageDriverFactory("primary");
        factory.registerDriver("primary", localDriver);
        StorageTemplate template = new StorageTemplate(factory);

        assertEquals("http://localhost:8080/assets/logo.png", template.getUrl("assets/logo.png"));
    }

    @Test
    @DisplayName("测试 S3Driver uploadPublic 设置 ObjectCannedACL.PUBLIC_READ")
    void testS3UploadPublicSetsCannedAcl() {
        PutObjectResponse putResp = PutObjectResponse.builder().eTag("\"etag-pub\"").build();
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenReturn(putResp);

        S3StorageProperties properties = new S3StorageProperties();
        properties.setEndpoint("http://127.0.0.1:9000");
        properties.setBucket("pub-bucket");
        S3Driver driver = new S3Driver(s3Client, null, properties.getBucket(), null, null, properties.getEndpoint(), true);

        byte[] bytes = "Public content".getBytes(StandardCharsets.UTF_8);
        StorageObject obj = driver.uploadPublic("public/hello.txt", new ByteArrayInputStream(bytes), "text/plain");

        assertNotNull(obj);
        assertEquals("public/hello.txt", obj.getPath());

        ArgumentCaptor<PutObjectRequest> captor = ArgumentCaptor.forClass(PutObjectRequest.class);
        verify(s3Client).putObject(captor.capture(), any(RequestBody.class));
        assertEquals(ObjectCannedACL.PUBLIC_READ, captor.getValue().acl());
    }

    @Test
    @DisplayName("测试 StorageTemplate uploadPublic 门面上传")
    void testStorageTemplateUploadPublic() {
        PutObjectResponse putResp = PutObjectResponse.builder().eTag("\"etag-template-pub\"").build();
        when(s3Client.putObject(any(PutObjectRequest.class), any(RequestBody.class))).thenReturn(putResp);

        S3Driver s3Driver = new S3Driver(s3Client, null, "pub-bucket", "https://cdn.example.com", null, "http://127.0.0.1:9000", true);
        StorageDriverFactory factory = new StorageDriverFactory("s3");
        factory.registerDriver("s3", s3Driver);
        StorageTemplate template = new StorageTemplate(factory);

        byte[] bytes = "Template public content".getBytes(StandardCharsets.UTF_8);
        StorageObject obj = template.uploadPublic("public/shared.txt", new ByteArrayInputStream(bytes), "text/plain");

        assertNotNull(obj);
        assertEquals("public/shared.txt", obj.getPath());
        assertEquals("https://cdn.example.com/public/shared.txt", template.getUrl("public/shared.txt"));
    }
}
