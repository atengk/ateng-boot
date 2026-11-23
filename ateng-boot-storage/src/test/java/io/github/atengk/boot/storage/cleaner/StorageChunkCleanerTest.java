package io.github.atengk.boot.storage.cleaner;

import io.github.atengk.boot.storage.driver.StorageDriver;
import io.github.atengk.boot.storage.factory.StorageDriverFactory;
import io.github.atengk.boot.storage.properties.ChunkCleanerProperties;
import io.github.atengk.boot.storage.properties.StorageProperties;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.scheduling.TaskScheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ScheduledFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 孤儿分片生命周期治理组件单元测试
 *
 * @author Ateng
 * @since 2026-10-20
 */
@DisplayName("StorageChunkCleaner 碎片治理测试")
class StorageChunkCleanerTest {

    @Test
    @DisplayName("通过 StorageDriverFactory 扫描清理所有存储源的分片碎片")
    @SuppressWarnings("unchecked")
    void testTriggerCleanWithDriverFactory() {
        StorageProperties properties = new StorageProperties();
        ChunkCleanerProperties cleanerProps = new ChunkCleanerProperties();
        cleanerProps.setMaxAge(Duration.ofHours(12));
        properties.setChunkCleaner(cleanerProps);

        StorageDriver driver1 = mock(StorageDriver.class);
        StorageDriver driver2 = mock(StorageDriver.class);
        when(driver1.cleanExpiredChunkSessions(Duration.ofHours(12))).thenReturn(3);
        when(driver2.cleanExpiredChunkSessions(Duration.ofHours(12))).thenReturn(2);

        StorageDriverFactory factory = mock(StorageDriverFactory.class);
        when(factory.getAllDrivers()).thenReturn(Map.of("primary", driver1, "backup", driver2));

        ObjectProvider<StorageDriverFactory> factoryProvider = mock(ObjectProvider.class);
        when(factoryProvider.getIfAvailable()).thenReturn(factory);

        ObjectProvider<StorageDriver> defaultDriverProvider = mock(ObjectProvider.class);

        StorageChunkCleaner cleaner = new StorageChunkCleaner(properties, factoryProvider, defaultDriverProvider);
        try {
            int cleaned = cleaner.triggerClean();
            assertEquals(5, cleaned);
            verify(driver1).cleanExpiredChunkSessions(Duration.ofHours(12));
            verify(driver2).cleanExpiredChunkSessions(Duration.ofHours(12));
        } finally {
            cleaner.destroy();
        }
    }

    @Test
    @DisplayName("通过单驱动 DefaultDriverProvider 扫描清理分片碎片")
    @SuppressWarnings("unchecked")
    void testTriggerCleanWithDefaultDriver() {
        StorageProperties properties = new StorageProperties();

        StorageDriver driver = mock(StorageDriver.class);
        when(driver.cleanExpiredChunkSessions(any())).thenReturn(4);

        ObjectProvider<StorageDriverFactory> factoryProvider = mock(ObjectProvider.class);
        when(factoryProvider.getIfAvailable()).thenReturn(null);

        ObjectProvider<StorageDriver> defaultDriverProvider = mock(ObjectProvider.class);
        when(defaultDriverProvider.getIfAvailable()).thenReturn(driver);

        StorageChunkCleaner cleaner = new StorageChunkCleaner(properties, factoryProvider, defaultDriverProvider);
        try {
            int cleaned = cleaner.triggerClean();
            assertEquals(4, cleaned);
            verify(driver).cleanExpiredChunkSessions(Duration.ofHours(24));
        } finally {
            cleaner.destroy();
        }
    }

    @Test
    @DisplayName("驱动执行清理抛出异常时安全防御与降级日志")
    @SuppressWarnings("unchecked")
    void testCleanSafelyWhenDriverThrowsException() {
        StorageProperties properties = new StorageProperties();

        StorageDriver faultyDriver = mock(StorageDriver.class);
        when(faultyDriver.cleanExpiredChunkSessions(any())).thenThrow(new RuntimeException("Simulated IO Failure"));

        StorageDriver goodDriver = mock(StorageDriver.class);
        when(goodDriver.cleanExpiredChunkSessions(any())).thenReturn(1);

        StorageDriverFactory factory = mock(StorageDriverFactory.class);
        when(factory.getAllDrivers()).thenReturn(Map.of("faulty", faultyDriver, "good", goodDriver));

        ObjectProvider<StorageDriverFactory> factoryProvider = mock(ObjectProvider.class);
        when(factoryProvider.getIfAvailable()).thenReturn(factory);

        ObjectProvider<StorageDriver> defaultDriverProvider = mock(ObjectProvider.class);

        StorageChunkCleaner cleaner = new StorageChunkCleaner(properties, factoryProvider, defaultDriverProvider);
        try {
            int cleaned = assertDoesNotThrow(cleaner::triggerClean);
            assertEquals(1, cleaned);
        } finally {
            cleaner.destroy();
        }
    }

    @Test
    @DisplayName("优先复用外部注入的 Spring TaskScheduler 并于销毁时取消调度句柄")
    @SuppressWarnings("unchecked")
    void testSchedulesWithProvidedTaskSchedulerAndCancelsOnDestroy() {
        StorageProperties properties = new StorageProperties();
        ChunkCleanerProperties cleanerProps = new ChunkCleanerProperties();
        cleanerProps.setInterval(Duration.ofMinutes(30));
        properties.setChunkCleaner(cleanerProps);

        ObjectProvider<StorageDriverFactory> factoryProvider = mock(ObjectProvider.class);
        ObjectProvider<StorageDriver> defaultDriverProvider = mock(ObjectProvider.class);

        TaskScheduler taskScheduler = mock(TaskScheduler.class);
        ScheduledFuture<?> future = mock(ScheduledFuture.class);
        doReturn(future).when(taskScheduler).scheduleWithFixedDelay(any(Runnable.class), any(Instant.class), eq(Duration.ofMinutes(30)));

        ObjectProvider<TaskScheduler> schedulerProvider = mock(ObjectProvider.class);
        when(schedulerProvider.getIfAvailable()).thenReturn(taskScheduler);

        StorageChunkCleaner cleaner = new StorageChunkCleaner(properties, factoryProvider, defaultDriverProvider, schedulerProvider);
        verify(taskScheduler).scheduleWithFixedDelay(any(Runnable.class), any(Instant.class), eq(Duration.ofMinutes(30)));

        cleaner.destroy();
        verify(future).cancel(false);
    }
}
