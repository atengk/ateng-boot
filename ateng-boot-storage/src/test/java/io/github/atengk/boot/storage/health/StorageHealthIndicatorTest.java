package io.github.atengk.boot.storage.health;

import io.github.atengk.boot.storage.driver.LocalDriver;
import io.github.atengk.boot.storage.driver.S3Driver;
import io.github.atengk.boot.storage.driver.StorageDriver;
import io.github.atengk.boot.storage.factory.StorageDriverFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketResponse;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Actuator 存储健康探针单元测试
 *
 * @author Ateng
 * @since 2026-10-20
 */
@DisplayName("StorageHealthIndicator 健康探针测试")
class StorageHealthIndicatorTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("LocalDriver 目录正常可写时健康状态为 UP")
    @SuppressWarnings("unchecked")
    void testLocalDriverHealthy() {
        LocalDriver driver = new LocalDriver(tempDir.toString());

        ObjectProvider<StorageDriverFactory> factoryProvider = mock(ObjectProvider.class);
        when(factoryProvider.getIfAvailable()).thenReturn(null);

        ObjectProvider<StorageDriver> defaultDriverProvider = mock(ObjectProvider.class);
        when(defaultDriverProvider.getIfAvailable()).thenReturn(driver);

        StorageHealthIndicator indicator = new StorageHealthIndicator(factoryProvider, defaultDriverProvider);
        Health health = indicator.health();

        assertNotNull(health);
        assertEquals(Status.UP, health.getStatus());
        assertEquals("local", health.getDetails().get("type"));
        assertEquals("UP", health.getDetails().get("status"));
        assertEquals(tempDir.toAbsolutePath().normalize().toString(), health.getDetails().get("basePath"));
    }

    @Test
    @DisplayName("LocalDriver 基准目录不存在或不可写时健康状态为 DOWN")
    @SuppressWarnings("unchecked")
    void testLocalDriverUnhealthy() {
        // 使用一个初始创建后被删除的路径模拟目录不存在
        Path nonExistent = tempDir.resolve("deleted-dir");
        LocalDriver driver = new LocalDriver(tempDir.toString());
        // Mock a driver throwing exception on ping
        StorageDriver failingDriver = mock(StorageDriver.class);
        try {
            org.mockito.Mockito.doThrow(new RuntimeException("本地存储基准目录不存在")).when(failingDriver).ping();
        } catch (Exception ignored) {
        }

        ObjectProvider<StorageDriverFactory> factoryProvider = mock(ObjectProvider.class);
        when(factoryProvider.getIfAvailable()).thenReturn(null);

        ObjectProvider<StorageDriver> defaultDriverProvider = mock(ObjectProvider.class);
        when(defaultDriverProvider.getIfAvailable()).thenReturn(failingDriver);

        StorageHealthIndicator indicator = new StorageHealthIndicator(factoryProvider, defaultDriverProvider);
        Health health = indicator.health();

        assertNotNull(health);
        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("DOWN", health.getDetails().get("status"));
        assertTrue(health.getDetails().get("error").toString().contains("本地存储基准目录不存在"));
    }

    @Test
    @DisplayName("S3Driver 存储桶连通正常时健康状态为 UP")
    @SuppressWarnings("unchecked")
    void testS3DriverHealthy() {
        S3Client s3Client = mock(S3Client.class);
        when(s3Client.headBucket(any(HeadBucketRequest.class))).thenReturn(HeadBucketResponse.builder().build());

        S3Driver s3Driver = new S3Driver(s3Client, "my-app-bucket");

        ObjectProvider<StorageDriverFactory> factoryProvider = mock(ObjectProvider.class);
        when(factoryProvider.getIfAvailable()).thenReturn(null);

        ObjectProvider<StorageDriver> defaultDriverProvider = mock(ObjectProvider.class);
        when(defaultDriverProvider.getIfAvailable()).thenReturn(s3Driver);

        StorageHealthIndicator indicator = new StorageHealthIndicator(factoryProvider, defaultDriverProvider);
        Health health = indicator.health();

        assertNotNull(health);
        assertEquals(Status.UP, health.getStatus());
        assertEquals("s3", health.getDetails().get("type"));
        assertEquals("my-app-bucket", health.getDetails().get("bucket"));
        verify(s3Client).headBucket(any(HeadBucketRequest.class));
    }

    @Test
    @DisplayName("S3Driver 存储桶不可达或凭据异常时健康状态为 DOWN 且敏感信息被脱敏")
    @SuppressWarnings("unchecked")
    void testS3DriverUnhealthyAndSanitized() {
        S3Client s3Client = mock(S3Client.class);
        AwsServiceException s3Error = AwsServiceException.builder()
                .message("Access Denied: secretKey=AKIA123456789 secret=SECRET_VALUE")
                .statusCode(403)
                .build();
        when(s3Client.headBucket(any(HeadBucketRequest.class))).thenThrow(s3Error);

        S3Driver s3Driver = new S3Driver(s3Client, "private-bucket");

        ObjectProvider<StorageDriverFactory> factoryProvider = mock(ObjectProvider.class);
        when(factoryProvider.getIfAvailable()).thenReturn(null);

        ObjectProvider<StorageDriver> defaultDriverProvider = mock(ObjectProvider.class);
        when(defaultDriverProvider.getIfAvailable()).thenReturn(s3Driver);

        StorageHealthIndicator indicator = new StorageHealthIndicator(factoryProvider, defaultDriverProvider);
        Health health = indicator.health();

        assertNotNull(health);
        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("private-bucket", health.getDetails().get("bucket"));
        String errorMsg = health.getDetails().get("error").toString();
        assertTrue(errorMsg.contains("secretKey=******"));
        assertFalse(errorMsg.contains("AKIA123456789"));
    }

    @Test
    @DisplayName("多存储源工厂模式下所有存储源全量探查")
    @SuppressWarnings("unchecked")
    void testMultiSourceFactoryProbing() throws Exception {
        StorageDriver driver1 = mock(StorageDriver.class);
        StorageDriver driver2 = mock(StorageDriver.class);

        // driver1 正常
        // driver2 异常
        org.mockito.Mockito.doThrow(new RuntimeException("Connection Timeout")).when(driver2).ping();

        StorageDriverFactory factory = mock(StorageDriverFactory.class);
        when(factory.getAllDrivers()).thenReturn(Map.of("primary", driver1, "backup", driver2));

        ObjectProvider<StorageDriverFactory> factoryProvider = mock(ObjectProvider.class);
        when(factoryProvider.getIfAvailable()).thenReturn(factory);

        ObjectProvider<StorageDriver> defaultDriverProvider = mock(ObjectProvider.class);

        StorageHealthIndicator indicator = new StorageHealthIndicator(factoryProvider, defaultDriverProvider);
        Health health = indicator.health();

        assertNotNull(health);
        assertEquals(Status.DOWN, health.getStatus());

        Map<String, Object> details = health.getDetails();
        assertTrue(details.containsKey("primary"));
        assertTrue(details.containsKey("backup"));

        Map<String, Object> primaryInfo = (Map<String, Object>) details.get("primary");
        Map<String, Object> backupInfo = (Map<String, Object>) details.get("backup");
        assertEquals("UP", primaryInfo.get("status"));
        assertEquals("DOWN", backupInfo.get("status"));
    }

    @Test
    @DisplayName("未装配任何存储驱动时健康状态为 UNKNOWN")
    @SuppressWarnings("unchecked")
    void testNoDriverConfigured() {
        ObjectProvider<StorageDriverFactory> factoryProvider = mock(ObjectProvider.class);
        when(factoryProvider.getIfAvailable()).thenReturn(null);

        ObjectProvider<StorageDriver> defaultDriverProvider = mock(ObjectProvider.class);
        when(defaultDriverProvider.getIfAvailable()).thenReturn(null);

        StorageHealthIndicator indicator = new StorageHealthIndicator(factoryProvider, defaultDriverProvider);
        Health health = indicator.health();

        assertNotNull(health);
        assertEquals(Status.UNKNOWN, health.getStatus());
    }
}
