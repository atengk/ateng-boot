package io.github.atengk.boot.storage.properties;

import io.github.atengk.boot.storage.driver.S3Driver;
import io.github.atengk.boot.storage.exception.StorageException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * S3 存储配置属性与客户端装配单元测试
 *
 * @author Ateng
 * @since 2026-10-28
 */
@DisplayName("S3StorageProperties 生产级连接池与超时治理测试")
class S3StoragePropertiesTest {

    @Test
    @DisplayName("测试 S3 连接池与超时默认值符合生产安全基线")
    void testDefaultValues() {
        S3StorageProperties properties = new S3StorageProperties();

        assertEquals(Duration.ofSeconds(10), properties.getConnectTimeout());
        assertEquals(Duration.ofSeconds(30), properties.getSocketTimeout());
        assertEquals(Duration.ofSeconds(60), properties.getApiCallTimeout());
        assertEquals(Duration.ofSeconds(10), properties.getConnectionAcquisitionTimeout());
        assertEquals(50, properties.getMaxConnections());
        assertEquals("us-east-1", properties.getRegion());
        assertEquals(true, properties.isPathStyle());
    }

    @Test
    @DisplayName("测试 S3 配置属性支持自定义覆盖")
    void testCustomProperties() {
        S3StorageProperties properties = new S3StorageProperties();
        properties.setConnectTimeout(Duration.ofSeconds(5));
        properties.setSocketTimeout(Duration.ofSeconds(15));
        properties.setApiCallTimeout(Duration.ofSeconds(20));
        properties.setConnectionAcquisitionTimeout(Duration.ofSeconds(8));
        properties.setMaxConnections(100);

        assertEquals(Duration.ofSeconds(5), properties.getConnectTimeout());
        assertEquals(Duration.ofSeconds(15), properties.getSocketTimeout());
        assertEquals(Duration.ofSeconds(20), properties.getApiCallTimeout());
        assertEquals(Duration.ofSeconds(8), properties.getConnectionAcquisitionTimeout());
        assertEquals(100, properties.getMaxConnections());
    }

    @Test
    @DisplayName("测试 S3Driver.createS3Client 防御性检查 null")
    void testCreateS3ClientNull() {
        assertThrows(StorageException.class, () -> S3Driver.createS3Client(null));
    }

    @Test
    @DisplayName("测试 S3Driver.createS3Client 装配 ApacheHttpClient 连接池与超时治理")
    void testCreateS3ClientWithApacheHttpClient() {
        S3StorageProperties properties = new S3StorageProperties();
        properties.setEndpoint("http://127.0.0.1:9000");
        properties.setBucket("test-bucket");
        properties.setAccessKey("admin");
        properties.setSecretKey("Admin@123");
        properties.setRegion("us-east-1");
        properties.setPathStyle(true);
        properties.setConnectTimeout(Duration.ofSeconds(5));
        properties.setSocketTimeout(Duration.ofSeconds(15));
        properties.setApiCallTimeout(Duration.ofSeconds(30));
        properties.setConnectionAcquisitionTimeout(Duration.ofSeconds(5));
        properties.setMaxConnections(20);

        try (S3Client s3Client = S3Driver.createS3Client(properties)) {
            assertNotNull(s3Client, "S3Client 实例应当成功构建且非空");
        }
    }
}
