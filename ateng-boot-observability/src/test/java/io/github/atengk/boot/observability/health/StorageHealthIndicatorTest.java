package io.github.atengk.boot.observability.health;

import io.github.atengk.boot.storage.driver.StorageDriver;
import io.github.atengk.boot.storage.exception.StorageException;
import io.github.atengk.boot.storage.exception.StorageResultCode;
import io.github.atengk.boot.storage.factory.StorageDriverFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * StorageHealthIndicator 存储探针单元测试
 *
 * @author Ateng
 * @since 2026-10-22
 */
@DisplayName("StorageHealthIndicator 存储健康探针单元测试")
class StorageHealthIndicatorTest {

    @Test
    @DisplayName("未装配任何存储驱动时，返回 UNKNOWN 状态")
    void testNoDriverConfigured() {
        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        StorageHealthIndicator indicator = new StorageHealthIndicator(
                beanFactory.getBeanProvider(StorageDriverFactory.class),
                beanFactory.getBeanProvider(StorageDriver.class)
        );

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
        assertThat(health.getDetails()).containsEntry("message", "未检测到已装配的存储驱动");
    }

    @Test
    @DisplayName("单个默认驱动探测成功，返回 UP 状态与诊断详情")
    void testSingleDriverUp() throws Exception {
        StorageDriver mockDriver = mock(StorageDriver.class);
        doNothing().when(mockDriver).ping();
        Map<String, Object> diag = new LinkedHashMap<>();
        diag.put("type", "local");
        diag.put("basePath", "/data/storage");
        when(mockDriver.getDiagnostics()).thenReturn(diag);

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("defaultDriver", mockDriver);

        StorageHealthIndicator indicator = new StorageHealthIndicator(
                beanFactory.getBeanProvider(StorageDriverFactory.class),
                beanFactory.getBeanProvider(StorageDriver.class)
        );

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("status", "UP");
        assertThat(health.getDetails()).containsEntry("type", "local");
        assertThat(health.getDetails()).containsEntry("basePath", "/data/storage");
    }

    @Test
    @DisplayName("单个驱动探测失败且包含敏感 Token，返回 DOWN 状态并脱敏凭据")
    void testSingleDriverDownWithSensitiveSanitization() throws Exception {
        StorageDriver mockDriver = mock(StorageDriver.class);
        doThrow(new StorageException(StorageResultCode.STORAGE_HEALTH_CHECK_FAILED, "S3 连通失败: accessKey=AKIA1234567890 token=supersecret"))
                .when(mockDriver).ping();

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("defaultDriver", mockDriver);

        StorageHealthIndicator indicator = new StorageHealthIndicator(
                beanFactory.getBeanProvider(StorageDriverFactory.class),
                beanFactory.getBeanProvider(StorageDriver.class)
        );

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("status", "DOWN");
        String errorMsg = (String) health.getDetails().get("error");
        assertThat(errorMsg).contains("accessKey=******");
        assertThat(errorMsg).doesNotContain("AKIA1234567890");
    }

    @Test
    @DisplayName("多驱动工厂模式：所有驱动健康时返回 UP，任一驱动故障时整体返回 DOWN")
    void testMultipleDriversInFactory() throws Exception {
        StorageDriver driver1 = mock(StorageDriver.class);
        doNothing().when(driver1).ping();

        StorageDriver driver2 = mock(StorageDriver.class);
        doThrow(new RuntimeException("磁盘无写入权限")).when(driver2).ping();

        Map<String, StorageDriver> drivers = new LinkedHashMap<>();
        drivers.put("local-primary", driver1);
        drivers.put("s3-backup", driver2);

        StorageDriverFactory factory = new StorageDriverFactory("local-primary", drivers);

        StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
        beanFactory.addBean("storageDriverFactory", factory);

        StorageHealthIndicator indicator = new StorageHealthIndicator(
                beanFactory.getBeanProvider(StorageDriverFactory.class),
                beanFactory.getBeanProvider(StorageDriver.class),
                Duration.ofSeconds(1),
                null
        );

        Health health = indicator.health();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("local-primary");
        assertThat(health.getDetails()).containsKey("s3-backup");
    }
}
